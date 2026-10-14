package com.rpgworldgen.gen;

import com.rpgworldgen.model.PlannedStructure;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.Random;

/**
 * Wilderness decoration between settlements: shrines, camps, watchtowers,
 * resource groves. Deliberately sparse — most wilderness stays natural.
 */
public class WildernessGenerator {

    private final BuildingEngine buildings;
    private final StructurePlacementEngine placement;

    public WildernessGenerator(BuildingEngine buildings, StructurePlacementEngine placement) {
        this.buildings = buildings;
        this.placement = placement;
    }

    public enum Feature { SHRINE, CAMP, WATCHTOWER, GROVE, RUIN }

    /** Scatter a budgeted number of wilderness features for a world (server thread). */
    public int decorate(World world, long seed, int size, int budget) {
        Random rng = new Random(seed ^ 0x177D5L);
        int placed = 0;
        int attempts = budget * 4;
        int half = size / 2 - 60;
        for (int i = 0; i < attempts && placed < budget; i++) {
            int x = rng.nextInt(half * 2 + 1) - half;
            int z = rng.nextInt(half * 2 + 1) - half;
            int cx = x >> 4, cz = z >> 4;
            if (!world.isChunkLoaded(cx, cz)) {
                // Fresh-world fast path: queue background gen and skip instead of
                // throwing — decorate() places many candidates per call and the
                // scheduler retries until chunks are ready.
                ChunkPreloader.touch(world, cx, cz);
                continue;
            }
            Feature f = Feature.values()[rng.nextInt(Feature.values().length)];
            try {
                switch (f) {
                    case SHRINE:
                        buildShrine(world, x, z);
                        break;
                    case CAMP:
                        buildCamp(world, x, z, rng);
                        break;
                    case WATCHTOWER:
                        buildings.buildTower(world, x, z, "rustic", 7, rng);
                        break;
                    case GROVE:
                        buildGrove(world, x, z, rng);
                        break;
                    case RUIN:
                        buildRuin(world, x, z, rng);
                        break;
                    default:
                        break;
                }
                placed++;
            } catch (ChunkNotReadyException cnr) {
                ChunkPreloader.touch(world, cnr.getChunkX(), cnr.getChunkZ());
                // Skip bad spots; wilderness must never fail generation.
            } catch (Exception e) {
                // Skip bad spots; wilderness must never fail generation.
            }
        }
        return placed;
    }

    private void buildShrine(World world, int x, int z) {
        int gy = BuildingEngine.groundY(world, x, z);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                placement.enqueue(x + dx, gy, z + dz, Material.STONE_BRICKS);
            }
        }
        placement.enqueue(x, gy + 1, z, Material.ENCHANTING_TABLE);
        for (int[] c : new int[][]{{x - 2, z - 2}, {x + 2, z - 2}, {x - 2, z + 2}, {x + 2, z + 2}}) {
            placement.enqueue(c[0], gy + 1, c[1], Material.SOUL_LANTERN);
        }
    }

    private void buildCamp(World world, int x, int z, Random rng) {
        int gy = BuildingEngine.groundY(world, x, z);
        placement.enqueue(x, gy, z, Material.CAMPFIRE);
        for (int i = 0; i < 3; i++) {
            int lx = x + rng.nextInt(7) - 3;
            int lz = z + rng.nextInt(7) - 3;
            placement.enqueue(lx, BuildingEngine.groundY(world, lx, lz), lz, Material.OAK_LOG);
        }
        placement.enqueue(x + 2, gy, z + 2, Material.CHEST);
    }

    private void buildGrove(World world, int x, int z, Random rng) {
        // All block-placeable; SWEET_BERRY_BUSH is not a block in some API
        // versions and tripped "Provided material must be a block" live.
        // enqueue() also drops non-blocks defensively — never fail wilderness.
        Material[] flowers = {Material.POPPY, Material.DANDELION, Material.OXEYE_DAISY,
            Material.CORNFLOWER, Material.ALLIUM};
        for (int i = 0; i < 5; i++) {
            int tx = x + rng.nextInt(17) - 8;
            int tz = z + rng.nextInt(17) - 8;
            int ty = BuildingEngine.groundY(world, tx, tz);
            placement.enqueue(tx, ty, tz, Material.OAK_SAPLING);
            placement.enqueue(tx + 1, BuildingEngine.groundY(world, tx + 1, tz), tz,
                flowers[rng.nextInt(flowers.length)]);
        }
    }

    private void buildRuin(World world, int x, int z, Random rng) {
        int gy = BuildingEngine.groundY(world, x, z);
        int w = 5 + rng.nextInt(4);
        for (int dx = 0; dx < w; dx++) {
            placement.enqueue(x + dx, gy, z, Material.MOSSY_COBBLESTONE);
            if (rng.nextDouble() < 0.6) placement.enqueue(x + dx, gy + 1, z, Material.MOSSY_COBBLESTONE);
            if (rng.nextDouble() < 0.3) placement.enqueue(x + dx, gy + 2, z, Material.MOSSY_COBBLESTONE);
        }
        placement.enqueue(x + w / 2, gy + 1, z + 1, Material.CHEST);
    }

    /** Landmark builder for plan LANDMARK entries (stone circle / beacon). */
    public void buildLandmark(World world, PlannedStructure s, long seed) {
        Random rng = new Random(seed ^ s.getId().hashCode());
        int cx = s.getX(), cz = s.getZ();
        int gy = BuildingEngine.groundY(world, cx, cz);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * 2 * i / 8;
            int px = cx + (int) (Math.cos(a) * 6);
            int pz = cz + (int) (Math.sin(a) * 6);
            int py = BuildingEngine.groundY(world, px, pz);
            for (int y = py; y < py + 3 + rng.nextInt(2); y++) {
                placement.enqueue(px, y, pz, Material.STONE_BRICKS);
            }
        }
        placement.enqueue(cx, gy + 1, cz, Material.BEACON);
    }
}
