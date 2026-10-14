package com.rpgworldgen.integration;

import com.rpgworldgen.model.PlannedRoad;
import com.rpgworldgen.model.PlannedStructure;
import com.rpgworldgen.plan.WorldPlanningEngine;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Publishes world plans as live map markers on Dynmap and/or BlueMap when
 * those plugins are installed. Fully reflective: no compile-time dependency
 * on either map plugin, and either (or both) may be absent.
 *
 * <p>Dynmap: one MarkerSet {@code rpg_<world>} with one Marker per structure
 * (default version icons), one CircleMarker for the spawn safe-zone, and one
 * PolyLineMarker per road. BlueMap: one MarkerSet {@code rpg_<world>} on every
 * map of the world with POIMarkers per structure, plus one LineMarker per road.
 *
 * <p>Ids are deterministic ({@code rpg_<kind>_<x>_<z>}), so publishing is
 * idempotent: existing entries are deleted before re-creation. Marker Y uses
 * the world's max height / 2 (pure plan data; interactively correct enough).
 */
public class MapMarkerService {

    /** Marker-set id prefix shared by both map plugins. */
    public static final String SET_PREFIX = "rpg_";
    /** Human label shown in the map UI layer list. */
    public static final String SET_LABEL = "RPG World";

    private final Logger log;
    private final Map<String, Integer> publishedByWorld = new HashMap<>();

    public MapMarkerService(Logger log) {
        this.log = log;
    }

    // ---- pure helpers (unit-testable, no Bukkit) ----

    /** Deterministic marker-set id for a world (stable across restarts). */
    public static String markerSetId(String world) {
        return IntegrationManager.sanitizeRegionId(SET_PREFIX + world);
    }

    /** Deterministic marker id for a structure (stable across restarts). */
    public static String markerId(PlannedStructure s) {
        return IntegrationManager.sanitizeRegionId(
            "rpg_" + s.getKind().name().toLowerCase() + "_" + s.getX() + "_" + s.getZ());
    }

    /**
     * Dynmap built-in icon name per structure kind. Names verified against the
     * default icon pack shipped in the Dynmap JAR; unknown kinds fall back to
     * {@code default}.
     */
    public static String dynmapIcon(PlannedStructure.Kind kind) {
        switch (kind) {
            case SPAWN: return "star";
            case KINGDOM_CAPITAL: return "king";
            case CITY: return "building";
            case VILLAGE: return "bighouse";
            case KINGDOM_OUTPOST: return "shield";
            case DUNGEON: return "skull";
            case LANDMARK: return "diamond";
            default: return "default";
        }
    }

    /** Marker label shown on the map: name + coords + tier (for locate parity). */
    public static String markerLabel(PlannedStructure s) {
        return s.getName() + " (" + s.getX() + "," + s.getZ() + ") T" + s.getTier();
    }

    /** Hex RGB per kind, rendered as Dynmap line/fill colors. */
    public static int kindColor(PlannedStructure.Kind kind) {
        switch (kind) {
            case SPAWN: return 0xFFD700;
            case KINGDOM_CAPITAL: return 0xFF3C3C;
            case CITY: return 0xFF8C00;
            case VILLAGE: return 0x50C850;
            case KINGDOM_OUTPOST: return 0xC83CC8;
            case DUNGEON: return 0x963CDC;
            case LANDMARK: return 0x46B4FF;
            default: return 0xFFFFFF;
        }
    }

    /** Count pushed to status output (published markers last run). */
    public int publishedCount(String world) {
        Integer n = publishedByWorld.get(world);
        return n == null ? 0 : n;
    }

    /**
     * Publish one world's plan to every installed map plugin. Returns a
     * human-readable summary (never throws).
     */
    public String publish(String world, WorldPlanningEngine.Plan plan) {
        List<String> parts = new ArrayList<>();
        int total = 0;
        org.bukkit.World bw = Bukkit.getWorld(world);
        int y = 64;
        if (bw != null) {
            try { y = (bw.getMinHeight() + bw.getMaxHeight()) / 2; } catch (Throwable t) { y = 64; }
        }
        String dyn = publishDynmap(world, plan, y);
        parts.add(dyn.startsWith("ok") || dyn.startsWith("published")
            ? "Dynmap: " + dyn : "Dynmap: " + dyn);
        String bm = publishBlueMap(world, plan, y);
        parts.add("BlueMap: " + bm);
        Integer n = publishedByWorld.get(world);
        total = n == null ? 0 : n;
        parts.add("markers=" + total);
        return String.join("; ", parts);
    }

    /** Remove one world's marker set from both map plugins (never throws). */
    public String clear(String world) {
        clearDynmap(world);
        clearBlueMap(world);
        publishedByWorld.remove(world);
        return "map markers cleared for '" + world + "'.";
    }

    // ---- Dynmap (reflective) ----

    private Object dynmapApi() {
        try {
            org.bukkit.plugin.Plugin p =
                Bukkit.getPluginManager().getPlugin("dynmap");
            if (p == null || !p.isEnabled()) return null;
            Object api = p.getClass().getMethod("getMarkerAPI").invoke(p);
            try {
                Object inited = p.getClass().getMethod("markerAPIInitialized").invoke(p);
                if (!Boolean.TRUE.equals(inited)) return null;
            } catch (NoSuchMethodException e) {
                // older Dynmap without the guard: proceed
            }
            return api;
        } catch (Throwable t) {
            return null;
        }
    }

    private String publishDynmap(String world, WorldPlanningEngine.Plan plan, int y) {
        Object markerApi = dynmapApi();
        if (markerApi == null) return "not installed";
        try {
            Class<?> setClass = Class.forName("org.dynmap.markers.MarkerSet");
            String setId = markerSetId(world);
            Object existing = markerApi.getClass()
                .getMethod("getMarkerSet", String.class).invoke(markerApi, setId);
            if (existing != null) {
                existing.getClass().getMethod("deleteMarkerSet").invoke(existing);
            }
            Object set = markerApi.getClass().getMethod("createMarkerSet",
                    String.class, String.class, java.util.Set.class, boolean.class)
                .invoke(markerApi, setId, SET_LABEL + " — " + world, null, true);
            if (set == null) return "marker set creation failed";
            int count = 0;
            for (PlannedStructure s : plan.structures) {
                String iconName = dynmapIcon(s.getKind());
                Object icon = null;
                try {
                    icon = markerApi.getClass()
                        .getMethod("getMarkerIcon", String.class).invoke(markerApi, iconName);
                } catch (Throwable t) {
                    icon = null;
                }
                Object m = setClass.getMethod("createMarker", String.class, String.class,
                        boolean.class, String.class, double.class, double.class, double.class,
                        Class.forName("org.dynmap.markers.MarkerIcon"), boolean.class)
                    .invoke(set, markerId(s), markerLabel(s), true, world,
                        (double) s.getX(), (double) y, (double) s.getZ(), icon, true);
                if (m != null) count++;
            }
            // spawn safe-zone circle
            for (PlannedStructure s : plan.structures) {
                if (s.getKind() != PlannedStructure.Kind.SPAWN) continue;
                try {
                    setClass.getMethod("createCircleMarker", String.class, String.class,
                            boolean.class, String.class, double.class, double.class, double.class,
                            double.class, double.class, boolean.class)
                        .invoke(set, markerId(s) + "_zone", s.getName() + " safe zone",
                            true, world, (double) s.getX(), (double) y, (double) s.getZ(),
                            (double) s.getRadius(), (double) s.getRadius(), true);
                    count++;
                } catch (Throwable t) {
                    log.warning("Dynmap circle marker failed: " + t.getMessage());
                }
            }
            // roads as polylines
            for (int i = 0; i < plan.roads.size(); i++) {
                PlannedRoad r = plan.roads.get(i);
                try {
                    List<int[]> wps = r.getWaypoints();
                    double[] xs = new double[wps.size()];
                    double[] ys = new double[wps.size()];
                    double[] zs = new double[wps.size()];
                    for (int j = 0; j < wps.size(); j++) {
                        xs[j] = wps.get(j)[0]; ys[j] = y; zs[j] = wps.get(j)[1];
                    }
                    Object line = setClass.getMethod("createPolyLineMarker", String.class,
                            String.class, boolean.class, String.class,
                            double[].class, double[].class, double[].class, boolean.class)
                        .invoke(set, setId + "_road_" + i, "Road " + (i + 1),
                            true, world, xs, ys, zs, true);
                    if (line != null) {
                        line.getClass().getMethod("setLineStyle", int.class, double.class, int.class)
                            .invoke(line, 2, 0.8, 0xBEA05A);
                        count++;
                    }
                } catch (Throwable t) {
                    log.warning("Dynmap road marker failed: " + t.getMessage());
                }
            }
            publishedByWorld.merge(world, count, Integer::sum);
            return "published " + count + " to set '" + setId + "'";
        } catch (Throwable t) {
            log.warning("Dynmap publish failed for '" + world + "': " + t.getMessage());
            return "failed: " + t.getMessage();
        }
    }

    private void clearDynmap(String world) {
        try {
            Object markerApi = dynmapApi();
            if (markerApi == null) return;
            Object set = markerApi.getClass()
                .getMethod("getMarkerSet", String.class)
                .invoke(markerApi, markerSetId(world));
            if (set != null) set.getClass().getMethod("deleteMarkerSet").invoke(set);
        } catch (Throwable t) {
            log.warning("Dynmap clear failed for '" + world + "': " + t.getMessage());
        }
    }

    // ---- BlueMap (reflective) ----

    private Object blueMapApi() {
        try {
            Class<?> apiClass = Class.forName("de.bluecolored.bluemap.api.BlueMapAPI");
            Object opt = apiClass.getMethod("getInstance").invoke(null);
            Object present = opt.getClass().getMethod("isPresent").invoke(opt);
            if (!Boolean.TRUE.equals(present)) return null;
            return opt.getClass().getMethod("get").invoke(opt);
        } catch (Throwable t) {
            return null;
        }
    }

    private String publishBlueMap(String world, WorldPlanningEngine.Plan plan, int y) {
        Object api = blueMapApi();
        if (api == null) return "not installed";
        String setId = markerSetId(world);
        int count = 0;
        List<String> problems = new ArrayList<>();
        try {
            Class<?> vec3 = Class.forName("com.flowpowered.math.vector.Vector3d");
            java.lang.reflect.Constructor<?> vec3ctor =
                vec3.getConstructor(double.class, double.class, double.class);
            Class<?> markerSetClass =
                Class.forName("de.bluecolored.bluemap.api.markers.MarkerSet");
            Class<?> poiClass =
                Class.forName("de.bluecolored.bluemap.api.markers.POIMarker");
            Class<?> lineMarkerClass =
                Class.forName("de.bluecolored.bluemap.api.markers.LineMarker");
            Class<?> lineClass = Class.forName("de.bluecolored.bluemap.api.math.Line");
            Class<?> colorClass = Class.forName("de.bluecolored.bluemap.api.math.Color");
            Object worldOpt = api.getClass().getMethod("getWorld", Object.class)
                .invoke(api, Bukkit.getWorld(world));
            boolean hasWorld = (Boolean) worldOpt.getClass()
                .getMethod("isPresent").invoke(worldOpt);
            if (!hasWorld) return "world not known to BlueMap yet (render once, then link)";
            Object bmWorld = worldOpt.getClass().getMethod("get").invoke(worldOpt);
            @SuppressWarnings("unchecked")
            java.util.Collection<Object> maps = (java.util.Collection<Object>) bmWorld.getClass()
                .getMethod("getMaps").invoke(bmWorld);
            if (maps.isEmpty()) return "no BlueMap maps for world yet";
            for (Object map : maps) {
                @SuppressWarnings("unchecked")
                Map<String, Object> sets = (Map<String, Object>) map.getClass()
                    .getMethod("getMarkerSets").invoke(map);
                Object set = sets.get(setId);
                if (set != null) {
                    set.getClass().getMethod("getMarkers").invoke(set);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> markers = (Map<String, Object>) set.getClass()
                        .getMethod("getMarkers").invoke(set);
                    markers.clear();
                } else {
                    Object builder = markerSetClass.getMethod("builder").invoke(null);
                    builder.getClass().getMethod("label", String.class)
                        .invoke(builder, SET_LABEL + " — " + world);
                    set = builder.getClass().getMethod("build").invoke(builder);
                    sets.put(setId, set);
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> markers = (Map<String, Object>) set.getClass()
                    .getMethod("getMarkers").invoke(set);
                for (PlannedStructure s : plan.structures) {
                    try {
                        Object b = poiClass.getMethod("builder").invoke(null);
                        b.getClass().getMethod("label", String.class)
                            .invoke(b, markerLabel(s));
                        Object pos = vec3ctor.newInstance(
                            (double) s.getX(), (double) y, (double) s.getZ());
                        b.getClass().getMethod("position",
                                Class.forName("com.flowpowered.math.vector.Vector3d"))
                            .invoke(b, pos);
                        b.getClass().getMethod("detail", String.class).invoke(b,
                            "<b>" + escHtml(s.getName()) + "</b><br>" + s.getKind()
                            + " — tier " + s.getTier() + "<br>(" + s.getX() + ", " + s.getZ() + ")");
                        markers.put(markerId(s), b.getClass().getMethod("build").invoke(b));
                        count++;
                    } catch (Throwable t) {
                        problems.add(markerId(s) + ": " + t.getMessage());
                    }
                }
                for (int i = 0; i < plan.roads.size(); i++) {
                    try {
                        Object lineBuilder = lineClass.getMethod("builder").invoke(null);
                        for (int[] w : plan.roads.get(i).getWaypoints()) {
                            lineBuilder.getClass().getMethod("addPoint", vec3).invoke(lineBuilder,
                                vec3ctor.newInstance((double) w[0], (double) y, (double) w[1]));
                        }
                        Object line = lineBuilder.getClass().getMethod("build").invoke(lineBuilder);
                        Object lb = lineMarkerClass.getMethod("builder").invoke(null);
                        lb.getClass().getMethod("label", String.class)
                            .invoke(lb, "Road " + (i + 1));
                        lb.getClass().getMethod("line", lineClass).invoke(lb, line);
                        lb.getClass().getMethod("lineWidth", int.class).invoke(lb, 3);
                        Object col = colorClass.getConstructor(int.class, int.class, int.class)
                            .newInstance(190, 160, 90);
                        lb.getClass().getMethod("lineColor", colorClass).invoke(lb, col);
                        Object lm = lb.getClass().getMethod("build").invoke(lb);
                        markers.put(setId + "_road_" + i, lm);
                        count++;
                    } catch (Throwable t) {
                        problems.add("road " + i + ": " + t.getMessage());
                    }
                }
            }
            // count once across maps (same markers on every map)
            publishedByWorld.merge(world, count / Math.max(1, maps.size()), Integer::sum);
            if (!problems.isEmpty()) {
                log.warning("BlueMap marker warnings for '" + world + "': "
                    + String.join("; ", problems.subList(0, Math.min(5, problems.size()))));
            }
            return "published " + count / Math.max(1, maps.size())
                + " markers on " + maps.size() + " map(s)";
        } catch (Throwable t) {
            log.warning("BlueMap publish failed for '" + world + "': " + t.getMessage());
            return "failed: " + t.getMessage();
        }
    }

    private void clearBlueMap(String world) {
        try {
            Object api = blueMapApi();
            if (api == null) return;
            String setId = markerSetId(world);
            Object worldOpt = api.getClass().getMethod("getWorld", Object.class)
                .invoke(api, Bukkit.getWorld(world));
            boolean hasWorld = (Boolean) worldOpt.getClass()
                .getMethod("isPresent").invoke(worldOpt);
            if (!hasWorld) return;
            Object bmWorld = worldOpt.getClass().getMethod("get").invoke(worldOpt);
            @SuppressWarnings("unchecked")
            java.util.Collection<Object> maps = (java.util.Collection<Object>) bmWorld.getClass()
                .getMethod("getMaps").invoke(bmWorld);
            for (Object map : maps) {
                @SuppressWarnings("unchecked")
                Map<String, Object> sets = (Map<String, Object>) map.getClass()
                    .getMethod("getMarkerSets").invoke(map);
                sets.remove(setId);
            }
        } catch (Throwable t) {
            log.warning("BlueMap clear failed for '" + world + "': " + t.getMessage());
        }
    }

    private static String escHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
