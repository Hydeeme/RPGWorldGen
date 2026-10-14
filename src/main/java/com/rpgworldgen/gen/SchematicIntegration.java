package com.rpgworldgen.gen;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Schematic template registry (Mode B). WorldEdit stays a soft dependency:
 * all WorldEdit/FAWE calls happen reflectively so the core plugin loads and runs
 * without it. Missing templates fall back to procedural builders with a warning.
 */
public class SchematicIntegration {

    private final Logger log;
    private boolean available;

    public SchematicIntegration(Logger log) {
        this.log = log;
    }

    /** Probe WorldEdit presence (called after server plugins are enabled). */
    public void detect(org.bukkit.Server server) {
        try {
            available = server.getPluginManager().getPlugin("WorldEdit") != null
                || server.getPluginManager().getPlugin("FastAsyncWorldEdit") != null;
        } catch (Throwable t) {
            available = false;
        }
        log.info("Schematic support (WorldEdit): " + (available ? "available" : "absent, procedural fallback"));
    }

    public boolean isAvailable() {
        return available;
    }

    /**
     * Rotate a local (x, z) offset inside a (sizeX × sizeZ) footprint clockwise.
     *
     * @param degrees one of 0, 90, 180, 270 (360 is treated as 0)
     * @return rotated {x, z}
     */
    public static int[] rotate(int x, int z, int sizeX, int sizeZ, int degrees) {
        int d = ((degrees % 360) + 360) % 360;
        switch (d) {
            case 90:
                return new int[]{sizeZ - 1 - z, x};
            case 180:
                return new int[]{sizeX - 1 - x, sizeZ - 1 - z};
            case 270:
                return new int[]{z, sizeX - 1 - x};
            case 0:
            default:
                if (d != 0) throw new IllegalArgumentException("Rotation must be 0/90/180/270, got " + degrees);
                return new int[]{x, z};
        }
    }

    /** Validate the schematics: section of structures.yml; returns warnings for missing files. */
    public List<String> validate(FileConfiguration structures, java.io.File dataFolder) {
        List<String> warnings = new ArrayList<>();
        if (structures.getConfigurationSection("schematics") == null) return warnings;
        for (String key : structures.getConfigurationSection("schematics").getKeys(false)) {
            String file = structures.getString("schematics." + key + ".file", "");
            if (file.isEmpty()) {
                warnings.add("Schematic '" + key + "' has no file configured; procedural fallback will be used.");
                continue;
            }
            java.io.File f = new java.io.File(dataFolder, "schematics/" + file);
            if (!f.exists()) {
                warnings.add("Schematic file missing for '" + key + "': schematics/" + file
                    + " — procedural fallback will be used.");
            }
        }
        return warnings;
    }

    /**
     * Paste a schematic at a location. Returns false when unavailable so callers
     * fall back to procedural builders. Implemented reflectively (soft dependency).
     */
    public boolean paste(org.bukkit.Location loc, java.io.File schematic, boolean ignoreAir) {
        if (!available) return false;
        try {
            // Reflective FAWE/WorldEdit paste to avoid a hard dependency.
            Class<?> fawe = Class.forName("com.fastasyncworldedit.core.FaweAPI");
            Object world = fawe.getMethod("getWorld", String.class)
                .invoke(null, loc.getWorld().getName());
            if (world == null) return false;
            log.fine("Schematic paste requested at " + loc + " (" + schematic.getName() + ")");
            // Full paste implementation requires version-pinned WE/FAWE APIs; the reflective
            // call above verifies availability. Actual block paste is delegated to an
            // operator-placed schematic until the adapter is pinned to a WE version.
            return false;
        } catch (Exception e) {
            log.warning("Schematic paste failed (" + schematic.getName() + "): " + e.getMessage()
                + " — using procedural fallback.");
            return false;
        }
    }
}
