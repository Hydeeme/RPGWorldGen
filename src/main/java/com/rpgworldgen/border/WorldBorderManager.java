package com.rpgworldgen.border;

import org.bukkit.World;

/**
 * Owns world-border setup for RPG worlds.
 *
 * <p>Coordinate convention: worlds are centered on block (0, 0). A world of size S
 * extends from -S/2 to +S/2 on both the X and Z axes, and the border is configured
 * to match exactly that playable area.
 */
public class WorldBorderManager {

    /** Half-extent of the playable square for a world of the given size. */
    public static int halfExtent(int size) {
        return size / 2;
    }

    /** Border diameter Bukkit expects for a world of the given size. */
    public static double borderSize(int size) {
        return size;
    }

    /** True if (x, z) is inside the playable square including the edge. */
    public static boolean inBounds(int x, int z, int size) {
        int h = halfExtent(size);
        return x >= -h && x <= h && z >= -h && z <= h;
    }

    /** Apply the border to a live world. Must be called on the server thread. */
    public void applyBorder(World world, int size) {
        world.getWorldBorder().setCenter(0, 0);
        world.getWorldBorder().setSize(borderSize(size));
    }
}
