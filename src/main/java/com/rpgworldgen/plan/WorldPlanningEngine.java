package com.rpgworldgen.plan;

import com.rpgworldgen.model.PlannedRoad;
import com.rpgworldgen.model.PlannedStructure;
import com.rpgworldgen.util.CoordUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Deterministic procedural world-layout planner.
 *
 * <p>Pure Java (no Bukkit dependency): given a seed, world size, and counts/spacings,
 * it produces an identical layout every time. Steps: seed RNG, place spawn at center,
 * kingdoms (outermost/high tier), cities, villages (inner/low tier), dungeons spread
 * across all tiers, then road links (nearest-neighbor chains + kingdom links).
 */
public class WorldPlanningEngine {

    public static class Plan {
        public final long seed;
        public final int size;
        public final List<PlannedStructure> structures = new ArrayList<>();
        public final List<PlannedRoad> roads = new ArrayList<>();
        public final List<String> validationErrors = new ArrayList<>();

        public Plan(long seed, int size) { this.seed = seed; this.size = size; }

        public List<PlannedStructure> byKind(PlannedStructure.Kind k) {
            List<PlannedStructure> out = new ArrayList<>();
            for (PlannedStructure s : structures) if (s.getKind() == k) out.add(s);
            return out;
        }
    }

    public static class PlanRequest {
        public long seed;
        public int size = 6000;
        public int villages = 9;
        public int cities = 3;
        public int kingdoms = 2;
        public int dungeons = 14;
        public int landmarks = 4;
        public int villageSpacing = 550;
        public int citySpacing = 850;
        public int kingdomSpacing = 1300;
        public int dungeonSpacing = 300;
        public int landmarkSpacing = 400;
        public int minGap = 80;
    }

    private static final String[] VILLAGE_NAMES = {"Oakfield", "Millbrook", "Thornbury", "Ashford",
        "Willowmere", "Stonebridge", "Eldervale", "Fox Hollow", "Ravenshire", "Goldmeadow",
        "Cinderfell", "Mossgrove", "Dunmore", "Haverhill", "Kestrel Bay", "Larkspur",
        "Mud Hollow", "Nettleton", "Oakhaven", "Pinewatch"};
    private static final String[] CITY_NAMES = {"Valoria", "Drakhelm", "Silverport", "Thornhold",
        "Ember City", "Frostgate", "Kingsreach", "Stormhaven"};
    private static final String[] KINGDOM_NAMES = {"Aldoria", "Drakmor", "Elvendom", "Khaz Barak",
        "Suncrest Empire", "The Blighted Realm"};
    private static final String[] DUNGEON_NAMES = {"Sunken Crypt", "Whispering Caves", "Forsaken Mine",
        "Howling Catacombs", "Moss Temple", "Ironhold Bastion", "Buried Tomb", "Ashen Sanctuary",
        "Frostbound Deep", "Serpent Ruins", "Hollow Barrow", "Ember Depths", "Gloom Hollow",
        "Starfall Vault", "Thorn Maze", "Drowned Chapel", "Cinder Vault", "Wailing Pit",
        "Obsidian Gate", "Final Descent"};
    private static final String[] THEMES = {"medieval", "rustic", "forest", "plains", "snowy", "desert"};
    private static final String[] KINGDOM_THEMES = {"human", "elven", "dwarven", "desert", "corrupted"};
    private static final String[] DUNGEON_TYPES = {"cave", "ruins", "mine", "crypt", "catacombs",
        "forest_temple", "fortress", "desert_tomb", "corrupted_sanctuary", "endgame"};
    private static final String[] LANDMARK_NAMES = {"Elder Stone Circle", "Sky Beacon", "Fallen Star Shrine",
        "Whispering Cairn", "Sunken Obelisk", "Ember Watch", "Frostbrand Monolith", "Thorn Crown Altar"};

    public Plan generatePlan(PlanRequest req) {
        com.rpgworldgen.model.WorldPreset.validateSize(req.size);
        Random rng = new Random(req.seed);
        Plan plan = new Plan(req.seed, req.size);

        // 1. spawn at center
        plan.structures.add(new PlannedStructure(req.seed + ":spawn",
            PlannedStructure.Kind.SPAWN, "World Spawn", 0, 0, 60, 1, "plains", ""));

        int seq = 0;
        // 2. kingdoms: outer ring (far from center => high tier), each with border outposts
        for (int i = 0; i < req.kingdoms; i++) {
            String theme = KINGDOM_THEMES[i % KINGDOM_THEMES.length];
            int[] p = findSpot(rng, plan, req.size, 120, req.kingdomSpacing, req.minGap, 1400, req.size / 2 - 200);
            if (p == null) { plan.validationErrors.add("Could not place kingdom #" + (i + 1)); continue; }
            String id = req.seed + ":kingdom:" + i;
            int tier = 4 + (i == req.kingdoms - 1 ? 1 : 0);
            plan.structures.add(new PlannedStructure(id, PlannedStructure.Kind.KINGDOM_CAPITAL,
                KINGDOM_NAMES[(int) (rng.nextDouble() * KINGDOM_NAMES.length)] + " " + (i + 1),
                p[0], p[1], 120, Math.min(5, tier), theme, ""));
            seq++;
            // Border outposts around each capital (2 per kingdom, smaller protected circles).
            for (int o = 0; o < 2; o++) {
                double ang = rng.nextDouble() * Math.PI * 2;
                int ox = CoordUtil.clampCenter(p[0] + (int) (Math.cos(ang) * 260), 25, req.size);
                int oz = CoordUtil.clampCenter(p[1] + (int) (Math.sin(ang) * 260), 25, req.size);
                if (!CoordUtil.fitsInside(ox, oz, 25, req.size)) continue;
                boolean ok = true;
                PlannedStructure probe = new PlannedStructure("probe",
                    PlannedStructure.Kind.KINGDOM_OUTPOST, "probe", ox, oz, 25, 4, theme, "");
                for (PlannedStructure s : plan.structures) {
                    if (probe.overlaps(s, req.minGap)) { ok = false; break; }
                }
                if (!ok) continue;
                plan.structures.add(new PlannedStructure(req.seed + ":outpost:" + i + ":" + o,
                    PlannedStructure.Kind.KINGDOM_OUTPOST,
                    KINGDOM_NAMES[i % KINGDOM_NAMES.length] + " Outpost " + (o + 1),
                    ox, oz, 25, 4, theme, ""));
                seq++;
            }
        }
        // 3. cities: mid ring
        for (int i = 0; i < req.cities; i++) {
            int[] p = findSpot(rng, plan, req.size, 80, req.citySpacing, req.minGap, 700, req.size / 2 - 150);
            if (p == null) { plan.validationErrors.add("Could not place city #" + (i + 1)); continue; }
            String id = req.seed + ":city:" + i;
            plan.structures.add(new PlannedStructure(id, PlannedStructure.Kind.CITY,
                CITY_NAMES[(int) (rng.nextDouble() * CITY_NAMES.length)],
                p[0], p[1], 80, 2 + (i % 2), THEMES[(int) (rng.nextDouble() * THEMES.length)], ""));
            seq++;
        }
        // 4. villages: inner/mid area
        for (int i = 0; i < req.villages; i++) {
            int[] p = findSpot(rng, plan, req.size, 45, req.villageSpacing, req.minGap, 150, req.size / 2 - 120);
            if (p == null) { plan.validationErrors.add("Could not place village #" + (i + 1)); continue; }
            String id = req.seed + ":village:" + i;
            double dc = CoordUtil.distance(p[0], p[1], 0, 0) / (req.size / 2.0);
            int tier = dc < 0.35 ? 1 : 2;
            plan.structures.add(new PlannedStructure(id, PlannedStructure.Kind.VILLAGE,
                VILLAGE_NAMES[(i + (int) (rng.nextDouble() * VILLAGE_NAMES.length)) % VILLAGE_NAMES.length],
                p[0], p[1], 45, tier, THEMES[(int) (rng.nextDouble() * THEMES.length)], ""));
            seq++;
        }
        // 5. dungeons: anywhere with spacing, tier scaled by distance from center
        List<String> dnames = new ArrayList<>();
        Collections.addAll(dnames, DUNGEON_NAMES);
        Collections.shuffle(dnames, rng);
        for (int i = 0; i < req.dungeons; i++) {
            int[] p = findSpot(rng, plan, req.size, 30, req.dungeonSpacing, req.minGap, 200, req.size / 2 - 100);
            if (p == null) { plan.validationErrors.add("Could not place dungeon #" + (i + 1)); continue; }
            String id = req.seed + ":dungeon:" + i;
            double dc = CoordUtil.distance(p[0], p[1], 0, 0) / (req.size / 2.0);
            int tier = Math.min(5, 1 + (int) (dc * 5));
            String type = i == req.dungeons - 1 ? "endgame" : DUNGEON_TYPES[i % (DUNGEON_TYPES.length - 1)];
            plan.structures.add(new PlannedStructure(id, PlannedStructure.Kind.DUNGEON,
                dnames.get(i % dnames.size()), p[0], p[1], 30, tier, type, type));
            seq++;
        }
        // 6. landmarks: rare wilderness points of interest (beacon circles, shrines)
        for (int i = 0; i < req.landmarks; i++) {
            int[] p = findSpot(rng, plan, req.size, 20, req.landmarkSpacing, req.minGap, 300, req.size / 2 - 100);
            if (p == null) { plan.validationErrors.add("Could not place landmark #" + (i + 1)); continue; }
            double dc = CoordUtil.distance(p[0], p[1], 0, 0) / (req.size / 2.0);
            int tier = Math.min(5, 1 + (int) (dc * 5));
            plan.structures.add(new PlannedStructure(req.seed + ":landmark:" + i,
                PlannedStructure.Kind.LANDMARK,
                LANDMARK_NAMES[(i + (int) (rng.nextDouble() * LANDMARK_NAMES.length)) % LANDMARK_NAMES.length],
                p[0], p[1], 20, tier, "plains", "beacon"));
            seq++;
        }

        // 6. roads: connect each village to nearest city/capital, each city to nearest capital,
        //    capitals chained; route via midpoint with seeded perpendicular offset (terrain-following
        //    approximation; build phase re-routes around slopes using height sampling).
        linkToNearest(rng, plan, PlannedStructure.Kind.VILLAGE);
        linkToNearest(rng, plan, PlannedStructure.Kind.CITY);
        linkToNearest(rng, plan, PlannedStructure.Kind.KINGDOM_OUTPOST);
        chainKind(rng, plan, PlannedStructure.Kind.KINGDOM_CAPITAL);
        return plan;
    }

    private int[] findSpot(Random rng, Plan plan, int size, int radius, int spacing, int gap,
                           int minDistCenter, int maxDistCenter) {
        int h = size / 2 - radius - 20;
        int lo = Math.min(minDistCenter, h);
        for (int attempt = 0; attempt < 400; attempt++) {
            double ang = rng.nextDouble() * Math.PI * 2;
            double dist = lo + rng.nextDouble() * Math.max(1, (h - lo));
            int x = (int) (Math.cos(ang) * dist);
            int z = (int) (Math.sin(ang) * dist);
            if (!CoordUtil.fitsInside(x, z, radius, size)) continue;
            boolean ok = true;
            for (PlannedStructure s : plan.structures) {
                long minD = (long) radius + s.getRadius() + Math.max(spacing, gap);
                // spacing applies per-kind via caller's spacing for same category; use gap otherwise
                if (CoordUtil.distanceSq(x, z, s.getX(), s.getZ()) < minD * minD) { ok = false; break; }
            }
            if (ok) return new int[]{x, z};
        }
        return null;
    }

    private void linkToNearest(Random rng, Plan plan, PlannedStructure.Kind from) {
        for (PlannedStructure s : plan.byKind(from)) {
            PlannedStructure best = null;
            long bestD = Long.MAX_VALUE;
            for (PlannedStructure c : plan.structures) {
                if (c.getKind() != PlannedStructure.Kind.CITY
                    && c.getKind() != PlannedStructure.Kind.KINGDOM_CAPITAL) continue;
                long d = CoordUtil.distanceSq(s.getX(), s.getZ(), c.getX(), c.getZ());
                if (d < bestD) { bestD = d; best = c; }
            }
            if (best != null) addRoad(rng, plan, s, best);
        }
    }

    private void chainKind(Random rng, Plan plan, PlannedStructure.Kind k) {
        List<PlannedStructure> list = plan.byKind(k);
        for (int i = 1; i < list.size(); i++) addRoad(rng, plan, list.get(i - 1), list.get(i));
        if (list.size() > 2) addRoad(rng, plan, list.get(list.size() - 1), list.get(0));
    }

    private void addRoad(Random rng, Plan plan, PlannedStructure a, PlannedStructure b) {
        PlannedRoad road = new PlannedRoad(a.getId(), b.getId());
        road.addWaypoint(a.getX(), a.getZ());
        // seeded midpoint offset for organic (non-straight) routes
        double mx = (a.getX() + b.getX()) / 2.0;
        double mz = (a.getZ() + b.getZ()) / 2.0;
        double dx = b.getX() - a.getX(), dz = b.getZ() - a.getZ();
        double len = Math.max(1, Math.hypot(dx, dz));
        double off = (rng.nextDouble() - 0.5) * len * 0.25;
        int wx = CoordUtil.clampCenter((int) (mx - dz / len * off), 10, plan.size);
        int wz = CoordUtil.clampCenter((int) (mz + dx / len * off), 10, plan.size);
        road.addWaypoint(wx, wz);
        road.addWaypoint(b.getX(), b.getZ());
        plan.roads.add(road);
    }

    // ---- serialization (minimal JSON; avoids extra deps) ----

    public String toJson(Plan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"seed\":").append(plan.seed).append(",\"size\":").append(plan.size)
          .append(",\"structures\":[");
        boolean first = true;
        for (PlannedStructure s : plan.structures) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":\"").append(esc(s.getId())).append("\",\"kind\":\"").append(s.getKind())
              .append("\",\"name\":\"").append(esc(s.getName())).append("\",\"x\":").append(s.getX())
              .append(",\"z\":").append(s.getZ()).append(",\"r\":").append(s.getRadius())
              .append(",\"t\":").append(s.getTier()).append(",\"th\":\"").append(esc(s.getTheme()))
              .append("\",\"ex\":\"").append(esc(s.getExtra())).append("\"}");
        }
        sb.append("],\"roads\":[");
        first = true;
        for (PlannedRoad r : plan.roads) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"f\":\"").append(esc(r.getFromId())).append("\",\"t\":\"").append(esc(r.getToId()))
              .append("\",\"w\":[");
            boolean fw = true;
            for (int[] w : r.getWaypoints()) {
                if (!fw) sb.append(',');
                fw = false;
                sb.append('[').append(w[0]).append(',').append(w[1]).append(']');
            }
            sb.append("]}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
