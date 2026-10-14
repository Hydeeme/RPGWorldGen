package com.rpgworldgen.gen;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/**
 * Procedural room-and-corridor dungeon layout generator.
 *
 * <p>Guarantees: every room is reachable from the entrance (spanning-tree corridors
 * plus optional loop branches), there is exactly one boss room (last room), one
 * loot room, and optional secret rooms. Pure Java, fully unit-testable.
 */
public class DungeonGraphGenerator {

    public static class Room {
        public final int id;
        public final int x, z, w, d; // grid coords + size
        public final RoomType type;
        Room(int id, int x, int z, int w, int d, RoomType type) {
            this.id = id; this.x = x; this.z = z; this.w = w; this.d = d; this.type = type;
        }
    }

    public enum RoomType { ENTRANCE, NORMAL, MOB, TRAP, LOOT, SECRET, BOSS }

    public static class Layout {
        public final List<Room> rooms = new ArrayList<>();
        public final List<int[]> corridors = new ArrayList<>(); // [roomA, roomB]
        public int difficulty;
        public String dungeonType;
    }

    /**
     * Generate a connected layout.
     *
     * @param rng        seeded random
     * @param roomCount  total rooms incl. entrance and boss (min 4)
     * @param difficulty 1..5 difficulty rating
     */
    public Layout generate(Random rng, int roomCount, int difficulty, String dungeonType) {
        if (roomCount < 4) throw new IllegalArgumentException("roomCount must be >= 4");
        Layout layout = new Layout();
        layout.difficulty = difficulty;
        layout.dungeonType = dungeonType;

        // Place rooms on a coarse grid with random walk expansion to avoid overlap.
        int gridR = (int) Math.ceil(Math.sqrt(roomCount)) + 2;
        boolean[][] occupied = new boolean[gridR * 2][gridR * 2];
        int gx = gridR, gz = gridR;
        int[][] cells = new int[roomCount][2];
        cells[0][0] = gx; cells[0][1] = gz;
        occupied[gx][gz] = true;
        int placed = 1;
        int guard = 0;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (placed < roomCount && guard++ < roomCount * 200) {
            int[] from = cells[rng.nextInt(placed)];
            int[] dir = dirs[rng.nextInt(4)];
            int nx = from[0] + dir[0], nz = from[1] + dir[1];
            if (nx < 0 || nz < 0 || nx >= gridR * 2 || nz >= gridR * 2 || occupied[nx][nz]) continue;
            occupied[nx][nz] = true;
            cells[placed][0] = nx; cells[placed][1] = nz;
            placed++;
        }
        // Fallback: fill any remaining by scanning for free cells adjacent to placed ones.
        for (int i = placed; i < roomCount; i++) {
            boolean done = false;
            for (int x = 0; x < gridR * 2 && !done; x++) {
                for (int z = 0; z < gridR * 2 && !done; z++) {
                    if (!occupied[x][z]) { occupied[x][z] = true; cells[i][0] = x; cells[i][1] = z; done = true; }
                }
            }
            if (!done) throw new IllegalStateException("Dungeon grid exhausted");
        }

        for (int i = 0; i < roomCount; i++) {
            RoomType t;
            if (i == 0) t = RoomType.ENTRANCE;
            else if (i == roomCount - 1) t = RoomType.BOSS;
            else if (i == roomCount - 2) t = RoomType.LOOT;
            else {
                double r = rng.nextDouble();
                if (r < 0.35) t = RoomType.MOB;
                else if (r < 0.50) t = RoomType.TRAP;
                else if (r < 0.58 && roomCount >= 6) t = RoomType.SECRET;
                else t = RoomType.NORMAL;
            }
            int w = 7 + rng.nextInt(6);
            int d = 7 + rng.nextInt(6);
            layout.rooms.add(new Room(i, cells[i][0], cells[i][1], w, d, t));
        }

        // Spanning tree over grid adjacency => guaranteed connectivity.
        boolean[] connected = new boolean[roomCount];
        connected[0] = true;
        List<Integer> frontier = new ArrayList<>();
        frontier.add(0);
        boolean[][] adjacent = new boolean[roomCount][roomCount];
        for (int i = 0; i < roomCount; i++) {
            for (int j = i + 1; j < roomCount; j++) {
                int mdist = Math.abs(cells[i][0] - cells[j][0]) + Math.abs(cells[i][1] - cells[j][1]);
                if (mdist == 1) { adjacent[i][j] = adjacent[j][i] = true; }
            }
        }
        int connectedCount = 1;
        while (connectedCount < roomCount) {
            List<int[]> candidates = new ArrayList<>();
            for (int i = 0; i < roomCount; i++) {
                if (!connected[i]) continue;
                for (int j = 0; j < roomCount; j++) {
                    if (connected[j] || !adjacent[i][j]) continue;
                    candidates.add(new int[]{i, j});
                }
            }
            if (candidates.isEmpty()) {
                // Grid disconnected (fallback cells): connect nearest unconnected room.
                int bestA = -1, bestB = -1, bestD = Integer.MAX_VALUE;
                for (int i = 0; i < roomCount; i++) {
                    if (!connected[i]) continue;
                    for (int j = 0; j < roomCount; j++) {
                        if (connected[j]) continue;
                        int dd = Math.abs(cells[i][0] - cells[j][0]) + Math.abs(cells[i][1] - cells[j][1]);
                        if (dd < bestD) { bestD = dd; bestA = i; bestB = j; }
                    }
                }
                layout.corridors.add(new int[]{bestA, bestB});
                connected[bestB] = true;
                connectedCount++;
            } else {
                int[] pick = candidates.get(rng.nextInt(candidates.size()));
                layout.corridors.add(pick);
                if (!connected[pick[1]]) { connected[pick[1]] = true; connectedCount++; }
            }
        }
        // Optional loop branches (extra corridors between adjacent rooms).
        for (int i = 0; i < roomCount; i++) {
            for (int j = i + 1; j < roomCount; j++) {
                if (adjacent[i][j] && rng.nextDouble() < 0.15) {
                    layout.corridors.add(new int[]{i, j});
                }
            }
        }
        return layout;
    }

    /** Verify every room is reachable from room 0. Returns unreachable room ids. */
    public List<Integer> findUnreachable(Layout layout) {
        boolean[] seen = new boolean[layout.rooms.size()];
        Deque<Integer> q = new ArrayDeque<>();
        q.add(0); seen[0] = true;
        List<List<Integer>> adj = new ArrayList<>();
        for (int i = 0; i < layout.rooms.size(); i++) adj.add(new ArrayList<>());
        for (int[] c : layout.corridors) { adj.get(c[0]).add(c[1]); adj.get(c[1]).add(c[0]); }
        while (!q.isEmpty()) {
            int cur = q.poll();
            for (int n : adj.get(cur)) {
                if (!seen[n]) { seen[n] = true; q.add(n); }
            }
        }
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < seen.length; i++) if (!seen[i]) missing.add(i);
        return missing;
    }
}
