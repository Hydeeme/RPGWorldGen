package com.rpgworldgen.integration;

import com.rpgworldgen.model.PlannedStructure;
import org.junit.Test;

import static org.junit.Assert.*;

public class MapMarkerServiceTest {

    private static PlannedStructure s(PlannedStructure.Kind k, String name, int x, int z, int tier) {
        return new PlannedStructure("t:" + k + ":" + x + ":" + z, k, name, x, z, 30, tier, "", "");
    }

    @Test
    public void markerSetIdIsDeterministicAndSanitized() {
        assertEquals(MapMarkerService.markerSetId("Verify5000"),
            MapMarkerService.markerSetId("Verify5000"));
        assertEquals("rpg_verify5000", MapMarkerService.markerSetId("Verify5000"));
        assertEquals("rpg_my_world", MapMarkerService.markerSetId("My World"));
    }

    @Test
    public void markerIdIsUniquePerKindAndCoords() {
        PlannedStructure a = s(PlannedStructure.Kind.VILLAGE, "A", 10, 20, 1);
        PlannedStructure b = s(PlannedStructure.Kind.DUNGEON, "B", 10, 20, 3);
        PlannedStructure c = s(PlannedStructure.Kind.VILLAGE, "C", 11, 20, 1);
        assertEquals(MapMarkerService.markerId(a), MapMarkerService.markerId(a));
        assertNotEquals(MapMarkerService.markerId(a), MapMarkerService.markerId(b));
        assertNotEquals(MapMarkerService.markerId(a), MapMarkerService.markerId(c));
        assertEquals("rpg_village_10_20", MapMarkerService.markerId(a));
    }

    @Test
    public void dynmapIconsUseStockNamesOnly() {
        assertEquals("star", MapMarkerService.dynmapIcon(PlannedStructure.Kind.SPAWN));
        assertEquals("king", MapMarkerService.dynmapIcon(PlannedStructure.Kind.KINGDOM_CAPITAL));
        assertEquals("building", MapMarkerService.dynmapIcon(PlannedStructure.Kind.CITY));
        assertEquals("bighouse", MapMarkerService.dynmapIcon(PlannedStructure.Kind.VILLAGE));
        assertEquals("shield", MapMarkerService.dynmapIcon(PlannedStructure.Kind.KINGDOM_OUTPOST));
        assertEquals("skull", MapMarkerService.dynmapIcon(PlannedStructure.Kind.DUNGEON));
        assertEquals("diamond", MapMarkerService.dynmapIcon(PlannedStructure.Kind.LANDMARK));
    }

    @Test
    public void markerLabelCarriesNameCoordsTier() {
        PlannedStructure d = s(PlannedStructure.Kind.DUNGEON, "Sunken Crypt", 367, 240, 3);
        assertEquals("Sunken Crypt (367,240) T3", MapMarkerService.markerLabel(d));
    }

    @Test
    public void kindColorsAreDistinct() {
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (PlannedStructure.Kind k : PlannedStructure.Kind.values()) {
            assertTrue("duplicate color for " + k, seen.add(MapMarkerService.kindColor(k)));
        }
    }
}
