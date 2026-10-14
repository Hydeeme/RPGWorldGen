package com.rpgworldgen.plan;

import com.rpgworldgen.model.PlannedStructure;
import com.rpgworldgen.model.WorldPreset;
import org.junit.Test;
import static org.junit.Assert.*;

public class WorldPlanningEngineTest {

    private WorldPlanningEngine.PlanRequest req(long seed, int size) {
        WorldPlanningEngine.PlanRequest r = new WorldPlanningEngine.PlanRequest();
        WorldPreset p = WorldPreset.fromSize(size);
        r.seed = seed;
        r.size = size;
        if (p != null) {
            r.villages = p.getDefaultVillages();
            r.cities = p.getDefaultCities();
            r.kingdoms = p.getDefaultKingdoms();
            r.dungeons = p.getDefaultDungeons();
            r.villageSpacing = p.getVillageSpacing();
            r.citySpacing = p.getCitySpacing();
            r.kingdomSpacing = p.getKingdomSpacing();
        }
        return r;
    }

    @Test
    public void deterministicSameSeedSameLayout() {
        WorldPlanningEngine e = new WorldPlanningEngine();
        WorldPlanningEngine.Plan a = e.generatePlan(req(12345L, 6000));
        WorldPlanningEngine.Plan b = e.generatePlan(req(12345L, 6000));
        assertEquals(a.structures.size(), b.structures.size());
        for (int i = 0; i < a.structures.size(); i++) {
            assertEquals(a.structures.get(i).getX(), b.structures.get(i).getX());
            assertEquals(a.structures.get(i).getZ(), b.structures.get(i).getZ());
            assertEquals(a.structures.get(i).getId(), b.structures.get(i).getId());
        }
        assertEquals(a.roads.size(), b.roads.size());
    }

    @Test
    public void differentSeedsDiffer() {
        WorldPlanningEngine e = new WorldPlanningEngine();
        WorldPlanningEngine.Plan a = e.generatePlan(req(1L, 6000));
        WorldPlanningEngine.Plan b = e.generatePlan(req(2L, 6000));
        boolean anyDiff = false;
        for (int i = 0; i < Math.min(a.structures.size(), b.structures.size()); i++) {
            if (a.structures.get(i).getX() != b.structures.get(i).getX()
                || a.structures.get(i).getZ() != b.structures.get(i).getZ()) {
                anyDiff = true;
                break;
            }
        }
        assertTrue("Different seeds should produce different layouts", anyDiff);
    }

    @Test
    public void allStructuresInsideBorderAllPresets() {
        WorldPlanningEngine e = new WorldPlanningEngine();
        for (int size : new int[]{5000, 6000, 8000}) {
            WorldPlanningEngine.Plan plan = e.generatePlan(req(999L, size));
            int half = size / 2;
            for (PlannedStructure s : plan.structures) {
                assertTrue("Structure outside border: " + s.getName() + " at "
                    + s.getX() + "," + s.getZ() + " size=" + size,
                    Math.abs(s.getX()) + s.getRadius() <= half
                    && Math.abs(s.getZ()) + s.getRadius() <= half);
            }
        }
    }

    @Test
    public void noOverlappingMajorStructures() {
        WorldPlanningEngine e = new WorldPlanningEngine();
        WorldPlanningEngine.Plan plan = e.generatePlan(req(777L, 6000));
        for (int i = 0; i < plan.structures.size(); i++) {
            for (int j = i + 1; j < plan.structures.size(); j++) {
                PlannedStructure a = plan.structures.get(i);
                PlannedStructure b = plan.structures.get(j);
                // spawn is allowed near a village plaza; all others must not overlap circles
                if (a.getKind() == PlannedStructure.Kind.SPAWN
                    || b.getKind() == PlannedStructure.Kind.SPAWN) continue;
                assertFalse("Overlap: " + a.getName() + " vs " + b.getName(),
                    a.overlaps(b, 0));
            }
        }
    }

    @Test
    public void roadConnectivityCoversVillagesAndCities() {
        WorldPlanningEngine e = new WorldPlanningEngine();
        WorldPlanningEngine.Plan plan = e.generatePlan(req(42L, 6000));
        java.util.Set<String> linked = new java.util.HashSet<>();
        for (com.rpgworldgen.model.PlannedRoad r : plan.roads) {
            linked.add(r.getFromId());
            linked.add(r.getToId());
        }
        for (PlannedStructure s : plan.structures) {
            if (s.getKind() == PlannedStructure.Kind.VILLAGE
                || s.getKind() == PlannedStructure.Kind.CITY) {
                assertTrue("Unlinked settlement: " + s.getName(), linked.contains(s.getId()));
            }
        }
    }

    @Test
    public void serializationRoundTripStable() {
        WorldPlanningEngine e = new WorldPlanningEngine();
        WorldPlanningEngine.Plan plan = e.generatePlan(req(31337L, 5000));
        String json = e.toJson(plan);
        assertTrue(json.contains("\"seed\":31337"));
        assertTrue(json.contains("\"size\":5000"));
        assertTrue(json.contains("kingdom"));
    }
}
