package com.rpgworldgen.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Loads, validates, and serves all plugin YAML configuration.
 * Invalid values are never silently ignored: problems are logged as warnings
 * and safe defaults are substituted.
 */
public class ConfigurationManager {

    private static final String[] CONFIG_FILES = {
        "config.yml", "worlds.yml", "structures.yml", "settlements.yml",
        "dungeons.yml", "regions.yml", "safezones.yml", "messages.yml"
    };

    private final JavaPlugin plugin;
    private final Logger log;
    private final List<String> warnings = new ArrayList<>();

    private FileConfiguration config;
    private FileConfiguration worlds;
    private FileConfiguration structures;
    private FileConfiguration settlements;
    private FileConfiguration dungeons;
    private FileConfiguration regions;
    private FileConfiguration safezones;
    private FileConfiguration messages;

    public ConfigurationManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    /** (Re)load all configs from disk, creating defaults for missing files. */
    public void loadAll() {
        warnings.clear();
        for (String name : CONFIG_FILES) {
            ensureDefault(name);
        }
        config = load("config.yml");
        worlds = load("worlds.yml");
        structures = load("structures.yml");
        settlements = load("settlements.yml");
        dungeons = load("dungeons.yml");
        regions = load("regions.yml");
        safezones = load("safezones.yml");
        messages = load("messages.yml");
        validate();
        for (String w : warnings) {
            log.warning("[config] " + w);
        }
        log.info("Configuration loaded (" + warnings.size() + " warning(s)).");
    }

    private void ensureDefault(String name) {
        File f = new File(plugin.getDataFolder(), name);
        if (!f.exists()) {
            plugin.saveResource(name, false);
        }
    }

    private FileConfiguration load(String name) {
        return YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), name));
    }

    // ---- validation ----

    private void validate() {
        checkRange("generation.blocks-per-tick", config.getInt("generation.blocks-per-tick", 2000), 100, 50000);
        checkRange("generation.max-queue-size", config.getInt("generation.max-queue-size", 50000), 1000, 1000000);
        checkRange("worlds.default-size", config.getInt("worlds.default-size", 6000), 2000, 12000);
        for (int t = 1; t <= 5; t++) {
            String base = "tiers.tier" + t + ".";
            if (regions.get(base + "recommended-level", null) == null) {
                warn("regions.yml missing '" + base + "recommended-level'; using tier default.");
            }
        }
        if (dungeons.getInt("dungeons.count-medium", 14) < 0) {
            warn("dungeons.yml count-medium is negative; using 0.");
        }
    }

    private void checkRange(String key, int value, int min, int max) {
        if (value < min || value > max) {
            warn("'" + key + "' out of range [" + min + "," + max + "]: " + value + "; using clamped value.");
        }
    }

    private void warn(String msg) { warnings.add(msg); }

    public List<String> getWarnings() { return new ArrayList<>(warnings); }

    // ---- accessors ----

    public FileConfiguration getConfig() { return config; }
    public FileConfiguration getWorlds() { return worlds; }
    public FileConfiguration getStructures() { return structures; }
    public FileConfiguration getSettlements() { return settlements; }
    public FileConfiguration getDungeons() { return dungeons; }
    public FileConfiguration getRegions() { return regions; }
    public FileConfiguration getSafezones() { return safezones; }

    public String msg(String key, String def) {
        String v = messages.getString(key, def);
        return v == null ? def : v.replace('&', '\u00A7');
    }

    public int getBlocksPerTick() {
        return clamp(config.getInt("generation.blocks-per-tick", 2000), 100, 50000);
    }

    public int getMaxQueueSize() {
        return clamp(config.getInt("generation.max-queue-size", 50000), 1000, 1000000);
    }

    public int getDefaultSize() {
        try {
            return com.rpgworldgen.model.WorldPreset.validateSize(
                config.getInt("worlds.default-size", 6000));
        } catch (IllegalArgumentException e) {
            warnings.add(e.getMessage() + " Using 6000.");
            return 6000;
        }
    }

    public long getDefaultSeed() {
        return config.getLong("worlds.default-seed", 0L);
    }

    public int getVillageSpacing() { return settlements.getInt("villages.min-spacing", 550); }
    public int getCitySpacing() { return settlements.getInt("cities.min-spacing", 850); }
    public int getKingdomSpacing() { return settlements.getInt("kingdoms.min-spacing", 1300); }
    public int getDungeonSpacing() { return dungeons.getInt("dungeons.min-spacing", 300); }

    public boolean preferWorldGuard() {
        return config.getBoolean("integrations.prefer-worldguard", true);
    }

    public int getWildernessBudget() {
        return clamp(config.getInt("generation.wilderness-budget", 40), 0, 500);
    }

    public int getErrorHaltThreshold() {
        return clamp(config.getInt("generation.error-halt-threshold", 25), 1, 1000);
    }

    public int getLootDrainPerTick() {
        return clamp(config.getInt("performance.loot-drain-per-tick", 4), 1, 32);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /** Persist the worlds.yml registry (world list) back to disk. */
    public void saveWorlds() {
        try {
            worlds.save(new File(plugin.getDataFolder(), "worlds.yml"));
        } catch (java.io.IOException e) {
            log.severe("Failed to save worlds.yml: " + e.getMessage());
        }
    }
}
