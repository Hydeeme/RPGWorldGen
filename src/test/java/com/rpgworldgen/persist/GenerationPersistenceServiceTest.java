package com.rpgworldgen.persist;

import com.rpgworldgen.model.PlannedStructure;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.logging.Logger;

import static org.junit.Assert.*;

public class GenerationPersistenceServiceTest {

    private GenerationPersistenceService db;

    @Before
    public void init() throws Exception {
        db = new GenerationPersistenceService(Logger.getLogger("test"), "jdbc:sqlite::memory:");
        db.init();
    }

    @After
    public void close() {
        db.close();
    }

    @Test
    public void worldAndStructureRoundTrip() throws Exception {
        db.saveWorld("testworld", 123L, 6000, "MEDIUM", "PLANNED", "{\"seed\":123}");
        assertEquals("{\"seed\":123}", db.loadPlanJson("testworld"));
        PlannedStructure s = new PlannedStructure("123:village:0",
            PlannedStructure.Kind.VILLAGE, "Oakfield", 100, -200, 45, 1, "plains", "");
        db.saveStructure("testworld", s, false);
        assertEquals(0, db.countBuilt("testworld"));
        assertEquals(1, db.countStructures("testworld"));
        assertEquals(1, db.listUnbuilt("testworld").size());
        db.markBuilt("123:village:0");
        assertEquals(1, db.countBuilt("testworld"));
        assertTrue(db.listUnbuilt("testworld").isEmpty());
    }

    @Test
    public void structuresAndRoadsLoadForRecovery() throws Exception {
        db.saveWorld("w2", 7L, 5000, "SMALL", "GENERATING", null);
        PlannedStructure a = new PlannedStructure("7:city:0",
            PlannedStructure.Kind.CITY, "Valoria", 10, 20, 80, 2, "medieval", "");
        PlannedStructure b = new PlannedStructure("7:dungeon:0",
            PlannedStructure.Kind.DUNGEON, "Sunken Crypt", 300, 400, 30, 3, "crypt", "crypt");
        db.saveStructure("w2", a, true);
        db.saveStructure("w2", b, false);
        List<GenerationPersistenceService.StructureRow> rows = db.loadStructures("w2");
        assertEquals(2, rows.size());
        assertTrue(rows.get(0).built);
        assertFalse(rows.get(1).built);
        assertEquals(PlannedStructure.Kind.DUNGEON, rows.get(1).structure.getKind());

        java.util.List<int[]> wps = new java.util.ArrayList<>();
        wps.add(new int[]{0, 0});
        wps.add(new int[]{50, 60});
        wps.add(new int[]{100, 200});
        db.saveRoad("w2", 0, "7:city:0", "7:dungeon:0", "medieval", wps);
        List<GenerationPersistenceService.RoadRow> roads = db.loadRoads("w2");
        assertEquals(1, roads.size());
        assertEquals(3, roads.get(0).waypoints.size());
        assertArrayEquals(new int[]{50, 60}, roads.get(0).waypoints.get(1));
    }

    @Test
    public void safeZoneAndProgressPersist() throws Exception {
        db.saveWorld("w3", 9L, 8000, "LARGE", "GENERATING", null);
        db.saveSafeZone("w3:spawn", "w3", "Spawn", 0, 0, 80, "pvp,mob");
        db.saveProgress("w3", "building", 5, 30, 1);
        // No exception + delete cleans up.
        db.deleteWorld("w3");
        assertEquals(0, db.countStructures("w3"));
        assertTrue(db.loadRoads("w3").isEmpty());
    }
}
