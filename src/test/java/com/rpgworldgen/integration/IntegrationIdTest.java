package com.rpgworldgen.integration;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class IntegrationIdTest {

    private static Set<String> flags(String... f) {
        return new HashSet<>(Arrays.asList(f));
    }

    @Test
    public void sanitizeRegionIdRejectsIllegalChars() {
        assertEquals("verify5000_spawn", IntegrationManager.sanitizeRegionId("Verify5000:spawn"));
        assertEquals("rpg_zone", IntegrationManager.sanitizeRegionId(""));
        assertEquals("rpg_zone", IntegrationManager.sanitizeRegionId(null));
        assertEquals("a_b-c1", IntegrationManager.sanitizeRegionId("A B-C1"));
    }

    @Test
    public void zoneRegionIdIsDeterministic() {
        assertEquals(IntegrationManager.zoneRegionId("Verify5000", 0, 0),
            IntegrationManager.zoneRegionId("Verify5000", 0, 0));
        assertEquals("rpg_verify5000_0_0", IntegrationManager.zoneRegionId("Verify5000", 0, 0));
        assertNotEquals(IntegrationManager.zoneRegionId("Verify5000", 0, 0),
            IntegrationManager.zoneRegionId("Verify5000", 10, -5));
    }

    @Test
    public void wgDenyFlagsCoverAllBuiltInFlags() {
        Set<String> all = flags("pvp-deny", "mob-spawn-deny", "break-deny",
            "place-deny", "explosion-deny", "fire-deny");
        assertEquals(Arrays.asList("PVP", "BLOCK_BREAK", "BLOCK_PLACE", "MOB_SPAWNING",
            "TNT", "OTHER_EXPLOSION", "CREEPER_EXPLOSION", "FIRE_SPREAD", "LAVA_FIRE"),
            IntegrationManager.wgDenyFlags(all));
    }

    @Test
    public void wgDenyFlagsEmptyForEmpty() {
        assertTrue(IntegrationManager.wgDenyFlags(new HashSet<>()).isEmpty());
    }

    @Test
    public void multiverseImportCommandFormat() {
        assertEquals("mv import MyWorld normal",
            IntegrationManager.multiverseImportCommand("MyWorld"));
    }
}
