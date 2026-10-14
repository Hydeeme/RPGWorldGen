package com.rpgworldgen.gen;

import org.bukkit.Material;
import org.bukkit.World;

import java.util.Random;

/**
 * Procedural architectural builders (Mode A). All builders only enqueue block ops;
 * they never touch the world directly, so planning stays thread-safe and placement
 * stays budgeted through {@link StructurePlacementEngine}.
 */
public class BuildingEngine {

    private final StructurePlacementEngine engine;

    public BuildingEngine(StructurePlacementEngine engine) {
        this.engine = engine;
    }

    private Material[] pal(String theme) { return engine.palette(theme); }

    /** Place a single themed wall block (used by city walls). */
    public void placeWallBlock(int x, int y, int z, String theme) {
        engine.enqueue(x, y, z, pal(theme)[0]);
    }

    /**
     * Ground Y lookup. If the chunk is already generated (on disk) it is
     * fast-loaded with no stall; if it is ungenerated, background generation
     * is queued and a {@link ChunkNotReadyException} is thrown so the work
     * item is deferred instead of stalling the server thread.
     * Must be called on the server thread.
     */
    public static int groundY(World world, int x, int z) {
        int cx = x >> 4, cz = z >> 4;
        if (!world.isChunkLoaded(cx, cz)) {
            if (!ChunkPreloader.touch(world, cx, cz)) {
                throw new ChunkNotReadyException(cx, cz);
            }
        }
        int y = world.getHighestBlockYAt(x, z);
        return Math.max(world.getMinHeight() + 1, y);
    }

    private void box(int x0, int y0, int z0, int x1, int y1, int z1, Material m) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++)
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++)
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++)
                    engine.enqueue(x, y, z, m);
    }

    private void hollowBox(int x0, int y0, int z0, int x1, int y1, int z1, Material wall) {
        box(x0, y0, z0, x1, y0, z1, wall); // floor handled by caller palette
        for (int y = y0 + 1; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                engine.enqueue(x, y, z0, wall);
                engine.enqueue(x, y, z1, wall);
            }
            for (int z = z0 + 1; z < z1; z++) {
                engine.enqueue(x0, y, z, wall);
                engine.enqueue(x1, y, z, wall);
            }
        }
    }

    /**
     * Build a house with seeded variation (footprint 7-11 x 6-9, wall height 3-4,
     * gabled or flat roof, door on random side, window gaps).
     *
     * @return footprint half-extent used (for spacing callers)
     */
    public int buildHouse(World world, int cx, int cz, String theme, Random rng) {
        Material[] p = pal(theme);
        int gy = groundY(world, cx, cz);
        int w = 7 + rng.nextInt(5);   // 7..11
        int d = 6 + rng.nextInt(4);   // 6..9
        int h = 3 + rng.nextInt(2);   // 3..4
        int x0 = cx - w / 2, x1 = cx + w / 2;
        int z0 = cz - d / 2, z1 = cz + d / 2;
        // foundation + floor
        box(x0 - 1, gy - 1, z0 - 1, x1 + 1, gy - 1, z1 + 1, Material.COBBLESTONE);
        box(x0, gy, z0, x1, gy, z1, p[1]);
        // walls with window gaps
        hollowBox(x0, gy, z0, x1, gy + h, z1, p[0]);
        for (int i = 0; i < w / 2; i++) {
            int wx = x0 + 1 + rng.nextInt(Math.max(1, w - 1));
            engine.enqueue(wx, gy + 2, z0, Material.GLASS_PANE);
            engine.enqueue(wx, gy + 2, z1, Material.GLASS_PANE);
        }
        // door on random side
        int doorSide = rng.nextInt(4);
        int doorX = doorSide == 0 ? cx : doorSide == 1 ? cx : doorSide == 2 ? x0 : x1;
        int doorZ = doorSide == 0 ? z0 : doorSide == 1 ? z1 : cz;
        engine.enqueue(doorX, gy + 1, doorZ, Material.AIR);
        engine.enqueue(doorX, gy + 2, doorZ, Material.AIR);
        engine.enqueue(doorX, gy, doorZ, p[1]);
        // gabled roof
        int ridge = gy + h + 2;
        for (int y = gy + h + 1; y <= ridge; y++) {
            int inset = y - (gy + h + 1);
            box(x0 - inset, y, z0, x1 + inset, y, z1, Material.AIR); // clear interior top
            for (int x = x0 - 1; x <= x1 + 1; x++) {
                engine.enqueue(x, y, z0 - 0 + 0, p[2]); // eaves handled per-row below
            }
            box(x0 - 1, y, z0 - 1, x1 + 1, y, z0 - 1, p[2]);
            box(x0 - 1, y, z1 + 1, x1 + 1, y, z1 + 1, p[2]);
            engine.enqueue(x0 - 1 - 0, y, z0, p[2]);
            engine.enqueue(x1 + 1, y, z0, p[2]);
            engine.enqueue(x0 - 1, y, z1, p[2]);
            engine.enqueue(x1 + 1, y, z1, p[2]);
            // shrink span each layer for gable effect
            x0 += 0; // keep simple stepped roof
        }
        // torches inside
        engine.enqueue(cx, gy + 1, cz, Material.TORCH);
        // bed + crafting table furnishing
        engine.enqueue(x0 + 1, gy + 1, z0 + 1, Material.CRAFTING_TABLE);
        return Math.max(w, d) / 2 + 2;
    }

    /** Watchtower: 5x5 stone base, ladder shaft, crenellated top platform. */
    public void buildTower(World world, int cx, int cz, String theme, int height, Random rng) {
        Material[] p = pal(theme);
        int gy = groundY(world, cx, cz);
        box(cx - 2, gy - 1, cz - 2, cx + 2, gy - 1, cz + 2, Material.COBBLESTONE);
        for (int y = gy; y < gy + height; y++) {
            for (int x = cx - 1; x <= cx + 1; x++)
                for (int z = cz - 1; z <= cz + 1; z++)
                    if (x == cx - 1 || x == cx + 1 || z == cz - 1 || z == cz + 1)
                        engine.enqueue(x, y, z, p[0]);
            engine.enqueue(cx, y, cz, Material.LADDER);
        }
        int top = gy + height;
        box(cx - 2, top, cz - 2, cx + 2, top, cz + 2, p[1]);
        for (int x = cx - 2; x <= cx + 2; x++) {
            engine.enqueue(x, top + 1, cz - 2, p[3]);
            engine.enqueue(x, top + 1, cz + 2, p[3]);
        }
        for (int z = cz - 2; z <= cz + 2; z++) {
            engine.enqueue(cx - 2, top + 1, z, p[3]);
            engine.enqueue(cx + 2, top + 1, z, p[3]);
        }
        engine.enqueue(cx, top + 1, cz, Material.TORCH);
    }

    /** Well: 3x3 water shaft with posts and roof. */
    public void buildWell(World world, int cx, int cz, String theme) {
        Material[] p = pal(theme);
        int gy = groundY(world, cx, cz);
        box(cx - 1, gy, cz - 1, cx + 1, gy + 1, cz + 1, p[0]);
        engine.enqueue(cx, gy, cz, Material.WATER);
        engine.enqueue(cx, gy + 1, cz, Material.AIR);
        for (int[] c : new int[][]{{cx - 1, cz - 1}, {cx + 1, cz - 1}, {cx - 1, cz + 1}, {cx + 1, cz + 1}}) {
            box(c[0], gy + 1, c[1], c[0], gy + 3, c[1], Material.OAK_FENCE);
        }
        box(cx - 1, gy + 4, cz - 1, cx + 1, gy + 4, cz + 1, p[2]);
    }

    /** Farm plot: tilled soil rows with crops, water channel, fence perimeter. */
    public void buildFarm(World world, int cx, int cz, int size, Random rng) {
        int gy = groundY(world, cx, cz);
        int h = size / 2;
        for (int x = cx - h; x <= cx + h; x++) {
            for (int z = cz - h; z <= cz + h; z++) {
                boolean edge = x == cx - h || x == cx + h || z == cz - h || z == cz + h;
                if (edge) {
                    engine.enqueue(x, gy + 1, z, Material.OAK_FENCE);
                } else if (x == cx) {
                    engine.enqueue(x, gy, z, Material.WATER);
                } else {
                    engine.enqueue(x, gy, z, Material.FARMLAND);
                    if (rng.nextDouble() < 0.85) engine.enqueue(x, gy + 1, z, Material.WHEAT);
                }
            }
        }
    }

    /** Road segment: stamp road palette blocks along waypoints, following terrain height. */
    public void buildRoad(World world, java.util.List<int[]> waypoints, String theme) {
        Material road = pal(theme)[4];
        for (int i = 1; i < waypoints.size(); i++) {
            int[] a = waypoints.get(i - 1), b = waypoints.get(i);
            double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
            int steps = Math.max(1, (int) len);
            for (int s = 0; s <= steps; s++) {
                double t = (double) s / steps;
                int x = (int) Math.round(a[0] + (b[0] - a[0]) * t);
                int z = (int) Math.round(a[1] + (b[1] - a[1]) * t);
                int gy = groundY(world, x, z);
                // 3-wide roadbed
                int dx = b[0] - a[0], dz = b[1] - a[1];
                boolean alongX = Math.abs(dx) >= Math.abs(dz);
                for (int w = -1; w <= 1; w++) {
                    int rx = alongX ? x : x + w;
                    int rz = alongX ? z + w : z;
                    // Neighbor columns reuse the spine height: probing them here
                    // would sync-generate fresh chunks mid-tick (watchdog risk).
                    // The drain phase fills ops once chunks exist, so reuse gy.
                    // Bridge only when the neighbor chunk is already loaded and
                    // visibly water; otherwise stamp plain roadbed.
                    boolean neighborLoaded = world.isChunkLoaded(rx >> 4, rz >> 4);
                    boolean water = false;
                    if (neighborLoaded) {
                        water = world.getBlockAt(rx, gy - 1, rz).getType() == Material.WATER
                            || world.getBlockAt(rx, gy, rz).getType() == Material.WATER;
                    }
                    if (water) {
                        box(rx, gy - 3, rz, rx, gy - 3, rz, Material.OAK_LOG);
                        engine.enqueue(rx, gy, rz, Material.OAK_SLAB);
                    } else {
                        engine.enqueue(rx, gy, rz, road);
                        engine.enqueue(rx, gy + 1, rz, Material.AIR);
                        engine.enqueue(rx, gy + 2, rz, Material.AIR);
                    }
                }
            }
        }
    }

    /** Simple market stall: posts, counter, striped awning. */
    public void buildStall(World world, int cx, int cz, Random rng) {
        int gy = groundY(world, cx, cz);
        Material wool = new Material[]{Material.RED_WOOL, Material.BLUE_WOOL,
            Material.YELLOW_WOOL, Material.WHITE_WOOL}[rng.nextInt(4)];
        for (int[] c : new int[][]{{cx - 1, cz - 1}, {cx + 1, cz - 1}, {cx - 1, cz + 1}, {cx + 1, cz + 1}}) {
            box(c[0], gy + 1, c[1], c[0], gy + 2, c[1], Material.OAK_LOG);
        }
        box(cx - 1, gy + 1, cz, cx + 1, gy + 1, cz, Material.OAK_PLANKS);
        box(cx - 2, gy + 3, cz - 2, cx + 2, gy + 3, cz + 2, wool);
        engine.enqueue(cx, gy + 2, cz, Material.CHEST);
    }

    /** Castle keep core for kingdom capitals: curtain approach, keep, courtyard torches. */
    public void buildKeep(World world, int cx, int cz, String theme, Random rng) {
        Material[] p = pal(theme);
        int gy = groundY(world, cx, cz);
        // keep 13x13, height 10
        box(cx - 6, gy - 1, cz - 6, cx + 6, gy - 1, cz + 6, Material.COBBLESTONE);
        for (int y = gy; y < gy + 10; y++) {
            for (int x = cx - 6; x <= cx + 6; x++) {
                engine.enqueue(x, y, cz - 6, p[0]);
                engine.enqueue(x, y, cz + 6, p[0]);
            }
            for (int z = cz - 6; z <= cz + 6; z++) {
                engine.enqueue(cx - 6, y, z, p[0]);
                engine.enqueue(cx + 6, y, z, p[0]);
            }
        }
        // crenellations
        for (int x = cx - 6; x <= cx + 6; x += 2) {
            engine.enqueue(x, gy + 10, cz - 6, p[3]);
            engine.enqueue(x, gy + 10, cz + 6, p[3]);
        }
        for (int z = cz - 6; z <= cz + 6; z += 2) {
            engine.enqueue(cx - 6, gy + 10, z, p[3]);
            engine.enqueue(cx + 6, gy + 10, z, p[3]);
        }
        // gate + torches
        engine.enqueue(cx, gy + 1, cz + 6, Material.AIR);
        engine.enqueue(cx, gy + 2, cz + 6, Material.AIR);
        engine.enqueue(cx - 1, gy + 2, cz + 5, Material.TORCH);
        engine.enqueue(cx + 1, gy + 2, cz + 5, Material.TORCH);
        // corner towers
        for (int[] c : new int[][]{{cx - 8, cz - 8}, {cx + 8, cz - 8}, {cx - 8, cz + 8}, {cx + 8, cz + 8}}) {
            buildTower(world, c[0], c[1], theme, 9, rng);
        }
    }

    /** Dungeon room block-out: floor, cleared interior, walls, themed dressing. */
    public void buildDungeonRoom(World world, int cx, int cy, int cz, int w, int d, int h,
                                 DungeonGraphGenerator.RoomType type, String theme, Random rng) {
        Material[] p = pal(theme);
        Material wall = type == DungeonGraphGenerator.RoomType.BOSS ? Material.OBSIDIAN : p[0];
        int x0 = cx - w / 2, x1 = cx + w / 2;
        int z0 = cz - d / 2, z1 = cz + d / 2;
        box(x0, cy, z0, x1, cy, z1, Material.COBBLESTONE);
        // Excavate the interior: without this the room would remain solid rock.
        for (int x = x0 + 1; x < x1; x++) {
            for (int z = z0 + 1; z < z1; z++) {
                for (int y = cy + 1; y <= cy + h; y++) {
                    engine.enqueue(x, y, z, Material.AIR);
                }
            }
        }
        for (int y = cy + 1; y <= cy + h; y++) {
            for (int x = x0; x <= x1; x++) {
                engine.enqueue(x, y, z0, wall);
                engine.enqueue(x, y, z1, wall);
            }
            for (int z = z0 + 1; z < z1; z++) {
                engine.enqueue(x0, y, z, wall);
                engine.enqueue(x1, y, z, wall);
            }
        }
        box(x0, cy + h + 1, z0, x1, cy + h + 1, z1, Material.COBBLESTONE);
        switch (type) {
            case LOOT:
                engine.enqueue(cx, cy + 1, cz, Material.CHEST);
                engine.enqueue(cx + 1, cy + 1, cz, Material.GOLD_BLOCK);
                break;
            case BOSS:
                engine.enqueue(cx, cy + 1, cz, Material.NETHERITE_BLOCK);
                for (int[] c : new int[][]{{x0 + 1, z0 + 1}, {x1 - 1, z0 + 1}, {x0 + 1, z1 - 1}, {x1 - 1, z1 - 1}})
                    engine.enqueue(c[0], cy + 1, c[1], Material.SOUL_LANTERN);
                break;
            case MOB:
                engine.enqueue(cx, cy + 1, cz, Material.SPAWNER);
                break;
            case TRAP:
                for (int i = 0; i < 3; i++)
                    engine.enqueue(x0 + 1 + rng.nextInt(Math.max(1, w - 2)), cy, z0 + 1 + rng.nextInt(Math.max(1, d - 2)),
                        Material.STONE_PRESSURE_PLATE);
                break;
            default:
                engine.enqueue(cx, cy + 2, cz, Material.TORCH);
                break;
        }
    }

    /**
     * Carve a 3-wide, 4-high corridor between two points at level {@code y}.
     *
     * <p>Enqueue-based (never direct writes): callers must carve AFTER enqueueing
     * rooms so corridor air wins over room walls in FIFO drain order. Runs on the
     * server thread via the scheduler like all other builders.
     */
    public void carveCorridor(int x0, int y, int z0, int x1, int z1) {
        double len = Math.hypot(x1 - x0, z1 - z0);
        int steps = Math.max(1, (int) len);
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            int x = (int) Math.round(x0 + (x1 - x0) * t);
            int z = (int) Math.round(z0 + (z1 - z0) * t);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dy = 0; dy < 4; dy++) {
                        engine.enqueue(x + dx, y + 1 + dy, z + dz, Material.AIR);
                    }
                    engine.enqueue(x + dx, y, z + dz, Material.COBBLESTONE);
                    engine.enqueue(x + dx, y + 5, z + dz, Material.COBBLESTONE);
                }
            }
        }
    }
}
