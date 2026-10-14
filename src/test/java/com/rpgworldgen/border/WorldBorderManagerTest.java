package com.rpgworldgen.border;

import org.junit.Test;
import static org.junit.Assert.*;

public class WorldBorderManagerTest {

    @Test
    public void halfExtentConvention() {
        assertEquals(2500, WorldBorderManager.halfExtent(5000));
        assertEquals(3000, WorldBorderManager.halfExtent(6000));
        assertEquals(4000, WorldBorderManager.halfExtent(8000));
    }

    @Test
    public void inBoundsEdges() {
        assertTrue(WorldBorderManager.inBounds(2500, 2500, 5000));
        assertTrue(WorldBorderManager.inBounds(-2500, -2500, 5000));
        assertTrue(WorldBorderManager.inBounds(0, 0, 5000));
        assertFalse(WorldBorderManager.inBounds(2501, 0, 5000));
        assertFalse(WorldBorderManager.inBounds(0, -2501, 5000));
    }

    @Test
    public void borderSizeMatchesPreset() {
        assertEquals(5000.0, WorldBorderManager.borderSize(5000), 0.0);
        assertEquals(8000.0, WorldBorderManager.borderSize(8000), 0.0);
    }
}
