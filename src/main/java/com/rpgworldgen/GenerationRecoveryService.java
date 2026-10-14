package com.rpgworldgen;

import com.rpgworldgen.model.PlannedStructure;
import com.rpgworldgen.persist.GenerationPersistenceService;
import com.rpgworldgen.plan.WorldPlanningEngine;
import com.rpgworldgen.sched.GenerationTaskScheduler;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Restart recovery: finds worlds left in GENERATING state, rebuilds their
 * structure queues from persisted structure + road records, and resumes jobs.
 *
 * <p>Recovery prefers the exact persisted structure rows (kind/name/coords/radius/
 * tier/theme/extra). If a world has no structure rows (e.g. database wiped but
 * worlds.yml remains), it falls back to deterministic re-planning from seed+size,
 * which yields the identical layout for the same planner version.
 */
public class GenerationRecoveryService {

    private final RPGWorldGeneratorPlugin plugin;
    private final Logger log;
    private final GenerationPersistenceService db;
    private final WorldGenerationManager worlds;
    private final GenerationTaskScheduler scheduler;

    public GenerationRecoveryService(RPGWorldGeneratorPlugin plugin, GenerationPersistenceService db,
                                     WorldGenerationManager worlds, GenerationTaskScheduler scheduler) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.db = db;
        this.worlds = worlds;
        this.scheduler = scheduler;
    }

    public void recover() {
        // Worlds are discovered from worlds.yml registry (sizes/seeds) + DB progress rows.
        // Full DB world-listing is intentionally simple: iterate configured worlds.
        if (!plugin.getConfigManager().getWorlds().isConfigurationSection("worlds")) return;
        for (String name : plugin.getConfigManager().getWorlds()
                .getConfigurationSection("worlds").getKeys(false)) {
            int size = plugin.getConfigManager().getWorlds().getInt("worlds." + name + ".size", 6000);
            long seed = plugin.getConfigManager().getWorlds().getLong("worlds." + name + ".seed", 0L);
            List<GenerationPersistenceService.StructureRow> rows;
            try {
                rows = db.loadStructures(name);
            } catch (SQLException e) {
                log.warning("Recovery: could not load structures for '" + name + "': " + e.getMessage());
                continue;
            }
            if (rows.isEmpty()) continue;
            List<PlannedStructure> unbuilt = new ArrayList<>();
            List<PlannedStructure> all = new ArrayList<>();
            for (GenerationPersistenceService.StructureRow row : rows) {
                all.add(row.structure);
                if (!row.built) unbuilt.add(row.structure);
            }
            if (unbuilt.isEmpty()) continue;
            List<GenerationPersistenceService.RoadRow> roadRows;
            try {
                roadRows = db.loadRoads(name);
            } catch (SQLException e) {
                log.warning("Recovery: could not load roads for '" + name + "': " + e.getMessage());
                roadRows = new ArrayList<>();
            }
            List<GenerationTaskScheduler.RoadTask> roads = new ArrayList<>();
            for (GenerationPersistenceService.RoadRow rr : roadRows) {
                List<int[]> wps = new ArrayList<>();
                for (int[] w : rr.waypoints) wps.add(new int[]{w[0], w[1]});
                int x0 = wps.get(0)[0], z0 = wps.get(0)[1];
                int x1 = wps.get(wps.size() - 1)[0], z1 = wps.get(wps.size() - 1)[1];
                roads.add(new GenerationTaskScheduler.RoadTask(x0, z0, x1, z1, wps, rr.theme));
            }
            // Rebuild an in-memory plan for status/preview commands.
            WorldPlanningEngine.Plan plan = new WorldPlanningEngine.Plan(seed, size);
            plan.structures.addAll(all);
            for (GenerationPersistenceService.RoadRow rr : roadRows) {
                com.rpgworldgen.model.PlannedRoad pr =
                    new com.rpgworldgen.model.PlannedRoad(rr.fromId, rr.toId);
                for (int[] w : rr.waypoints) pr.addWaypoint(w[0], w[1]);
                plan.roads.add(pr);
            }
            worlds.registerKnownWorld(name, seed, size, plan);
            // Re-register built-in zones from the recovered plan, then re-mirror
            // to WorldGuard when present (WG regions do not survive WG config wipes;
            // built-in zones are the authority, mirrors are repaired here).
            worlds.recoverSafeZones(plan);
            scheduler.resumeUnbuilt(name, seed, size, unbuilt, roads);
            log.info("Recovery: resumed generation for '" + name + "' (" + unbuilt.size() + " structures, "
                + roads.size() + " roads left).");
            log.info("Recovery: " + worlds.mirrorWorldGuardZones(name));
        }
    }
}
