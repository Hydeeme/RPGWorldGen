package com.rpgworldgen.model;

import com.rpgworldgen.plan.WorldPlanningEngine;
import org.junit.Test;
import static org.junit.Assert.*;

public class PlannedStructureTest {

    @Test
    public void overlapDetection() {
        PlannedStructure a = new PlannedStructure("a", PlannedStructure.Kind.VILLAGE,
            "A", 0, 0, 45, 1, "plains", "");
        PlannedStructure b = new PlannedStructure("b", PlannedStructure.Kind.VILLAGE,
            "B", 50, 0, 45, 1, "plains", "");
        PlannedStructure c = new PlannedStructure("c", PlannedStructure.Kind.VILLAGE,
            "C", 500, 500, 45, 1, "plains", "");
        assertTrue(a.overlaps(b, 0));
        assertFalse(a.overlaps(c, 0));
        assertTrue(a.overlaps(c, 1000));
    }

    @Test
    public void regionTierValues() {
        assertEquals(1, RegionTier.TIER_1.getRecommendedLevel());
        assertEquals(5, RegionTier.TIER_5.getLootTier());
        assertEquals(RegionTier.TIER_3, RegionTier.of(3));
    }

    @Test(expected = IllegalArgumentException.class)
    public void regionTierRejectsUnknown() {
        RegionTier.of(9);
    }

    @Test
    public void plannerRejectsInvalidSize() {
        WorldPlanningEngine.PlanRequest r = new WorldPlanningEngine.PlanRequest();
        r.seed = 1;
        r.size = 500; // below minimum
        try {
            new WorldPlanningEngine().generatePlan(r);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }
}
