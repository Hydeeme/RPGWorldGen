package com.rpgworldgen.util;

/**
 * Coordinate and border math. Pure Java (no Bukkit dependency) so it is unit-testable.
 * Convention: world centered at (0,0); playable area is [-half, +half] on X and Z.
 */
public final class CoordUtil {
    private CoordUtil() {}

    public static int halfExtent(int size) { return size / 2; }

    /** True if (x,z) is inside the playable square including the edge. */
    public static boolean inBounds(int x, int z, int size) {
        int h = halfExtent(size);
        return x >= -h && x <= h && z >= -h && z <= h;
    }

    /** True if a circle of given radius centered at (x,z) fits fully inside the border. */
    public static boolean fitsInside(int x, int z, int radius, int size) {
        int h = halfExtent(size);
        return x - radius >= -h && x + radius <= h && z - radius >= -h && z + radius <= h;
    }

    /** Clamp a center coordinate so a circle of radius r stays inside the border. */
    public static int clampCenter(int c, int radius, int size) {
        int h = halfExtent(size) - radius;
        if (h < 0) return 0;
        return Math.max(-h, Math.min(h, c));
    }

    public static double distance(int x1, int z1, int x2, int z2) {
        long dx = (long) x1 - x2, dz = (long) z1 - z2;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Squared distance (avoids sqrt in hot loops). */
    public static long distanceSq(int x1, int z1, int x2, int z2) {
        long dx = (long) x1 - x2, dz = (long) z1 - z2;
        return dx * dx + dz * dz;
    }
}
