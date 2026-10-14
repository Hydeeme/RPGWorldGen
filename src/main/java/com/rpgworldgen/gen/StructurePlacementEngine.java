package com.rpgworldgen.gen;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Bounded, chunk-aware block placement engine.
 *
 * <p>Work model: generation tasks enqueue {@link BlockOp} entries into a bounded
 * queue. A repeating synchronous Bukkit task drains up to N ops per tick
 * (configurable generation budget), so large worlds never freeze the server.
 * All world/block access happens on the server thread.
 */
public class StructurePlacementEngine {

    public static class BlockOp {
        public final int x, y, z;
        public final Material material;
        public BlockOp(int x, int y, int z, Material material) {
            this.x = x; this.y = y; this.z = z; this.material = material;
        }
    }

    private final Deque<BlockOp> queue = new ArrayDeque<>();
    private final int maxQueueSize;
    private int droppedOps;

    // Palette cache per theme to avoid repeated lookups.
    private final Map<String, Material[]> paletteCache = new HashMap<>();

    public StructurePlacementEngine(int maxQueueSize) {
        this.maxQueueSize = maxQueueSize;
    }

    /** Enqueue a block write. Returns false (and counts a drop) if the queue is full
     * or the material is not a placeable block (never throws — bad palette entries
     * must not fail a whole structure). */
    public synchronized boolean enqueue(int x, int y, int z, Material material) {
        if (material == null || !material.isBlock()) {
            droppedOps++;
            return false;
        }
        if (queue.size() >= maxQueueSize) { droppedOps++; return false; }
        queue.add(new BlockOp(x, y, z, material));
        return true;
    }

    public synchronized int queueSize() { return queue.size(); }
    public synchronized int getDroppedOps() { return droppedOps; }
    public synchronized void clear() { queue.clear(); }

    /**
     * Drain up to {@code budget} ops into the world. Must be called on the
     * server thread. Returns number of blocks actually placed.
     *
     * <p>Watchdog-safe: ops in unloaded chunks are NOT generated synchronously
     * (which stalls the tick on fresh worlds). Instead background generation is
     * queued and the op is deferred back to the queue front for a later tick.
     * Async requests are de-duplicated per drain call so a large queue in one
     * ungenerated area costs one background request per chunk, not per op.
     */
    public int drain(World world, int budget) {
        int placed = 0;
        java.util.List<BlockOp> deferred = null;
        java.util.Set<String> requested = null;
        for (int i = 0; i < budget; i++) {
            BlockOp op;
            synchronized (this) {
                op = queue.poll();
            }
            if (op == null) break;
            int cx = op.x >> 4, cz = op.z >> 4;
            if (!world.isChunkLoaded(cx, cz)) {
                String key = cx + "," + cz;
                if (requested == null) requested = new java.util.HashSet<>();
                if (requested.add(key)) {
                    com.rpgworldgen.gen.ChunkPreloader.touch(world, cx, cz);
                }
                if (deferred == null) deferred = new java.util.ArrayList<>();
                deferred.add(op);
                continue;
            }
            Block b = world.getBlockAt(op.x, op.y, op.z);
            if (b.getType() != op.material) {
                b.setType(op.material, false);
                placed++;
            }
        }
        if (deferred != null && !deferred.isEmpty()) {
            synchronized (this) {
                for (int i = deferred.size() - 1; i >= 0; i--) {
                    queue.addFirst(deferred.get(i));
                }
            }
        }
        return placed;
    }

    /** Themed block palettes: [wall, floor, roof, trim, road]. */
    public Material[] palette(String theme) {
        return paletteCache.computeIfAbsent(theme == null ? "plains" : theme.toLowerCase(), t -> {
            switch (t) {
                case "desert": return new Material[]{Material.SANDSTONE, Material.SMOOTH_SANDSTONE,
                    Material.CUT_SANDSTONE, Material.CHISELED_SANDSTONE, Material.SANDSTONE};
                case "snowy": return new Material[]{Material.SPRUCE_PLANKS, Material.SPRUCE_PLANKS,
                    Material.SPRUCE_STAIRS, Material.COBBLESTONE, Material.GRAVEL};
                case "forest":
                case "elven": return new Material[]{Material.OAK_LOG, Material.OAK_PLANKS,
                    Material.OAK_STAIRS, Material.MOSSY_COBBLESTONE, Material.DIRT_PATH};
                case "dwarven": return new Material[]{Material.STONE_BRICKS, Material.POLISHED_ANDESITE,
                    Material.COBBLESTONE_STAIRS, Material.CHISELED_STONE_BRICKS, Material.COBBLESTONE};
                case "corrupted": return new Material[]{Material.BLACKSTONE, Material.POLISHED_BLACKSTONE,
                    Material.POLISHED_BLACKSTONE_BRICK_STAIRS, Material.CRACKED_DEEPSLATE_BRICKS,
                    Material.SOUL_SAND};
                case "rustic": return new Material[]{Material.COBBLESTONE, Material.DIRT,
                    Material.OAK_STAIRS, Material.OAK_LOG, Material.GRAVEL};
                default: return new Material[]{Material.OAK_PLANKS, Material.COBBLESTONE,
                    Material.OAK_STAIRS, Material.OAK_LOG, Material.GRAVEL};
            }
        });
    }
}
