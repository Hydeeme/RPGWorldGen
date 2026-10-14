package com.rpgworldgen.plan;

import com.rpgworldgen.model.PlannedStructure;
import com.rpgworldgen.model.RegionTier;

import java.util.ArrayList;
import java.util.List;

/**
 * Assigns progression tiers and themed region identities from plan geometry.
 *
 * <p>Rule: normalized distance from spawn (0 = center, 1 = border) maps to tiers 1..5.
 * Pure Java, unit-testable. Region names come from tier defaults plus a directional
 * qualifier so admins can tell regions apart.
 */
public class RegionGenerationService {

    public static class Region {
        public final String id;
        public final String name;
        public final RegionTier tier;
        public final int cx, cz, radius;

        public Region(String id, String name, RegionTier tier, int cx, int cz, int radius) {
            this.id = id;
            this.name = name;
            this.tier = tier;
            this.cx = cx;
            this.cz = cz;
            this.radius = radius;
        }
    }

    /** Tier for a normalized distance 0..1 from spawn. */
    public static RegionTier tierForDistance(double normalized) {
        if (normalized < 0.25) return RegionTier.TIER_1;
        if (normalized < 0.45) return RegionTier.TIER_2;
        if (normalized < 0.65) return RegionTier.TIER_3;
        if (normalized < 0.85) return RegionTier.TIER_4;
        return RegionTier.TIER_5;
    }

    /** Build one region entry per major structure in the plan. */
    public List<Region> regionsFor(WorldPlanningEngine.Plan plan) {
        List<Region> out = new ArrayList<>();
        double half = plan.size / 2.0;
        for (PlannedStructure s : plan.structures) {
            double norm = Math.hypot(s.getX(), s.getZ()) / half;
            RegionTier tier = RegionTier.of(s.getTier());
            // Cross-check: flag structures whose stored tier disagrees with geometry.
            RegionTier geo = tierForDistance(Math.min(1.0, norm));
            RegionTier effective = Math.abs(geo.getLevel() - tier.getLevel()) <= 1 ? tier : geo;
            out.add(new Region(s.getId(), effective.getDefaultName() + " — " + s.getName(),
                effective, s.getX(), s.getZ(), s.getRadius() + 60));
        }
        return out;
    }
}
