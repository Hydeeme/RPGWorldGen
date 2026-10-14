package com.rpgworldgen.plan;

import org.bukkit.World;
import org.bukkit.block.Biome;

/**
 * Samples underlying terrain to score candidate structure locations.
 *
 * <p>All Bukkit access happens on the calling (server) thread. The pure scoring
 * helper {@link #score(int, boolean, boolean, double)} is unit-testable without a server.
 */
public class TerrainAnalysisService {

    public static class Sample {
        public final int height;
        public final boolean water;
        public final boolean oceanBiome;
        public final double slope;

        public Sample(int height, boolean water, boolean oceanBiome, double slope) {
            this.height = height;
            this.water = water;
            this.oceanBiome = oceanBiome;
            this.slope = slope;
        }
    }

    /** Sample a column on the server thread. Throws {@link ChunkNotReadyException}
     * instead of blocking when the chunk is not loaded (see ChunkPreloader). */
    public Sample sample(World world, int x, int z) {
        int cx = x >> 4, cz = z >> 4;
        if (!world.isChunkLoaded(cx, cz)) {
            com.rpgworldgen.gen.ChunkPreloader.touch(world, cx, cz);
            throw new com.rpgworldgen.gen.ChunkNotReadyException(cx, cz);
        }
        int h = world.getHighestBlockYAt(x, z);
        boolean water = world.getHighestBlockAt(x, z).isLiquid();
        Biome biome = world.getBiome(x, h, z);
        boolean ocean = biome.name().contains("OCEAN") || biome.name().contains("RIVER")
            || biome.name().contains("BEACH") && water;
        int hx;
        int hz;
        int hxcx = (x + 8) >> 4, hxcz = z >> 4;
        int hzcx = x >> 4, hzcz = (z + 8) >> 4;
        if (!world.isChunkLoaded(hxcx, hxcz)) {
            com.rpgworldgen.gen.ChunkPreloader.touch(world, hxcx, hxcz);
            throw new com.rpgworldgen.gen.ChunkNotReadyException(hxcx, hxcz);
        }
        if (!world.isChunkLoaded(hzcx, hzcz)) {
            com.rpgworldgen.gen.ChunkPreloader.touch(world, hzcx, hzcz);
            throw new com.rpgworldgen.gen.ChunkNotReadyException(hzcx, hzcz);
        }
        hx = world.getHighestBlockYAt(x + 8, z);
        hz = world.getHighestBlockYAt(x, z + 8);
        double slope = (Math.abs(hx - h) + Math.abs(hz - h)) / 2.0;
        return new Sample(h, water, ocean, slope);
    }

    /**
     * Score 0..100 (higher = more buildable).
     *
     * @param water      column is liquid
     * @param oceanBiome biome is ocean/river class
     * @param slope      average height delta over 8 blocks
     */
    public static int score(boolean water, boolean oceanBiome, double slope) {
        if (water || oceanBiome) return 0;
        if (slope > 8) return 5;
        if (slope > 5) return 40;
        if (slope > 3) return 70;
        return 95;
    }

    /** True if the site is acceptable for a settlement of the given radius. */
    public boolean suitable(World world, int x, int z, int radius) {
        Sample s = sample(world, x, z);
        if (s.water || s.oceanBiome) return false;
        // Larger footprints demand flatter ground.
        double maxSlope = radius > 80 ? 3.0 : radius > 50 ? 5.0 : 8.0;
        return s.slope <= maxSlope;
    }
}
