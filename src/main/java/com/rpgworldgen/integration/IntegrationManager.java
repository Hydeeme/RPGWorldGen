package com.rpgworldgen.integration;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Optional-integration adapters behind soft dependencies. Every integration is
 * probed at enable-time; missing plugins only disable their own adapter and
 * never break the core. Supported versions are documented in docs/integrations.md.
 *
 * <p>WorldGuard mirroring is best-effort and reflective (no compile dependency):
 * every flag is set individually so one missing/renamed flag on a given WG
 * version cannot break the rest. Use {@link #mirrorSafeZones} + the
 * {@code /rpgworld link} command to verify and repair mirrors at any time.
 */
public class IntegrationManager {

    /** A zone to mirror as a WorldGuard cuboid region. Pure data. */
    public static class ZoneSpec {
        public final String regionId;
        public final int cx, cz, radius;
        public final Set<String> zoneFlags;
        public final String greet;
        public final String farewell;

        public ZoneSpec(String regionId, int cx, int cz, int radius,
                        Set<String> zoneFlags, String greet, String farewell) {
            this.regionId = sanitizeRegionId(regionId);
            this.cx = cx;
            this.cz = cz;
            this.radius = radius;
            this.zoneFlags = zoneFlags == null ? Collections.emptySet() : zoneFlags;
            this.greet = greet;
            this.farewell = farewell;
        }
    }

    /** Outcome of a mirror run. */
    public static class MirrorResult {
        public int ok;
        public int failed;
        public final List<String> errors = new ArrayList<>();
    }

    private final Logger log;
    private boolean multiverse;
    private boolean worldEdit;
    private boolean worldGuard;
    private boolean vault;
    private boolean citizens;
    private boolean placeholderApi;

    /** Sanitized WG region ids mirrored per world this session (for status). */
    private final Map<String, Set<String>> mirroredByWorld = new HashMap<>();
    private String lastMvResult = "never attempted";

    public IntegrationManager(Logger log) {
        this.log = log;
    }

    public void detect() {
        multiverse = has("Multiverse-Core");
        worldEdit = has("WorldEdit");
        worldGuard = has("WorldGuard");
        vault = has("Vault");
        citizens = has("Citizens");
        placeholderApi = has("PlaceholderAPI");
        log.info("Integrations: Multiverse=" + multiverse + " WorldEdit=" + worldEdit
            + " WorldGuard=" + worldGuard + " Vault=" + vault + " Citizens=" + citizens
            + " PlaceholderAPI=" + placeholderApi);
    }

    private boolean has(String name) {
        try {
            Plugin p = Bukkit.getPluginManager().getPlugin(name);
            return p != null && p.isEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean hasMultiverse() { return multiverse; }
    public boolean hasWorldEdit() { return worldEdit; }
    public boolean hasWorldGuard() { return worldGuard; }
    public boolean hasVault() { return vault; }
    public boolean hasCitizens() { return citizens; }
    public boolean hasPlaceholderApi() { return placeholderApi; }

    /** Last Multiverse outcome (for status reporting). */
    public String getLastMvResult() { return lastMvResult; }

    /** WG region ids mirrored for a world this session (empty when none/restarted). */
    public Set<String> mirroredRegions(String world) {
        Set<String> s = mirroredByWorld.get(world);
        return s == null ? Collections.emptySet() : Collections.unmodifiableSet(s);
    }

    // ---- pure helpers (unit-testable, no Bukkit) ----

    /**
     * Sanitize a WorldGuard region id: lowercase, only [a-z0-9_-], max 60 chars.
     * Our internal zone ids contain colons (e.g. {@code world:spawn}) which WG rejects.
     */
    public static String sanitizeRegionId(String raw) {
        if (raw == null || raw.isEmpty()) return "rpg_zone";
        String s = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        if (s.length() > 60) s = s.substring(0, 60);
        return s.isEmpty() ? "rpg_zone" : s;
    }

    /**
     * Deterministic WG region id for a zone: stable across restarts and
     * identical whether computed at plan time or by {@code /rpgworld link}.
     */
    public static String zoneRegionId(String world, int cx, int cz) {
        return sanitizeRegionId("rpg_" + world + "_" + cx + "_" + cz);
    }

    /**
     * Map built-in zone flags to WorldGuard {@code Flags} field names to set DENY.
     * Pure function so the mapping is unit-tested without a server.
     */
    public static List<String> wgDenyFlags(Set<String> zoneFlags) {
        List<String> out = new ArrayList<>();
        if (zoneFlags.contains("pvp-deny")) out.add("PVP");
        if (zoneFlags.contains("break-deny")) out.add("BLOCK_BREAK");
        if (zoneFlags.contains("place-deny")) out.add("BLOCK_PLACE");
        if (zoneFlags.contains("mob-spawn-deny")) out.add("MOB_SPAWNING");
        if (zoneFlags.contains("explosion-deny")) {
            out.add("TNT");
            out.add("OTHER_EXPLOSION");
            out.add("CREEPER_EXPLOSION");
        }
        if (zoneFlags.contains("fire-deny")) {
            out.add("FIRE_SPREAD");
            out.add("LAVA_FIRE");
        }
        return out;
    }

    /** Console command that registers an already-on-disk world with Multiverse. */
    public static String multiverseImportCommand(String worldName) {
        return "mv import " + worldName + " normal";
    }

    // ---- Multiverse ----

    /**
     * True when Multiverse already knows this world (reflective, no hard dep).
     * Supports Multiverse-Core 5.x ({@code getApi().getWorldManager().getWorld()}
     * returning a vavr Option) with a 4.x fallback
     * ({@code getMVWorldManager().isMVWorld()}).
     */
    public boolean isMultiverseWorld(String worldName) {
        if (!multiverse) return false;
        try {
            Plugin mv = Bukkit.getPluginManager().getPlugin("Multiverse-Core");
            if (mv == null || !mv.isEnabled()) return false;
            try {
                Object api = mv.getClass().getMethod("getApi").invoke(mv);
                Object wm = api.getClass().getMethod("getWorldManager").invoke(api);
                Object opt = wm.getClass().getMethod("getWorld", String.class)
                    .invoke(wm, worldName);
                return Boolean.TRUE.equals(opt.getClass().getMethod("isDefined").invoke(opt));
            } catch (NoSuchMethodException legacy) {
                Object mgr = mv.getClass().getMethod("getMVWorldManager").invoke(mv);
                Object res = mgr.getClass().getMethod("isMVWorld", String.class)
                    .invoke(mgr, worldName);
                return Boolean.TRUE.equals(res);
            }
        } catch (Exception e) {
            log.warning("Multiverse check failed for '" + worldName + "': " + e.getMessage());
            return false;
        }
    }

    /**
     * Register a world with Multiverse-Core when present so /mv tp works.
     * Idempotent: already-registered worlds are skipped. Prefers the native
     * 5.x API ({@code WorldManager.importWorld}) with a console-command
     * fallback for older versions. Returns a human-readable outcome; never throws.
     */
    public String registerWorldMultiverse(String worldName) {
        if (!multiverse) {
            lastMvResult = "skipped (not installed)";
            return "Multiverse-Core not installed; skipping (vanilla world access).";
        }
        if (isMultiverseWorld(worldName)) {
            lastMvResult = "already registered";
            return "already registered with Multiverse.";
        }
        String apiOutcome = importWorldViaApi(worldName);
        if (apiOutcome == null) {
            lastMvResult = "imported via API";
            return "imported via Multiverse API.";
        }
        if (!apiOutcome.startsWith("unsupported")) {
            lastMvResult = "import failed: " + apiOutcome;
            log.warning("Multiverse import failed for '" + worldName + "': " + apiOutcome);
            return "import failed: " + apiOutcome;
        }
        try {
            boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                multiverseImportCommand(worldName));
            lastMvResult = ok ? "import dispatched" : "import rejected";
            return ok ? "import command dispatched (" + multiverseImportCommand(worldName) + ")."
                : "import command was rejected by the server; check console.";
        } catch (Exception e) {
            lastMvResult = "import failed: " + e.getMessage();
            log.warning("Multiverse import failed for '" + worldName + "': " + e.getMessage());
            return "import failed: " + e.getMessage();
        }
    }

    /**
     * Import via the MV5 native API. Returns null on success, otherwise a short
     * reason. Returns "unsupported..." when the 5.x API is absent so the caller
     * can fall back to the console command.
     */
    private String importWorldViaApi(String worldName) {
        try {
            Plugin mv = Bukkit.getPluginManager().getPlugin("Multiverse-Core");
            if (mv == null || !mv.isEnabled()) return "Multiverse-Core not available";
            Object api;
            Object wm;
            try {
                api = mv.getClass().getMethod("getApi").invoke(mv);
                wm = api.getClass().getMethod("getWorldManager").invoke(api);
            } catch (NoSuchMethodException e) {
                return "unsupported (pre-5.x API)";
            }
            org.bukkit.World bukkitWorld = Bukkit.getWorld(worldName);
            org.bukkit.World.Environment env = bukkitWorld == null
                ? org.bukkit.World.Environment.NORMAL : bukkitWorld.getEnvironment();
            Class<?> optsClass = Class.forName(
                "org.mvplugins.multiverse.core.world.options.ImportWorldOptions");
            Object opts = optsClass.getMethod("worldName", String.class).invoke(null, worldName);
            opts = optsClass.getMethod("environment", org.bukkit.World.Environment.class)
                .invoke(opts, env);
            Object attempt = wm.getClass().getMethod("importWorld", optsClass).invoke(wm, opts);
            boolean success = (Boolean) attempt.getClass().getMethod("isSuccess").invoke(attempt);
            if (success) return null;
            Object msg = attempt.getClass().getMethod("getFailureMessage").invoke(attempt);
            return msg == null ? "unknown failure" : msg.toString();
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            return "API import error: " + msg;
        }
    }

    // ---- WorldGuard ----

    /**
     * Mirror one zone as a WorldGuard cuboid region with real protection flags.
     *
     * @return null on success, otherwise a short error description (never throws)
     */
    public String mirrorSafeZone(String worldName, ZoneSpec spec) {
        if (!worldGuard) return "WorldGuard not installed";
        try {
            Object wg = Class.forName("com.sk89q.worldguard.WorldGuard")
                .getMethod("getInstance").invoke(null);
            Object platform = wg.getClass().getMethod("getPlatform").invoke(wg);
            Object container = platform.getClass().getMethod("getRegionContainer").invoke(platform);
            org.bukkit.World world = Bukkit.getWorld(worldName);
            if (world == null) return "world '" + worldName + "' is not loaded";
            Object weWorld;
            try {
                weWorld = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter")
                    .getMethod("adapt", org.bukkit.World.class).invoke(null, world);
            } catch (ClassNotFoundException e) {
                return "WorldEdit (required by WorldGuard) not installed";
            }
            Object manager = container.getClass()
                .getMethod("get", Class.forName("com.sk89q.worldedit.world.World"))
                .invoke(container, weWorld);
            if (manager == null) return "no region manager for world";
            Class<?> vecClass = Class.forName("com.sk89q.worldedit.math.BlockVector3");
            Object min = vecClass.getMethod("at", int.class, int.class, int.class)
                .invoke(null, spec.cx - spec.radius, world.getMinHeight(), spec.cz - spec.radius);
            Object max = vecClass.getMethod("at", int.class, int.class, int.class)
                .invoke(null, spec.cx + spec.radius, world.getMaxHeight(), spec.cz + spec.radius);
            Class<?> cuboidClass = Class.forName(
                "com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion");
            Object region = cuboidClass
                .getConstructor(String.class, vecClass, vecClass)
                .newInstance(spec.regionId, min, max);
            List<String> flagProblems = new ArrayList<>();
            setDenyFlags(region, spec.zoneFlags, flagProblems);
            // WG renames across versions: GREETING/FAREWELL (<= 7.0.x) vs
            // GREET_MESSAGE/FAREWELL_MESSAGE (>= 7.1 with titles). Try both.
            setTextFlag(region, new String[]{"GREET_MESSAGE", "GREETING"},
                spec.greet, flagProblems);
            setTextFlag(region, new String[]{"FAREWELL_MESSAGE", "FAREWELL"},
                spec.farewell, flagProblems);
            manager.getClass().getMethod("addRegion", Class.forName(
                    "com.sk89q.worldguard.protection.regions.ProtectedRegion"))
                .invoke(manager, region);
            saveRegions(manager);
            mirroredByWorld.computeIfAbsent(worldName, k -> new HashSet<>()).add(spec.regionId);
            if (!flagProblems.isEmpty()) {
                return "mirrored with flag warnings: " + String.join("; ", flagProblems);
            }
            return null;
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            Throwable cause = e.getCause();
            if (cause != null && cause.getMessage() != null) msg += " (" + cause.getMessage() + ")";
            log.warning("WorldGuard region creation failed (" + spec.regionId + "): " + msg);
            return msg;
        }
    }

    /** Mirror a batch of zones; per-zone errors are collected, never thrown. */
    public MirrorResult mirrorSafeZones(String worldName, Collection<ZoneSpec> zones) {
        MirrorResult r = new MirrorResult();
        if (!worldGuard) {
            r.failed = zones.size();
            if (!zones.isEmpty()) r.errors.add("WorldGuard not installed");
            return r;
        }
        for (ZoneSpec z : zones) {
            String err = mirrorSafeZone(worldName, z);
            if (err == null) {
                r.ok++;
            } else if (err.startsWith("mirrored with flag warnings")) {
                r.ok++;
                r.errors.add(z.regionId + ": " + err);
            } else {
                r.failed++;
                r.errors.add(z.regionId + ": " + err);
            }
        }
        return r;
    }

    /** Set every mapped DENY flag individually; one bad flag never breaks the rest. */
    private void setDenyFlags(Object region, Set<String> zoneFlags, List<String> problems) {
        Object deny;
        try {
            Class<?> stateEnum = Class.forName("com.sk89q.worldguard.protection.flags.StateFlag$State");
            deny = stateEnum.getMethod("valueOf", String.class).invoke(null, "DENY");
        } catch (Exception e) {
            problems.add("DENY state unavailable: " + e.getMessage());
            return;
        }
        java.lang.reflect.Method setFlag;
        try {
            setFlag = region.getClass().getMethod("setFlag",
                Class.forName("com.sk89q.worldguard.protection.flags.Flag"), Object.class);
        } catch (Exception e) {
            problems.add("setFlag unavailable: " + e.getMessage());
            return;
        }
        Class<?> flagsClass;
        try {
            flagsClass = Class.forName("com.sk89q.worldguard.protection.flags.Flags");
        } catch (ClassNotFoundException e) {
            problems.add("Flags class unavailable");
            return;
        }
        for (String field : wgDenyFlags(zoneFlags)) {
            try {
                Object flag = flagsClass.getField(field).get(null);
                setFlag.invoke(region, flag, deny);
            } catch (Exception e) {
                problems.add("flag " + field + " unavailable on this WorldGuard version");
            }
        }
    }

    /** Try each candidate Flags field in order; first hit wins, else one warning. */
    private void setTextFlag(Object region, String[] fields, String value, List<String> problems) {
        if (value == null || value.isEmpty()) return;
        try {
            Class<?> flagsClass = Class.forName("com.sk89q.worldguard.protection.flags.Flags");
            java.lang.reflect.Method setFlag = region.getClass().getMethod("setFlag",
                Class.forName("com.sk89q.worldguard.protection.flags.Flag"), Object.class);
            for (String field : fields) {
                try {
                    setFlag.invoke(region, flagsClass.getField(field).get(null), value);
                    return;
                } catch (NoSuchFieldException missing) {
                    // try the next alias
                }
            }
            problems.add("flags " + String.join("/", fields) + " unavailable on this WorldGuard version");
        } catch (Exception e) {
            problems.add("greeting flags unavailable: " + e.getMessage());
        }
    }

    private void setTextFlag(Object region, String field, String value, List<String> problems) {
        setTextFlag(region, new String[]{field}, value, problems);
    }

    /** WG 7.1+ uses saveChanges(); older 7.0 uses save(). Try both. */
    private void saveRegions(Object manager) throws Exception {
        try {
            manager.getClass().getMethod("saveChanges").invoke(manager);
        } catch (NoSuchMethodException e) {
            manager.getClass().getMethod("save").invoke(manager);
        }
    }

    /**
     * Legacy single-region entry point (spawn only, no messages).
     * Prefer {@link #mirrorSafeZones} with full {@link ZoneSpec}s.
     */
    public boolean createWorldGuardRegion(String worldName, String id, int cx, int cz, int radius) {
        if (!worldGuard) return false;
        Set<String> flags = new HashSet<>();
        Collections.addAll(flags, "pvp-deny", "mob-spawn-deny", "break-deny",
            "place-deny", "explosion-deny", "fire-deny");
        return mirrorSafeZone(worldName,
            new ZoneSpec(id, cx, cz, radius, flags, null, null)) == null;
    }

    /** Schematic template registration point for WorldEdit (fawe supported via same API). */
    public boolean hasSchematicSupport() { return worldEdit; }
}
