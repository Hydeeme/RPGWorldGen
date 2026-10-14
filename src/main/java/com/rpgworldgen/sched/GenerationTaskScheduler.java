package com.rpgworldgen.sched;

import com.rpgworldgen.gen.BuildingEngine;
import com.rpgworldgen.gen.ChunkNotReadyException;
import com.rpgworldgen.gen.ChunkPreloader;
import com.rpgworldgen.gen.DungeonBuilder;
import com.rpgworldgen.gen.SettlementGenerator;
import com.rpgworldgen.gen.StructurePlacementEngine;
import com.rpgworldgen.gen.WildernessGenerator;
import com.rpgworldgen.model.PlannedStructure;
import com.rpgworldgen.persist.GenerationPersistenceService;
import com.rpgworldgen.plan.WorldPlanningEngine;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Bounded incremental generation scheduler.
 *
 * <p>Each active world gets a {@link Job} holding a queue of structure tasks.
 * A repeating sync task processes one structure per tick (enqueueing its blocks)
 * and drains a bounded number of block ops — keeping per-tick work predictable.
 * Progress checkpoints persist to SQLite after every structure; on restart,
 * unbuilt structures resume automatically (see
 * {@link com.rpgworldgen.WorldGenerationManager#recoverUnfinished()}).
 */
public class GenerationTaskScheduler {

    public enum JobState { QUEUED, RUNNING, PAUSED, CANCELLED, COMPLETE, FAILED }

    public static class Job {
        public final String worldName;
        public final long seed;
        public final int worldSize;
        public final Deque<PlannedStructure> remaining = new ArrayDeque<>();
        /** Roads to stamp after structures exist (coarse waypoints; refined at build). */
        public final List<RoadTask> roads = new ArrayList<>();
        public volatile JobState state = JobState.QUEUED;
        public volatile int done;
        public volatile int total;
        public volatile int errors;
        public volatile String stage = "starting";
        public volatile String error;
        /** Wilderness scatter runs once after roads. */
        public volatile boolean wildernessDone;
        /** Retries for wilderness when chunks are still generating (bounded). */
        public volatile int wildernessRetries;

        Job(String worldName, long seed, int worldSize) {
            this.worldName = worldName; this.seed = seed; this.worldSize = worldSize;
        }
    }

    /** A road stamping task: endpoints + coarse waypoints + theme. */
    public static class RoadTask {
        public final int x0, z0, x1, z1;
        public final List<int[]> waypoints;
        public final String theme;
        public RoadTask(int x0, int z0, int x1, int z1, List<int[]> waypoints, String theme) {
            this.x0 = x0; this.z0 = z0; this.x1 = x1; this.z1 = z1;
            this.waypoints = waypoints; this.theme = theme;
        }
    }

    private final JavaPlugin plugin;
    private final Logger log;
    private final StructurePlacementEngine placement;
    private final BuildingEngine buildings;
    private final SettlementGenerator settlements;
    private final DungeonBuilder dungeons;
    private final WildernessGenerator wilderness;
    private final GenerationPersistenceService db;
    private final int blocksPerTick;
    private final int wildernessBudget;
    private final int errorHaltThreshold;
    private final int lootDrainPerTick;

    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private BukkitTask ticker;
    /** Bounded synchronous chunk generations per tick on runtimes without async API. */
    private final int syncChunkGenPerTick = 2;

    public GenerationTaskScheduler(JavaPlugin plugin, StructurePlacementEngine placement,
                                   GenerationPersistenceService db, int blocksPerTick) {
        this(plugin, placement, db, blocksPerTick, 40, 25, 4);
    }

    public GenerationTaskScheduler(JavaPlugin plugin, StructurePlacementEngine placement,
                                   GenerationPersistenceService db, int blocksPerTick,
                                   int wildernessBudget) {
        this(plugin, placement, db, blocksPerTick, wildernessBudget, 25, 4);
    }

    public GenerationTaskScheduler(JavaPlugin plugin, StructurePlacementEngine placement,
                                   GenerationPersistenceService db, int blocksPerTick,
                                   int wildernessBudget, int errorHaltThreshold, int lootDrainPerTick) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.placement = placement;
        this.buildings = new BuildingEngine(placement);
        this.settlements = new SettlementGenerator(buildings);
        this.dungeons = new DungeonBuilder(buildings, placement);
        this.wilderness = new WildernessGenerator(buildings, placement);
        this.db = db;
        this.blocksPerTick = blocksPerTick;
        this.wildernessBudget = wildernessBudget;
        this.errorHaltThreshold = errorHaltThreshold;
        this.lootDrainPerTick = lootDrainPerTick;
    }

    public void start() {
        if (ticker == null) {
            ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        }
    }

    public void stop() {
        if (ticker != null) { ticker.cancel(); ticker = null; }
    }

    public Job submit(String worldName, long seed, WorldPlanningEngine.Plan plan) {
        Job job = new Job(worldName, seed, plan.size);
        // Build order: spawn area first, then villages/cities/kingdoms, outposts, dungeons, landmarks.
        for (PlannedStructure.Kind k : new PlannedStructure.Kind[]{
            PlannedStructure.Kind.SPAWN, PlannedStructure.Kind.VILLAGE,
            PlannedStructure.Kind.CITY, PlannedStructure.Kind.KINGDOM_CAPITAL,
            PlannedStructure.Kind.KINGDOM_OUTPOST, PlannedStructure.Kind.DUNGEON,
            PlannedStructure.Kind.LANDMARK}) {
            for (PlannedStructure s : plan.byKind(k)) job.remaining.add(s);
        }
        enqueueRoads(job, plan);
        job.total = job.remaining.size();
        job.state = JobState.RUNNING;
        jobs.put(worldName, job);
        persistProgress(job);
        log.info("Generation job started for '" + worldName + "' (" + job.total + " structures, "
            + job.roads.size() + " roads).");
        return job;
    }

    private void enqueueRoads(Job job, WorldPlanningEngine.Plan plan) {
        Map<String, PlannedStructure> byId = new java.util.HashMap<>();
        for (PlannedStructure s : plan.structures) byId.put(s.getId(), s);
        for (com.rpgworldgen.model.PlannedRoad road : plan.roads) {
            PlannedStructure a = byId.get(road.getFromId());
            PlannedStructure b = byId.get(road.getToId());
            if (a == null || b == null) continue;
            List<int[]> wps = new ArrayList<>();
            for (int[] w : road.getWaypoints()) wps.add(new int[]{w[0], w[1]});
            job.roads.add(new RoadTask(a.getX(), a.getZ(), b.getX(), b.getZ(), wps, a.getTheme()));
        }
    }

    /** Requeue unbuilt structures after a restart (recovery path). */
    public Job resumeUnbuilt(String worldName, long seed, int worldSize,
                             java.util.List<PlannedStructure> unbuilt,
                             java.util.List<RoadTask> roads) {
        Job job = new Job(worldName, seed, worldSize);
        job.remaining.addAll(unbuilt);
        if (roads != null) job.roads.addAll(roads);
        job.done = 0;
        try {
            job.total = db.countStructures(worldName);
        } catch (SQLException e) {
            job.total = unbuilt.size();
        }
        try {
            job.done = db.countBuilt(worldName);
        } catch (SQLException e) {
            log.warning("Could not read built count: " + e.getMessage());
        }
        job.state = JobState.RUNNING;
        job.stage = "resuming";
        jobs.put(worldName, job);
        return job;
    }

    /** Backwards-compatible overload (no persisted roads). */
    public Job resumeUnbuilt(String worldName, long seed, java.util.List<PlannedStructure> unbuilt) {
        return resumeUnbuilt(worldName, seed, 6000, unbuilt, null);
    }

    /** Queue a targeted rebuild of already-planned structures (regenerate path). */
    public Job rebuild(String worldName, long seed, int worldSize,
                       java.util.List<PlannedStructure> targets) {
        if (targets.isEmpty()) return null;
        Job job = new Job(worldName, seed, worldSize);
        job.remaining.addAll(targets);
        job.total = targets.size();
        job.wildernessDone = true; // regenerate touches structures only, not wilderness
        job.state = JobState.RUNNING;
        jobs.put(worldName, job);
        persistProgress(job);
        log.info("Regeneration queued for '" + worldName + "' (" + targets.size() + " structures).");
        return job;
    }

    public Job getJob(String world) { return jobs.get(world); }

    public boolean pause(String world) {
        Job j = jobs.get(world);
        if (j == null || j.state != JobState.RUNNING) return false;
        j.state = JobState.PAUSED;
        persistProgress(j);
        return true;
    }

    public boolean resume(String world) {
        Job j = jobs.get(world);
        if (j == null || j.state != JobState.PAUSED) return false;
        j.state = JobState.RUNNING;
        persistProgress(j);
        return true;
    }

    public boolean cancel(String world) {
        Job j = jobs.get(world);
        if (j == null) return false;
        j.state = JobState.CANCELLED;
        j.remaining.clear();
        persistProgress(j);
        return true;
    }

    private void tick() {
        for (Job job : jobs.values()) {
            if (job.state != JobState.RUNNING) continue;
            World world = Bukkit.getWorld(job.worldName);
            if (world == null) {
                job.stage = "waiting-for-world";
                continue;
            }
            try {
                PlannedStructure next = job.remaining.poll();
                if (next == null) {
                    // Phase 2: stamp roads (one per tick, each drains a bounded budget).
                    if (!job.roads.isEmpty()) {
                        RoadTask road = job.roads.remove(0);
                        if (!preTouchRoad(world, road)) {
                            job.roads.add(0, road);
                            job.stage = "waiting-for-chunks (roads)";
                            placement.drain(world, blocksPerTick);
                            persistProgress(job);
                            continue;
                        }
                        try {
                            job.stage = "roads (" + job.roads.size() + " left)";
                            stampRoad(world, job, road);
                        } catch (ChunkNotReadyException cnr) {
                            job.roads.add(0, road);
                            job.stage = "waiting-for-chunks (roads)";
                            syncFallback(world, cnr);
                            placement.drain(world, blocksPerTick);
                            persistProgress(job);
                            continue;
                        }
                        placement.drain(world, blocksPerTick);
                        persistProgress(job);
                        continue;
                    }
                    // Phase 3: wilderness scatter (budgeted, retried until chunks ready).
                    if (!job.wildernessDone) {
                        job.stage = "wilderness";
                        int n = wilderness.decorate(world, job.seed, job.worldSize, wildernessBudget);
                        placement.drain(world, blocksPerTick);
                        // Fresh worlds have mostly-ungenerated chunks: decorate touches
                        // and skips them, so a near-empty pass means "chunks not ready",
                        // not "wilderness finished". Retry on later ticks (bounded).
                        if (n < Math.max(1, wildernessBudget / 2)) {
                            job.wildernessRetries++;
                            if (job.wildernessRetries < 15) {
                                job.stage = "waiting-for-chunks (wilderness)";
                                persistProgress(job);
                                continue;
                            }
                        }
                        job.wildernessDone = true;
                        log.info("Wilderness scatter for '" + job.worldName + "': " + n + " features.");
                        persistProgress(job);
                        continue;
                    }
                    // Phase 4: drain remaining block ops + loot, then complete.
                    if (placement.queueSize() == 0) {
                        int lootDone = dungeons.drainLoot(Bukkit.getServer(), lootDrainPerTick);
                        if (!dungeons.getPendingLoot().isEmpty()) {
                            job.stage = "waiting-for-chunks (loot)";
                            persistProgress(job);
                            continue;
                        }
                        job.state = JobState.COMPLETE;
                        job.stage = "complete";
                        persistProgress(job);
                        ChunkPreloader.releaseAll();
                        try {
                            db.updateWorldState(job.worldName, "COMPLETE");
                        } catch (SQLException e) {
                            log.warning("State update failed: " + e.getMessage());
                        }
                        log.info("Generation complete for '" + job.worldName + "' (" + job.done
                            + "/" + job.total + ", errors=" + job.errors + ").");
                    } else {
                        job.stage = "draining (" + placement.queueSize() + " blocks left)";
                        placement.drain(world, blocksPerTick);
                    }
                    continue;
                }
                // Footprint pre-touch: never start a structure whose chunks are not
                // loaded — queue background generation and retry next tick instead
                // of half-enqueueing then throwing mid-build.
                if (!preTouchStructure(world, next)) {
                    job.remaining.addFirst(next);
                    job.stage = "waiting-for-chunks";
                    placement.drain(world, blocksPerTick);
                    persistProgress(job);
                    continue;
                }
                job.stage = "building " + next.getKind() + " " + next.getName()
                    + " (" + (job.done + 1) + "/" + job.total + ")";
                try {
                    buildOne(world, job, next);
                } catch (ChunkNotReadyException cnr) {
                    // Race: chunk unloaded between pre-touch and build. Requeue;
                    // partial enqueues are deterministic and idempotent on retry.
                    job.remaining.addFirst(next);
                    job.stage = "waiting-for-chunks";
                    syncFallback(world, cnr);
                    placement.drain(world, blocksPerTick);
                    persistProgress(job);
                    continue;
                }
                job.done++;
                try {
                    db.markBuilt(next.getId());
                } catch (SQLException e) {
                    log.warning("markBuilt failed: " + e.getMessage());
                }
                placement.drain(world, blocksPerTick);
                persistProgress(job);
            } catch (Exception e) {
                job.errors++;
                log.severe("Error building structure in '" + job.worldName + "': " + e.getMessage());
                persistProgress(job);
                if (job.errors > errorHaltThreshold) {
                    job.state = JobState.FAILED;
                    job.error = "Too many errors (" + job.errors + "); job halted. Fix and resume.";
                    try {
                        db.updateWorldState(job.worldName, "FAILED");
                    } catch (SQLException se) {
                        log.warning("State update failed: " + se.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Pre-touch all chunks a structure will read, but gate only on the core
     * (center chunk + immediate ring). Gating on the full footprint deadlocks
     * on fresh worlds: background generation of ~80+ chunks never converges
     * while the job waits. Outer-column misses still defer safely via
     * {@link ChunkNotReadyException} mid-build (requeue + retry).
     */
    private boolean preTouchStructure(World world, PlannedStructure s) {
        int radiusBlocks = s.getRadius();
        // Dungeons spread rooms on a grid (spacing 14, up to ~6 steps out) plus
        // room half-size: footprint can reach ~100 blocks from center.
        if (s.getKind() == PlannedStructure.Kind.DUNGEON) radiusBlocks = Math.max(radiusBlocks, 100);
        // Capitals build outlying towers (~85 blocks out); kingdoms need a wider touch.
        if (s.getKind() == PlannedStructure.Kind.KINGDOM_CAPITAL) radiusBlocks = Math.max(radiusBlocks, 110);
        if (s.getKind() == PlannedStructure.Kind.CITY) radiusBlocks = Math.max(radiusBlocks, 60);
        int radiusChunks = (radiusBlocks + 16) / 16 + 1;
        // Request background generation for the whole footprint (fire-and-forget,
        // deduplicated by the chunk system), but only block on the core.
        requestAll(world, ChunkPreloader.chunksForCircle(s.getX() >> 4, s.getZ() >> 4, radiusChunks));
        return coreReady(world, s.getX() >> 4, s.getZ() >> 4);
    }

    /** Pre-touch the coarse road footprint. The refining sampler defers on
     * unready chunks itself, so roads never gate — just request and proceed. */
    private boolean preTouchRoad(World world, RoadTask road) {
        List<int[]> base = road.waypoints.size() >= 2 ? road.waypoints
            : java.util.Arrays.asList(new int[]{road.x0, road.z0}, new int[]{road.x1, road.z1});
        requestAll(world, ChunkPreloader.chunksForPath(base, 1));
        return true;
    }

    /** Core gate: center chunk + immediate 3x3 ring loaded. */
    private boolean coreReady(World world, int ccx, int ccz) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!world.isChunkLoaded(ccx + dx, ccz + dz)) return false;
            }
        }
        return true;
    }

    /**
     * Queue background generation for every unloaded chunk in the set.
     * Fire-and-forget futures are deduplicated by the chunk system, so no cap
     * is needed on async runtimes; the Spigot path stays bounded via
     * {@link #syncChunkGenPerTick}.
     */
    private void requestAll(World world, List<int[]> chunks) {
        boolean async = ChunkPreloader.hasAsync(world);
        int syncUsed = 0;
        for (int[] c : chunks) {
            if (world.isChunkLoaded(c[0], c[1])) continue;
            if (async) {
                ChunkPreloader.touch(world, c[0], c[1]);
            } else if (syncUsed < syncChunkGenPerTick) {
                ChunkPreloader.generateSync(world, c[0], c[1]);
                syncUsed++;
            } else {
                return; // budget spent; remaining chunks requested next tick
            }
        }
    }

    /** Bounded synchronous generation fallback for Spigot (no async chunk API). */
    private void syncFallback(World world, ChunkNotReadyException cnr) {
        if (!ChunkPreloader.hasAsync(world)) {
            try {
                ChunkPreloader.generateSync(world, cnr.getChunkX(), cnr.getChunkZ());
            } catch (Exception ignored) {
                // Best effort; retry next tick.
            }
        }
    }

    /** Refine a road's coarse waypoints with terrain-aware routing, then stamp it. */
    private void stampRoad(World world, Job job, RoadTask road) {
        com.rpgworldgen.gen.RoadRouter router = new com.rpgworldgen.gen.RoadRouter();
        // Chunk-safe sampler: queue background gen and fail fast via
        // ChunkNotReadyException instead of stalling the tick on sync gen.
        com.rpgworldgen.gen.RoadRouter.HeightSampler sampler =
            new com.rpgworldgen.gen.RoadRouter.HeightSampler() {
                @Override public int heightAt(int x, int z) {
                    return BuildingEngine.groundY(world, x, z);
                }
                @Override public boolean isWater(int x, int z) {
                    int cx = x >> 4, cz = z >> 4;
                    if (!world.isChunkLoaded(cx, cz)) {
                        ChunkPreloader.touch(world, cx, cz);
                        throw new ChunkNotReadyException(cx, cz);
                    }
                    return world.getHighestBlockAt(x, z).isLiquid();
                }
            };
        List<int[]> refined = new ArrayList<>();
        List<int[]> legs = road.waypoints.size() >= 2 ? road.waypoints
            : java.util.Arrays.asList(new int[]{road.x0, road.z0}, new int[]{road.x1, road.z1});
        for (int i = 1; i < legs.size(); i++) {
            int[] p = legs.get(i - 1), q = legs.get(i);
            List<int[]> leg = router.route(sampler, p[0], p[1], q[0], q[1], 40);
            if (!refined.isEmpty() && !leg.isEmpty()) leg.remove(0);
            refined.addAll(leg);
        }
        if (refined.size() >= 2) {
            buildings.buildRoad(world, refined, road.theme == null ? "plains" : road.theme);
        }
    }

    private void buildOne(World world, Job job, PlannedStructure s) {
        switch (s.getKind()) {
            case SPAWN:
                // Beginner spawn: plaza, well, 2 houses, notice torches, safe-zone applied by manager.
                buildings.buildWell(world, s.getX(), s.getZ(), "plains");
                buildings.buildHouse(world, s.getX() + 14, s.getZ(), "plains",
                    new java.util.Random(job.seed ^ 0x5FA7L));
                break;
            case VILLAGE:
                settlements.buildVillage(world, s, job.seed);
                break;
            case CITY:
                settlements.buildCity(world, s, job.seed, s.getTier() >= 3);
                break;
            case KINGDOM_CAPITAL:
                settlements.buildKingdom(world, s, job.seed);
                break;
            case KINGDOM_OUTPOST:
                buildings.buildTower(world, s.getX(), s.getZ(), s.getTheme(), 8,
                    new java.util.Random(job.seed ^ s.getId().hashCode()));
                break;
            case DUNGEON:
                dungeons.buildDungeon(world, s, job.seed);
                break;
            case LANDMARK:
                wilderness.buildLandmark(world, s, job.seed);
                break;
            default:
                throw new IllegalArgumentException("Unknown structure kind: " + s.getKind());
        }
    }

    private void persistProgress(Job job) {
        try {
            db.saveProgress(job.worldName, job.stage, job.done, job.total, job.errors);
        } catch (SQLException e) {
            log.warning("Progress checkpoint failed: " + e.getMessage());
        }
    }
}
