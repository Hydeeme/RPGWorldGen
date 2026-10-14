package com.rpgworldgen.model;

/**
 * Built-in world-size presets.
 *
 * <p>Coordinate convention: worlds are centered on block (0, 0). A world of
 * size S extends from -S/2 to +S/2 on both the X and Z axes. The world border
 * is configured to match the selected preset.
 */
public enum WorldPreset {
    SMALL(5000, 6, 2, 1, 10, 600, 900, 1400),
    MEDIUM(6000, 9, 3, 2, 14, 550, 850, 1300),
    LARGE(8000, 14, 5, 3, 20, 500, 800, 1200);

    /** Hard validated limits for custom sizes (blocks per side). */
    public static final int MIN_CUSTOM_SIZE = 2000;
    public static final int MAX_CUSTOM_SIZE = 12000;

    private final int size;
    private final int villages;
    private final int cities;
    private final int kingdoms;
    private final int dungeons;
    private final int villageSpacing;
    private final int citySpacing;
    private final int kingdomSpacing;

    WorldPreset(int size, int villages, int cities, int kingdoms, int dungeons,
                int villageSpacing, int citySpacing, int kingdomSpacing) {
        this.size = size;
        this.villages = villages;
        this.cities = cities;
        this.kingdoms = kingdoms;
        this.dungeons = dungeons;
        this.villageSpacing = villageSpacing;
        this.citySpacing = citySpacing;
        this.kingdomSpacing = kingdomSpacing;
    }

    public int getSize() { return size; }
    /** Half-extent: playable coordinates run from -halfExtent to +halfExtent. */
    public int getHalfExtent() { return size / 2; }
    public int getDefaultVillages() { return villages; }
    public int getDefaultCities() { return cities; }
    public int getDefaultKingdoms() { return kingdoms; }
    public int getDefaultDungeons() { return dungeons; }
    public int getVillageSpacing() { return villageSpacing; }
    public int getCitySpacing() { return citySpacing; }
    public int getKingdomSpacing() { return kingdoms > 0 ? kingdomSpacing : 0; }

    /** Resolve a preset from an exact size, or null if no preset matches. */
    public static WorldPreset fromSize(int size) {
        for (WorldPreset p : values()) {
            if (p.size == size) return p;
        }
        return null;
    }

    /** Validate a world size (preset or custom). Throws IllegalArgumentException if invalid. */
    public static int validateSize(int size) {
        if (size < MIN_CUSTOM_SIZE || size > MAX_CUSTOM_SIZE) {
            throw new IllegalArgumentException(
                "World size must be between " + MIN_CUSTOM_SIZE + " and " + MAX_CUSTOM_SIZE
                + " blocks (presets: 5000, 6000, 8000). Got: " + size);
        }
        if (size % 100 != 0) {
            throw new IllegalArgumentException("World size must be a multiple of 100. Got: " + size);
        }
        return size;
    }
}
