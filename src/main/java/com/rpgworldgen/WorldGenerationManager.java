package com.rpgworldgen;

import com.rpgworldgen.config.ConfigurationManager;
import com.rpgworldgen.integration.IntegrationManager;
import com.rpgworldgen.model.PlannedStructure;
import com.rpgworldgen.model.WorldPreset;
import com.rpgworldgen.persist.GenerationPersistenceService;
import com.rpgworldgen.plan.WorldPlanningEngine;
import com.rpgworldgen.sched.GenerationTaskScheduler;
import com.rpgworldgen.zones.SafeZoneManager;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Owns world lifecycle: create (with border + spawn setup), plan registration,
 * road stamping, safe-zone registration, recovery of unfinished jobs.
 */
public class WorldGenerationManager {

    private final RPGWorldGeneratorPlugin plugin;
    private final Logger log;
    private final ConfigurationManager configs;
    private final GenerationPersistenceService db;
    private final GenerationTaskScheduler scheduler;
    private final SafeZoneManager safeZones;
    private final WorldPlanningEngine planner = new WorldPlanningEngine();

    // world name -> plan (in-memory; authoritative copy is plan JSON in SQLite)
    private final Map<String, WorldPlanningEngine.Plan> plans = new HashMap<>();
    private final Map<String, Long> seeds = new HashMap<>();
    private final Map<String, Integer> sizes = new HashMap<>();

    public WorldGenerationManager(RPGWorldGeneratorPlugin plugin, ConfigurationManager configs,
                                  GenerationPersistenceService db, GenerationTaskScheduler scheduler,
                                  SafeZoneManager safeZones) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.configs = configs;
        this.db = db;
        this.scheduler = scheduler;
        this.safeZones = safeZones;
    }

    public WorldPlanningEngine.Plan getPlan(String world) { return plans.get(world); }

    // ---- create ----

    public static class CreateResult {
        public final boolean ok;
        public final String message;
        CreateResult(boolean ok, String message) { this.ok = ok; this.message = message; }
    }

    public CreateResult createWorld(String name, int size, long seed) {
        if (name == null || !name.matches("[A-Za-z0-9_\\-]{1,32}")) {
            return new CreateResult(false, "Invalid world name (1-32 chars, A-Z a-z 0-9 _ -).");
        }
        try {
            WorldPreset.validateSize(size);
        } catch (IllegalArgumentException e) {
            return new CreateResult(false, e.getMessage());
        }
        if (Bukkit.getWorld(name) != null) {
            return new CreateResult(false, "World '" + name + "' is already loaded.");
        }
        if (plugin.getDataFolder() != null
            && new java.io.File(Bukkit.getWorldContainer(), name).exists()) {
            return new CreateResult(false, "A world folder named '" + name + "' already exists. "
                + "Delete or rename it first — RPGWorldGenerator never overwrites existing worlds.");
        }
        // Disk-space guard (best effort).
        long usable = Bukkit.getWorldContainer().getUsableSpace();
        long need = (long) size * size / 256 * 50; // rough: ~50 bytes per 16x16 column
        if (usable < need) {
            log.warning("Low disk space for world '" + name + "': usable=" + usable + " estimated need=" + need);
        }

        long finalSeed = seed == 0 ? new java.util.Random().nextLong() : seed;
        WorldCreator creator = new WorldCreator(name);
        creator.seed(finalSeed);
        creator.type(WorldType.NORMAL);
        creator.environment(World.Environment.NORMAL);
        World world = Bukkit.createWorld(creator);
        if (world == null) {
            return new CreateResult(false, "Bukkit failed to create world '" + name + "'.");
        }
        world.setDifficulty(Difficulty.NORMAL);
        // Fresh worlds have no chunks generated at 0,0 yet; probing height here
        // would sync-generate on the calling thread (watchdog risk). The scheduler
        // touches spawn chunks on the first tick; defer exact spawn placement.
        world.setSpawnLocation(0, world.getMaxHeight() / 2, 0);
        // Border matches playable area.
        new com.rpgworldgen.border.WorldBorderManager().applyBorder(world, size);
        world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);

        seeds.put(name, finalSeed);
        sizes.put(name, size);
        WorldPreset preset = WorldPreset.fromSize(size);
        try {
            db.saveWorld(name, finalSeed, size, preset == null ? "CUSTOM" : preset.name(),
                "CREATED", null);
        } catch (SQLException e) {
            return new CreateResult(false, "Database error: " + e.getMessage());
        }
        // worlds.yml registry for admins.
        configs.getWorlds().set("worlds." + name + ".size", size);
        configs.getWorlds().set("worlds." + name + ".seed", finalSeed);
        configs.saveWorlds();
        return new CreateResult(true, "World '" + name + "' created (" + size + "x" + size
            + ", seed " + finalSeed + "). Run /rpgworld generate " + name + " to build it.");
    }

    // ---- plan + generate ----

    public String planWorld(String name) {
        Integer size = sizes.get(name);
        Long seed = seeds.get(name);
        if (size == null || seed == null) return "Unknown world '" + name + "'. Create it first.";
        WorldPreset preset = WorldPreset.fromSize(size);
        WorldPlanningEngine.PlanRequest req = new WorldPlanningEngine.PlanRequest();
        req.seed = seed;
        req.size = size;
        if (preset != null) {
            req.villages = preset.getDefaultVillages();
            req.cities = preset.getDefaultCities();
            req.kingdoms = preset.getDefaultKingdoms();
            req.dungeons = preset.getDefaultDungeons();
            req.villageSpacing = preset.getVillageSpacing();
            req.citySpacing = preset.getCitySpacing();
            req.kingdomSpacing = preset.getKingdomSpacing();
        } else {
            req.villageSpacing = configs.getVillageSpacing();
            req.citySpacing = configs.getCitySpacing();
            req.kingdomSpacing = configs.getKingdomSpacing();
            req.dungeons = configs.getDungeons().getInt("dungeons.count-medium", 14);
        }
        req.villages = configs.getSettlements().getInt("villages.count-" + sizeKey(size), req.villages);
        req.cities = configs.getSettlements().getInt("cities.count-" + sizeKey(size), req.cities);
        req.kingdoms = configs.getSettlements().getInt("kingdoms.count-" + sizeKey(size), req.kingdoms);
        req.dungeons = configs.getDungeons().getInt("dungeons.count-" + sizeKey(size), req.dungeons);
        req.landmarks = configs.getStructures().getInt("structures.landmarks-" + sizeKey(size), 4);
        req.villageSpacing = configs.getSettlements().getInt("villages.min-spacing", req.villageSpacing);
        req.citySpacing = configs.getSettlements().getInt("cities.min-spacing", req.citySpacing);
        req.kingdomSpacing = configs.getSettlements().getInt("kingdoms.min-spacing", req.kingdomSpacing);
        req.dungeonSpacing = configs.getDungeonSpacing();
        req.minGap = configs.getSettlements().getInt("global-min-gap", 80);
        WorldPlanningEngine.Plan plan = planner.generatePlan(req);
        plans.put(name, plan);
        // Persist plan JSON + structure records + roads.
        try {
            db.saveWorld(name, seed, size, preset == null ? "CUSTOM" : preset.name(),
                "PLANNED", planner.toJson(plan));
            for (PlannedStructure s : plan.structures) {
                db.saveStructure(name, s, false);
            }
            Map<String, PlannedStructure> byId = new HashMap<>();
            for (PlannedStructure s : plan.structures) byId.put(s.getId(), s);
            int idx = 0;
            for (com.rpgworldgen.model.PlannedRoad road : plan.roads) {
                PlannedStructure a = byId.get(road.getFromId());
                db.saveRoad(name, idx++, road.getFromId(), road.getToId(),
                    a == null ? "plains" : a.getTheme(), road.getWaypoints());
            }
        } catch (SQLException e) {
            return "Planning succeeded but persistence failed: " + e.getMessage();
        }
        registerSafeZones(name, plan);
        return null; // null = success
    }

    private String sizeKey(int size) {
        if (size <= 5000) return "small";
        if (size <= 6000) return "medium";
        return "large";
    }

    /** Register spawn + capital safe zones from the plan (built-in protection). */
    private void registerSafeZones(World world, WorldPlanningEngine.Plan plan) {
        String w = world.getName();
        Set<String> spawnFlags = new HashSet<>();
        spawnFlags.add("pvp-deny");
        spawnFlags.add("mob-spawn-deny");
        spawnFlags.add("break-deny");
        spawnFlags.add("place-deny");
        spawnFlags.add("explosion-deny");
        spawnFlags.add("fire-deny");
        String enterMsg = configs.getSafezones().getString("safezones.enter-message", "Entering {zone}");
        String exitMsg = configs.getSafezones().getString("safezones.exit-message", "Leaving {zone}");
        int spawnR = configs.getSafezones().getInt("safezones.spawn-radius", 80);
        int capitalR = configs.getSafezones().getInt("safezones.capital-radius", 120);
        for (PlannedStructure s : plan.structures) {
            if (s.getKind() == PlannedStructure.Kind.SPAWN) {
                safeZones.addZone(new SafeZoneManager.Zone(w + ":spawn", w, "Spawn", s.getX(), s.getZ(),
                    spawnR, spawnFlags, enterMsg, exitMsg));
                try {
                    db.saveSafeZone(w + ":spawn", w, "Spawn", s.getX(), s.getZ(),
                        spawnR, "pvp,mob,break,place,explosion,fire");
                } catch (SQLException e) {
                    log.warning("Safe-zone persist failed: " + e.getMessage());
                }
            } else if (s.getKind() == PlannedStructure.Kind.KINGDOM_CAPITAL) {
                String zid = w + ":capital:" + s.getId().hashCode();
                safeZones.addZone(new SafeZoneManager.Zone(zid, w, s.getName(), s.getX(), s.getZ(),
                    capitalR, spawnFlags, enterMsg, exitMsg));
                try {
                    db.saveSafeZone(zid, w, s.getName(), s.getX(), s.getZ(),
                        capitalR, "pvp,mob,break,place,explosion,fire");
                } catch (SQLException e) {
                    log.warning("Safe-zone persist failed: " + e.getMessage());
                }
            }
        }
        // Mirror every zone (spawn + capitals) to WorldGuard when present; built-in
        // zones always apply regardless. Deterministic ids keep link idempotent.
        if (configs.preferWorldGuard()) {
            mirrorWorldGuardZones(w);
        }
    }

    /**
     * Mirror all registered safe zones of a world to WorldGuard with real
     * protection flags. Returns a human-readable summary (or a skip reason).
     * Idempotent: same deterministic ids every run. Never throws.
     */
    public String mirrorWorldGuardZones(String world) {
        IntegrationManager integrations = plugin.getIntegrations();
        if (!integrations.hasWorldGuard()) {
            return "WorldGuard not installed; built-in zones active.";
        }
        boolean greet = configs.getConfig().getBoolean("integrations.worldguard.greet-enabled", true);
        List<IntegrationManager.ZoneSpec> specs = new ArrayList<>();
        for (SafeZoneManager.Zone z : safeZones.zonesInWorld(world)) {
            specs.add(new IntegrationManager.ZoneSpec(
                IntegrationManager.zoneRegionId(world, z.cx, z.cz),
                z.cx, z.cz, z.radius, z.flags,
                greet ? wgText(z.enterMessage, z.name) : null,
                greet ? wgText(z.exitMessage, z.name) : null));
        }
        if (specs.isEmpty()) {
            return "No safe zones registered for '" + world + "'.";
        }
        IntegrationManager.MirrorResult r = integrations.mirrorSafeZones(world, specs);
        StringBuilder sb = new StringBuilder("WorldGuard: mirrored " + r.ok + "/" + specs.size()
            + " zones for '" + world + "'.");
        for (String e : r.errors) sb.append('\n').append("- ").append(e);
        return sb.toString();
    }

    /** WG GREETING/FAREWELL flags are plain text: strip &-color codes, fill {zone}. */
    private static String wgText(String s, String zoneName) {
        if (s == null) return null;
        String stripped = s.replaceAll("&[0-9a-fk-or]", "");
        stripped = stripped.replace("{zone}", zoneName == null ? "" : zoneName).trim();
        stripped = stripped.replaceAll("  +", " ");
        return stripped.isEmpty() ? null : stripped;
    }

    private void registerSafeZones(String worldName, WorldPlanningEngine.Plan plan) {
        World world = Bukkit.getWorld(worldName);
        if (world != null) registerSafeZones(world, plan);
        else {
            // Register zones without world ref (manager matches by world name at event time).
            int spawnR = configs.getSafezones().getInt("safezones.spawn-radius", 80);
            int capitalR = configs.getSafezones().getInt("safezones.capital-radius", 120);
            String enterMsg = configs.getSafezones().getString("safezones.enter-message", "Entering {zone}");
            String exitMsg = configs.getSafezones().getString("safezones.exit-message", "Leaving {zone}");
            Set<String> spawnFlags = new HashSet<>();
            spawnFlags.add("pvp-deny");
            spawnFlags.add("mob-spawn-deny");
            spawnFlags.add("break-deny");
            spawnFlags.add("place-deny");
            spawnFlags.add("explosion-deny");
            spawnFlags.add("fire-deny");
            for (PlannedStructure s : plan.structures) {
                Set<String> flags = new HashSet<>(spawnFlags);
                if (s.getKind() == PlannedStructure.Kind.SPAWN) {
                    safeZones.addZone(new SafeZoneManager.Zone(worldName + ":spawn", worldName, "Spawn",
                        s.getX(), s.getZ(), spawnR, flags, enterMsg, exitMsg));
                } else if (s.getKind() == PlannedStructure.Kind.KINGDOM_CAPITAL) {
                    String zid = worldName + ":capital:" + s.getId().hashCode();
                    safeZones.addZone(new SafeZoneManager.Zone(zid, worldName, s.getName(),
                        s.getX(), s.getZ(), capitalR, flags, enterMsg, exitMsg));
                }
            }
        }
    }

    public String startGeneration(String name) {
        WorldPlanningEngine.Plan plan = plans.get(name);
        if (plan == null) {
            String err = planWorld(name);
            if (err != null) return err;
            plan = plans.get(name);
        }
        // Re-plan validation: drop structures that fell outside border (shouldn't happen).
        int size = sizes.getOrDefault(name, 6000);
        int half = size / 2;
        plan.structures.removeIf(s -> Math.abs(s.getX()) > half || Math.abs(s.getZ()) > half);
        scheduler.submit(name, seeds.get(name), plan);
        // Roads are stamped by the scheduler's road phase (after endpoints exist), using
        // persisted coarse waypoints refined with live terrain sampling. No separate task needed.
        if (configs.getConfig().getBoolean("integrations.register-multiverse", true)) {
            String mvOutcome = plugin.getIntegrations().registerWorldMultiverse(name);
            log.info("Multiverse link for '" + name + "': " + mvOutcome);
        }
        try {
            db.updateWorldState(name, "GENERATING");
        } catch (SQLException e) {
            log.warning("State update failed: " + e.getMessage());
        }
        return null;
    }

    /** Resume unfinished jobs found in the database (called on enable). */
    public void recoverUnfinished() {
        // Recovery: any world in GENERATING/PLANNED state with unbuilt structures resumes.
        // Structure-plan JSON reload is implemented in GenerationRecoveryService.
        GenerationRecoveryService recovery = new GenerationRecoveryService(plugin, db, this, scheduler);
        recovery.recover();
    }

    public String statusLine(String name) {
        GenerationTaskScheduler.Job job = scheduler.getJob(name);
        WorldPlanningEngine.Plan plan = plans.get(name);
        Long seed = seeds.get(name);
        Integer size = sizes.get(name);
        StringBuilder sb = new StringBuilder();
        sb.append("RPG World: ").append(name).append('\n');
        sb.append("Preset: ").append(size == null ? "?" : size + "x" + size).append('\n');
        sb.append("Seed: ").append(seed == null ? "?" : seed).append('\n');
        sb.append("Planning: ").append(plan == null ? "Not planned" : "Complete").append('\n');
        if (job != null) {
            sb.append("Structures: ").append(job.done).append(" / ").append(job.total).append('\n');
            sb.append("Status: ").append(job.state).append(" (").append(job.stage).append(")\n");
            sb.append("Errors: ").append(job.errors).append('\n');
        } else if (plan != null) {
            sb.append("Structures: 0 / ").append(plan.structures.size()).append('\n');
            sb.append("Status: Idle\nErrors: 0\n");
        } else {
            sb.append("Status: Unknown world\n");
        }
        sb.append(integrationLine(name));
        return sb.toString();
    }

    /**
     * One-line-per-integration state for status/link output: installed flags,
     * Multiverse registration, and WorldGuard mirrored region ids.
     */
    public String integrationLine(String name) {
        IntegrationManager im = plugin.getIntegrations();
        StringBuilder sb = new StringBuilder("Integrations:");
        sb.append(" Multiverse=").append(im.hasMultiverse() ? "on" : "off");
        if (im.hasMultiverse()) {
            sb.append(im.isMultiverseWorld(name) ? "(registered)" : "(unregistered)");
        }
        sb.append(" WorldGuard=").append(im.hasWorldGuard() ? "on" : "off");
        if (im.hasWorldGuard()) {
            java.util.Set<String> mirrored = im.mirroredRegions(name);
            sb.append(mirrored.isEmpty() ? "(none mirrored)"
                : "(mirrored: " + String.join(", ", mirrored) + ")");
        }
        sb.append(" MV-last=").append(im.getLastMvResult());
        return sb.toString();
    }

    /** Verify/repair the Multiverse registration for a world (idempotent). */
    public String linkMultiverse(String name) {
        if (!plugin.getIntegrations().hasMultiverse()) {
            return "Multiverse-Core not installed; using vanilla world access.";
        }
        if (Bukkit.getWorld(name) == null) {
            return "World '" + name + "' is not loaded.";
        }
        return plugin.getIntegrations().registerWorldMultiverse(name);
    }

    void registerKnownWorld(String name, long seed, int size, WorldPlanningEngine.Plan plan) {
        seeds.put(name, seed);
        sizes.put(name, size);
        if (plan != null) plans.put(name, plan);
    }

    /** Re-register built-in zones from a recovered plan (recovery path). */
    void recoverSafeZones(WorldPlanningEngine.Plan plan) {
        for (java.util.Map.Entry<String, WorldPlanningEngine.Plan> e : plans.entrySet()) {
            if (e.getValue() == plan) {
                registerSafeZones(e.getKey(), plan);
                return;
            }
        }
    }

    /**
     * Rebuild structures whose id or name contains the region filter (case-insensitive).
     * The plan is deterministic for the same seed+size, so a re-plan yields the same
     * layout and only matching structures are re-queued (marked unbuilt first).
     *
     * @return status message (success text or error starting with "Unknown"/"No")
     */
    public String regenerateRegion(String name, String filter) {
        String err = planWorld(name);
        if (err != null) return err;
        WorldPlanningEngine.Plan plan = plans.get(name);
        Long seed = seeds.get(name);
        Integer size = sizes.get(name);
        if (plan == null || seed == null || size == null) return "Unknown world '" + name + "'.";
        String f = filter.toLowerCase();
        List<PlannedStructure> targets = new ArrayList<>();
        for (PlannedStructure s : plan.structures) {
            if (s.getId().toLowerCase().contains(f) || s.getName().toLowerCase().contains(f)) {
                targets.add(s);
            }
        }
        if (targets.isEmpty()) return "No structures match '" + filter + "' in '" + name + "'.";
        GenerationTaskScheduler.Job running = scheduler.getJob(name);
        if (running != null && running.state == GenerationTaskScheduler.JobState.RUNNING) {
            return "World '" + name + "' is already generating. Pause or wait first.";
        }
        try {
            for (PlannedStructure s : targets) {
                db.saveStructure(name, s, false);
            }
        } catch (SQLException e) {
            return "Database error: " + e.getMessage();
        }
        scheduler.rebuild(name, seed, size, targets);
        try {
            db.updateWorldState(name, "GENERATING");
        } catch (SQLException e) {
            log.warning("State update failed: " + e.getMessage());
        }
        return targets.size() + " structure(s) matching '" + filter + "' queued for rebuild in '" + name + "'.";
    }

    public Map<String, Integer> getSizes() { return sizes; }
    public IntegrationManager getIntegrations() { return plugin.getIntegrations(); }
}
