package com.rpgworldgen.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class CoordUtilTest {

    @Test
    public void inBoundsEdges() {
        assertTrue(CoordUtil.inBounds(2500, 2500, 5000));
        assertTrue(CoordUtil.inBounds(-2500, -2500, 5000));
        assertTrue(CoordUtil.inBounds(0, 0, 5000));
        assertFalse(CoordUtil.inBounds(2501, 0, 5000));
        assertFalse(CoordUtil.inBounds(0, -2501, 5000));
    }

    @Test
    public void fitsInsideRespectsRadius() {
        assertTrue(CoordUtil.fitsInside(2400, 0, 100, 5000));
        assertFalse(CoordUtil.fitsInside(2450, 0, 100, 5000));
    }

    @Test
    public void clampCenterKeepsCircleInside() {
        int c = CoordUtil.clampCenter(100000, 120, 6000);
        assertTrue(CoordUtil.fitsInside(c, 0, 120, 6000));
        assertEquals(0, CoordUtil.clampCenter(5, 100000, 6000));
    }
}
