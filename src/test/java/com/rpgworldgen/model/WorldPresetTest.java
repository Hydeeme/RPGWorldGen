package com.rpgworldgen.model;

import org.junit.Test;
import static org.junit.Assert.*;

public class WorldPresetTest {

    @Test
    public void presetSizes() {
        assertEquals(5000, WorldPreset.SMALL.getSize());
        assertEquals(6000, WorldPreset.MEDIUM.getSize());
        assertEquals(8000, WorldPreset.LARGE.getSize());
    }

    @Test
    public void halfExtentConvention() {
        assertEquals(2500, WorldPreset.SMALL.getHalfExtent());
        assertEquals(3000, WorldPreset.MEDIUM.getHalfExtent());
        assertEquals(4000, WorldPreset.LARGE.getHalfExtent());
    }

    @Test
    public void fromSizeResolves() {
        assertEquals(WorldPreset.SMALL, WorldPreset.fromSize(5000));
        assertEquals(WorldPreset.MEDIUM, WorldPreset.fromSize(6000));
        assertEquals(WorldPreset.LARGE, WorldPreset.fromSize(8000));
        assertNull(WorldPreset.fromSize(7000));
    }

    @Test
    public void validateAcceptsPresetsAndCustom() {
        assertEquals(5000, WorldPreset.validateSize(5000));
        assertEquals(7000, WorldPreset.validateSize(7000));
        assertEquals(12000, WorldPreset.validateSize(12000));
    }

    @Test(expected = IllegalArgumentException.class)
    public void validateRejectsTooSmall() {
        WorldPreset.validateSize(1000);
    }

    @Test(expected = IllegalArgumentException.class)
    public void validateRejectsTooLarge() {
        WorldPreset.validateSize(20000);
    }

    @Test(expected = IllegalArgumentException.class)
    public void validateRejectsNonMultiple() {
        WorldPreset.validateSize(5555);
    }
}
