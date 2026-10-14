package com.rpgworldgen.plan;

import com.rpgworldgen.model.RegionTier;
import org.junit.Test;
import static org.junit.Assert.*;

public class RegionGenerationServiceTest {

    @Test
    public void tierMappingByDistance() {
        assertEquals(RegionTier.TIER_1, RegionGenerationService.tierForDistance(0.0));
        assertEquals(RegionTier.TIER_1, RegionGenerationService.tierForDistance(0.24));
        assertEquals(RegionTier.TIER_2, RegionGenerationService.tierForDistance(0.3));
        assertEquals(RegionTier.TIER_3, RegionGenerationService.tierForDistance(0.5));
        assertEquals(RegionTier.TIER_4, RegionGenerationService.tierForDistance(0.7));
        assertEquals(RegionTier.TIER_5, RegionGenerationService.tierForDistance(0.9));
        assertEquals(RegionTier.TIER_5, RegionGenerationService.tierForDistance(1.0));
    }

    @Test
    public void regionsCoverPlannedStructures() {
        WorldPlanningEngine engine = new WorldPlanningEngine();
        WorldPlanningEngine.PlanRequest req = new WorldPlanningEngine.PlanRequest();
        req.seed = 4242L;
        req.size = 6000;
        WorldPlanningEngine.Plan plan = engine.generatePlan(req);
        RegionGenerationService svc = new RegionGenerationService();
        java.util.List<RegionGenerationService.Region> regions = svc.regionsFor(plan);
        assertEquals(plan.structures.size(), regions.size());
        for (RegionGenerationService.Region r : regions) {
            assertTrue(r.tier.getLevel() >= 1 && r.tier.getLevel() <= 5);
        }
    }

    @Test
    public void terrainScoreRejectsWaterAndSteep() {
        assertEquals(0, TerrainAnalysisService.score(true, false, 1.0));
        assertEquals(0, TerrainAnalysisService.score(false, true, 1.0));
        assertEquals(5, TerrainAnalysisService.score(false, false, 9.0));
        assertEquals(40, TerrainAnalysisService.score(false, false, 6.0));
        assertEquals(70, TerrainAnalysisService.score(false, false, 4.0));
        assertEquals(95, TerrainAnalysisService.score(false, false, 1.0));
    }
}
