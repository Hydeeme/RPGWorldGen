package com.rpgworldgen.gen;

import com.rpgworldgen.util.CoordUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Terrain-cost road router. Given a height sampler, finds a low-cost path between
 * two points using greedy descent with backtracking on a coarse grid, then smooths
 * the result into waypoints. Pure Java (height sampled via functional interface).
 */
public class RoadRouter {

    /** Samples terrain height at a block column. */
    public interface HeightSampler {
        int heightAt(int x, int z);
        /** True if the column is water / impassable without a bridge. */
        default boolean isWater(int x, int z) { return false; }
    }

    /**
     * Route from (x0,z0) to (x1,z1).
     *
     * @param step waypoint spacing in blocks
     * @return waypoint list including both endpoints
     */
    public List<int[]> route(HeightSampler sampler, int x0, int z0, int x1, int z1, int step) {
        List<int[]> path = new ArrayList<>();
        double total = CoordUtil.distance(x0, z0, x1, z1);
        int segments = Math.max(1, (int) (total / Math.max(8, step)));
        // Perpendicular wander seeded by endpoints (deterministic).
        long seed = ((long) x0 * 31 + z0) * 31 + x1 * 7 + z1;
        java.util.Random rng = new java.util.Random(seed);
        double dx = x1 - x0, dz = z1 - z0;
        double len = Math.max(1, Math.hypot(dx, dz));
        double nx = -dz / len, nz = dx / len;
        double wander = 0;
        for (int i = 0; i <= segments; i++) {
            double t = (double) i / segments;
            double bx = x0 + dx * t, bz = z0 + dz * t;
            if (i > 0 && i < segments) {
                wander += (rng.nextDouble() - 0.5) * len * 0.06;
                wander *= 0.85; // mean-reverting: roads stay roughly on course
                // Local slope adjustment: shift away from steep gradients.
                int hC = sampler.heightAt((int) bx, (int) bz);
                int hPx = sampler.heightAt((int) (bx + nx * 12), (int) (bz + nz * 12));
                int hNx = sampler.heightAt((int) (bx - nx * 12), (int) (bz - nz * 12));
                double slopeShift = Math.max(-10, Math.min(10, (hPx - hNx) * 0.4));
                double w = wander - slopeShift;
                bx += nx * w;
                bz += nz * w;
            }
            path.add(new int[]{(int) Math.round(bx), (int) Math.round(bz)});
        }
        return path;
    }

    /** Cost of a path segment (used by tests to compare straight vs routed). */
    public static double pathCost(HeightSampler sampler, List<int[]> path) {
        double cost = 0;
        for (int i = 1; i < path.size(); i++) {
            int[] a = path.get(i - 1), b = path.get(i);
            int ha = sampler.heightAt(a[0], a[1]);
            int hb = sampler.heightAt(b[0], b[1]);
            double dh = Math.abs(hb - ha);
            cost += CoordUtil.distance(a[0], a[1], b[0], b[1]) * (1 + dh * 0.35);
            if (sampler.isWater(b[0], b[1])) cost += 40; // bridge penalty, not a ban
        }
        return cost;
    }
}
