package com.rpgworldgen.gen;

import com.rpgworldgen.model.PlannedStructure;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Random;

/**
 * Builds dungeon instances from {@link DungeonGraphGenerator} layouts.
 * Underground rooms are excavated into terrain (or buried with stone shell when
 * terrain is too low), with entrance stairwell, corridors, loot chests, mob
 * spawners, traps, and a boss chamber.
 */
public class DungeonBuilder {

    private final BuildingEngine buildings;
    private final StructurePlacementEngine placement;
    private final DungeonGraphGenerator graphs = new DungeonGraphGenerator();

    // Loot tables per loot tier (1..5). Vanilla items only (no RPG-plugin dependency).
    private static final Material[][] LOOT = {
        {Material.IRON_INGOT, Material.BREAD, Material.COAL, Material.LEATHER},
        {Material.IRON_INGOT, Material.GOLD_INGOT, Material.BOW, Material.ARROW},
        {Material.DIAMOND, Material.GOLD_INGOT, Material.ENDER_PEARL, Material.CROSSBOW},
        {Material.DIAMOND, Material.EMERALD, Material.NETHERITE_SCRAP, Material.TOTEM_OF_UNDYING},
        {Material.NETHERITE_INGOT, Material.EMERALD_BLOCK, Material.TOTEM_OF_UNDYING, Material.NETHERITE_SWORD},
    };

    public DungeonBuilder(BuildingEngine buildings, StructurePlacementEngine placement) {
        this.buildings = buildings;
        this.placement = placement;
    }

    public void buildDungeon(World world, PlannedStructure s, long seed) {
        Random rng = new Random(seed ^ s.getId().hashCode());
        int roomCount = 6 + Math.min(6, s.getTier() * 2); // 8..16 rooms by tier
        DungeonGraphGenerator.Layout layout = graphs.generate(rng, roomCount, s.getTier(), s.getExtra());

        int baseX = s.getX(), baseZ = s.getZ();
        int surfY = BuildingEngine.groundY(world, baseX, baseZ);
        int floorY = Math.max(world.getMinHeight() + 2, surfY - 12); // dungeon level below surface
        int spacing = 14;

        // Rooms first (enqueue order), corridors after so corridor air wins in FIFO drain.
        for (DungeonGraphGenerator.Room r : layout.rooms) {
            int cx = baseX + r.x * spacing;
            int cz = baseZ + r.z * spacing;
            buildings.buildDungeonRoom(world, cx, floorY, cz, r.w, r.d, 5, r.type, "dwarven", rng);
        }
        // Corridors: carve 3-wide, 4-high tunnels between room centers.
        for (int[] c : layout.corridors) {
            DungeonGraphGenerator.Room a = layout.rooms.get(c[0]);
            DungeonGraphGenerator.Room b = layout.rooms.get(c[1]);
            buildings.carveCorridor(baseX + a.x * spacing, floorY, baseZ + a.z * spacing,
                baseX + b.x * spacing, baseZ + b.z * spacing);
        }
        // Entrance shaft: connects the surface directly above room 0 down to that room,
        // so the player can always walk in (no disconnected stairwell).
        DungeonGraphGenerator.Room entrance = layout.rooms.get(0);
        int ex = baseX + entrance.x * spacing;
        int ez = baseZ + entrance.z * spacing;
        buildEntranceShaft(ex, surfY, ez, floorY);
        // Fill loot chests (deferred: chests must exist as tile entities; scheduler
        // calls fillLootChests after placement queue drains — see GenerationTaskScheduler).
        pendingLoot.add(new LootJob(world.getName(), baseX, floorY, baseZ, spacing, layout, s.getTier()));
    }

    private void buildEntranceShaft(int x, int surfY, int z, int floorY) {
        // 3x3 shaft: ring walls, hollow center, ladder on one side, arch cap at top.
        for (int y = floorY + 1; y <= surfY + 3; y++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    boolean center = dx == 0 && dz == 0;
                    if (center) {
                        placement.enqueue(x, y, z, Material.AIR);
                    } else {
                        placement.enqueue(x + dx, y, z + dz, Material.MOSSY_COBBLESTONE);
                    }
                }
            }
            placement.enqueue(x + 1, y, z, Material.LADDER);
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.abs(dx) == 2 || Math.abs(dz) == 2) {
                    placement.enqueue(x + dx, surfY + 4, z + dz, Material.COBBLESTONE);
                }
            }
        }
        placement.enqueue(x, surfY + 5, z, Material.TORCH);
    }

    // ---- deferred loot filling ----

    public static class LootJob {
        final String world;
        final int baseX, floorY, baseZ, spacing, tier;
        final DungeonGraphGenerator.Layout layout;
        LootJob(String world, int baseX, int floorY, int baseZ, int spacing,
                DungeonGraphGenerator.Layout layout, int tier) {
            this.world = world; this.baseX = baseX; this.floorY = floorY; this.baseZ = baseZ;
            this.spacing = spacing; this.layout = layout; this.tier = tier;
        }
    }

    private final java.util.Queue<LootJob> pendingLoot = new java.util.ArrayDeque<>();

    /** Fill loot chests for completed dungeons. Runs on server thread. */
    public int drainLoot(org.bukkit.Server server, int maxJobs) {
        int done = 0;
        java.util.Queue<LootJob> deferred = null;
        while (done < maxJobs) {
            LootJob job = pendingLoot.poll();
            if (job == null) break;
            World w = server.getWorld(job.world);
            if (w == null) continue;
            // Chunk guard: chests must exist as tile entities; if the chunk is not
            // loaded yet, queue background generation and retry on a later tick
            // instead of blocking the server thread.
            boolean ready = true;
            for (DungeonGraphGenerator.Room r : job.layout.rooms) {
                if (r.type != DungeonGraphGenerator.RoomType.LOOT
                    && r.type != DungeonGraphGenerator.RoomType.BOSS) continue;
                int cx = job.baseX + r.x * job.spacing;
                int cz = job.baseZ + r.z * job.spacing;
                if (!w.isChunkLoaded(cx >> 4, cz >> 4)) {
                    ChunkPreloader.touch(w, cx >> 4, cz >> 4);
                    ready = false;
                    break;
                }
            }
            if (!ready) {
                if (deferred == null) deferred = new java.util.ArrayDeque<>();
                deferred.add(job);
                continue;
            }
            Random rng = new Random(job.baseX * 31L + job.baseZ);
            Material[] table = LOOT[Math.max(0, Math.min(4, job.tier - 1))];
            for (DungeonGraphGenerator.Room r : job.layout.rooms) {
                if (r.type != DungeonGraphGenerator.RoomType.LOOT
                    && r.type != DungeonGraphGenerator.RoomType.BOSS) continue;
                int cx = job.baseX + r.x * job.spacing;
                int cz = job.baseZ + r.z * job.spacing;
                Block b = w.getBlockAt(cx, job.floorY + 1, cz);
                if (b.getState() instanceof Chest) {
                    Chest chest = (Chest) b.getState();
                    int items = 3 + rng.nextInt(4) + job.tier;
                    for (int i = 0; i < items; i++) {
                        Material m = table[rng.nextInt(table.length)];
                        chest.getInventory().setItem(rng.nextInt(chest.getInventory().getSize()),
                            new ItemStack(m, 1 + rng.nextInt(3)));
                    }
                    chest.update();
                }
            }
            done++;
        }
        if (deferred != null && !deferred.isEmpty()) {
            // Re-queue unready loot jobs for a later tick (chunks still generating).
            pendingLoot.addAll(deferred);
        }
        return done;
    }

    public List<LootJob> getPendingLoot() { return new java.util.ArrayList<>(pendingLoot); }
}
