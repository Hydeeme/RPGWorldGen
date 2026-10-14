package com.rpgworldgen.gen;

import com.rpgworldgen.model.PlannedStructure;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Villager;

import java.util.Random;

/**
 * Builds villages, cities, and kingdom supporting settlements from a plan entry.
 * All block writes go through the placement engine queue (budgeted per tick).
 */
public class SettlementGenerator {

    private final BuildingEngine buildings;

    public SettlementGenerator(BuildingEngine buildings) {
        this.buildings = buildings;
    }

    public void buildVillage(World world, PlannedStructure s, long seed) {
        Random rng = new Random(seed ^ s.getId().hashCode());
        String theme = s.getTheme();
        int cx = s.getX(), cz = s.getZ();
        // central plaza + well
        buildings.buildWell(world, cx, cz, theme);
        // ring of houses (seeded count 5-9)
        int houses = 5 + rng.nextInt(5);
        for (int i = 0; i < houses; i++) {
            double ang = (Math.PI * 2 * i) / houses + rng.nextDouble() * 0.5;
            int hx = cx + (int) (Math.cos(ang) * (14 + rng.nextInt(10)));
            int hz = cz + (int) (Math.sin(ang) * (14 + rng.nextInt(10)));
            buildings.buildHouse(world, hx, hz, theme, rng);
        }
        // farms + pens
        buildings.buildFarm(world, cx + 22, cz + 6, 9, rng);
        buildings.buildFarm(world, cx - 22, cz - 4, 9, rng);
        // guard post + inn (large house variant)
        buildings.buildTower(world, cx + 10, cz - 16, theme, 7, rng);
        buildings.buildHouse(world, cx - 12, cz + 14, theme, rng);
        // villager spawn points (sync: called on server thread by scheduler)
        try {
            for (int i = 0; i < 4; i++) {
                int vx = cx + rng.nextInt(21) - 10;
                int vz = cz + rng.nextInt(21) - 10;
                int vy = BuildingEngine.groundY(world, vx, vz) + 1;
                Villager v = (Villager) world.spawnEntity(
                    new org.bukkit.Location(world, vx + 0.5, vy, vz + 0.5), EntityType.VILLAGER);
                v.setProfession(Villager.Profession.values()[rng.nextInt(Villager.Profession.values().length)]);
            }
        } catch (com.rpgworldgen.gen.ChunkNotReadyException cnr) {
            // Propagate unwrapped so the scheduler defers instead of counting an error.
            throw cnr;
        } catch (Exception e) {
            // Entity spawning must not fail the whole structure; logged by caller.
            throw new RuntimeException("villager spawn failed: " + e.getMessage(), e);
        }
    }

    public void buildCity(World world, PlannedStructure s, long seed, boolean walled) {
        Random rng = new Random(seed ^ s.getId().hashCode());
        String theme = s.getTheme();
        int cx = s.getX(), cz = s.getZ();
        // town hall (large keep-lite) at center-north, plaza + fountain south
        buildings.buildHouse(world, cx, cz - 20, theme, rng);
        buildings.buildWell(world, cx, cz, theme);
        // districts: residential ring, commercial row, market stalls
        for (int i = 0; i < 10; i++) {
            double ang = (Math.PI * 2 * i) / 10 + rng.nextDouble() * 0.3;
            int hx = cx + (int) (Math.cos(ang) * (26 + rng.nextInt(14)));
            int hz = cz + (int) (Math.sin(ang) * (26 + rng.nextInt(14)));
            buildings.buildHouse(world, hx, hz, theme, rng);
        }
        for (int i = -2; i <= 2; i++) {
            buildings.buildStall(world, cx + i * 6, cz + 12, rng);
        }
        // blacksmith + warehouse + barracks
        buildings.buildHouse(world, cx + 24, cz - 8, theme, rng);
        buildings.buildHouse(world, cx - 24, cz - 8, theme, rng);
        buildings.buildTower(world, cx + 30, cz + 20, theme, 9, rng);
        buildings.buildTower(world, cx - 30, cz + 20, theme, 9, rng);
        // walls with gates (approximate circle, gap at gates)
        if (walled) {
            int r = 52;
            for (int a = 0; a < 360; a += 2) {
                // gate gaps at 0/90/180/270 deg (+/- 4 deg)
                int mod = a % 90;
                if (mod < 4 || mod > 86) continue;
                double rad = Math.toRadians(a);
                int wx = cx + (int) (Math.cos(rad) * r);
                int wz = cz + (int) (Math.sin(rad) * r);
                int wy = BuildingEngine.groundY(world, wx, wz);
                for (int y = wy; y < wy + 4; y++) {
                    buildings.placeWallBlock(wx, y, wz, theme);
                }
            }
        }
    }

    public void buildKingdom(World world, PlannedStructure s, long seed) {
        Random rng = new Random(seed ^ s.getId().hashCode());
        String theme = s.getTheme();
        int cx = s.getX(), cz = s.getZ();
        // capital keep + courtyard
        buildings.buildKeep(world, cx, cz, theme, rng);
        // supporting ring: houses, farms, barracks towers, border outposts
        for (int i = 0; i < 8; i++) {
            double ang = (Math.PI * 2 * i) / 8;
            int hx = cx + (int) (Math.cos(ang) * 40);
            int hz = cz + (int) (Math.sin(ang) * 40);
            buildings.buildHouse(world, hx, hz, theme, rng);
        }
        buildings.buildFarm(world, cx + 55, cz + 20, 11, rng);
        buildings.buildFarm(world, cx - 55, cz - 20, 11, rng);
        for (int i = 0; i < 4; i++) {
            double ang = Math.PI / 4 + (Math.PI * 2 * i) / 4;
            buildings.buildTower(world, cx + (int) (Math.cos(ang) * 85),
                cz + (int) (Math.sin(ang) * 85), theme, 8, rng);
        }
    }
}
