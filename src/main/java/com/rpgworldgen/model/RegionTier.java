package com.rpgworldgen.model;

/**
 * RPG progression tiers. Configurable design tiers (see regions.yml);
 * defaults here are fallback values used when config is absent.
 */
public enum RegionTier {
    TIER_1(1, "Beginner Lands", 1, 1, 1),
    TIER_2(2, "Frontier", 5, 2, 2),
    TIER_3(3, "Heartlands", 10, 3, 3),
    TIER_4(4, "Hostile Territories", 20, 4, 4),
    TIER_5(5, "Endgame Realm", 30, 5, 5);

    private final int level;
    private final String defaultName;
    private final int recommendedLevel;
    private final int difficulty;
    private final int lootTier;

    RegionTier(int level, String defaultName, int recommendedLevel, int difficulty, int lootTier) {
        this.level = level;
        this.defaultName = defaultName;
        this.recommendedLevel = recommendedLevel;
        this.difficulty = difficulty;
        this.lootTier = lootTier;
    }

    public int getLevel() { return level; }
    public String getDefaultName() { return defaultName; }
    public int getRecommendedLevel() { return recommendedLevel; }
    public int getDifficulty() { return difficulty; }
    public int getLootTier() { return lootTier; }

    public static RegionTier of(int level) {
        for (RegionTier t : values()) {
            if (t.level == level) return t;
        }
        throw new IllegalArgumentException("Unknown tier: " + level);
    }
}
