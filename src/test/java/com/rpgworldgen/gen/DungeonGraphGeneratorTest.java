package com.rpgworldgen.gen;

import org.junit.Test;
import java.util.List;
import java.util.Random;
import static org.junit.Assert.*;

public class DungeonGraphGeneratorTest {

    @Test
    public void allRoomsReachable() {
        DungeonGraphGenerator g = new DungeonGraphGenerator();
        for (long seed : new long[]{1, 7, 99, 12345}) {
            DungeonGraphGenerator.Layout l = g.generate(new Random(seed), 12, 3, "crypt");
            List<Integer> missing = g.findUnreachable(l);
            assertTrue("Seed " + seed + " unreachable rooms: " + missing, missing.isEmpty());
        }
    }

    @Test
    public void roomRolesPresent() {
        DungeonGraphGenerator g = new DungeonGraphGenerator();
        DungeonGraphGenerator.Layout l = g.generate(new Random(5), 10, 2, "mine");
        assertEquals(10, l.rooms.size());
        assertEquals(DungeonGraphGenerator.RoomType.ENTRANCE, l.rooms.get(0).type);
        assertEquals(DungeonGraphGenerator.RoomType.BOSS, l.rooms.get(9).type);
        assertEquals(DungeonGraphGenerator.RoomType.LOOT, l.rooms.get(8).type);
        assertTrue(l.corridors.size() >= 9); // spanning tree minimum
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsTooFewRooms() {
        new DungeonGraphGenerator().generate(new Random(1), 3, 1, "cave");
    }

    @Test
    public void roadRouterAvoidsSteepCost() {
        RoadRouter router = new RoadRouter();
        // Ridge of high terrain straight ahead; flat around.
        RoadRouter.HeightSampler sampler = new RoadRouter.HeightSampler() {
            @Override public int heightAt(int x, int z) {
                if (Math.abs(x - 50) < 10 && z > -60 && z < 60) return 60; // ridge
                return 64;
            }
        };
        List<int[]> routed = router.route(sampler, 0, 0, 100, 0, 20);
        assertEquals(0, routed.get(0)[0]);
        assertEquals(100, routed.get(routed.size() - 1)[0]);
        // Routed path must include all segments and be finite-cost.
        assertTrue(RoadRouter.pathCost(sampler, routed) < Double.MAX_VALUE);
        assertTrue(routed.size() >= 2);
    }

    @Test
    public void schematicRotationMath() {
        assertArrayEquals(new int[]{2, 1}, SchematicIntegration.rotate(1, 0, 4, 3, 90));
        assertArrayEquals(new int[]{3, 2}, SchematicIntegration.rotate(0, 0, 4, 3, 180));
        assertArrayEquals(new int[]{0, 2}, SchematicIntegration.rotate(1, 0, 4, 3, 270));
        assertArrayEquals(new int[]{1, 0}, SchematicIntegration.rotate(1, 0, 4, 3, 0));
        assertArrayEquals(new int[]{1, 0}, SchematicIntegration.rotate(1, 0, 4, 3, 360));
    }
}
