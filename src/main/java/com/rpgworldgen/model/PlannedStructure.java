package com.rpgworldgen.model;

import java.util.Objects;

/** A single planned structure placement. Pure data object (no Bukkit dependency). */
public class PlannedStructure {
    public enum Kind { VILLAGE, CITY, KINGDOM_CAPITAL, KINGDOM_OUTPOST, DUNGEON, LANDMARK, SPAWN }

    private final String id;
    private final Kind kind;
    private final String name;
    private final int x;
    private final int z;
    private final int radius;
    private final int tier;
    private final String theme;
    private final String extra;

    public PlannedStructure(String id, Kind kind, String name, int x, int z,
                            int radius, int tier, String theme, String extra) {
        this.id = Objects.requireNonNull(id);
        this.kind = Objects.requireNonNull(kind);
        this.name = Objects.requireNonNull(name);
        this.x = x;
        this.z = z;
        this.radius = radius;
        this.tier = tier;
        this.theme = theme == null ? "" : theme;
        this.extra = extra == null ? "" : extra;
    }

    public String getId() { return id; }
    public Kind getKind() { return kind; }
    public String getName() { return name; }
    public int getX() { return x; }
    public int getZ() { return z; }
    public int getRadius() { return radius; }
    public int getTier() { return tier; }
    public String getTheme() { return theme; }
    public String getExtra() { return extra; }

    /** Overlap test between two structures' protected circles, plus required gap. */
    public boolean overlaps(PlannedStructure other, int extraGap) {
        long minDist = (long) radius + other.radius + extraGap;
        return CoordUtilDist.distanceSq(x, z, other.x, other.z) < minDist * minDist;
    }

    /** Local scratch copy of distance to avoid cross-package util import cycles in tests. */
    static final class CoordUtilDist {
        static long distanceSq(int x1, int z1, int x2, int z2) {
            long dx = (long) x1 - x2, dz = (long) z1 - z2;
            return dx * dx + dz * dz;
        }
    }
}
