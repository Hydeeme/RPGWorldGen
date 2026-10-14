package com.rpgworldgen.zones;

import org.junit.Test;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.*;

public class SafeZoneBoundaryTest {

    private SafeZoneManager.Zone zone() {
        Set<String> flags = new HashSet<>();
        flags.add("pvp-deny");
        flags.add("break-deny");
        return new SafeZoneManager.Zone("w:spawn", "w", "Spawn", 0, 0, 80, flags, "in", "out");
    }

    @Test
    public void containsCircle() {
        SafeZoneManager.Zone z = zone();
        assertTrue(z.contains(0, 0));
        assertTrue(z.contains(80, 0));
        assertTrue(z.contains(56, 56)); // ~79.2
        assertFalse(z.contains(81, 0));
        assertFalse(z.contains(60, 60)); // ~84.8
    }

    @Test
    public void flagsPresent() {
        assertTrue(zone().has("pvp-deny"));
        assertFalse(zone().has("place-deny"));
    }
}
