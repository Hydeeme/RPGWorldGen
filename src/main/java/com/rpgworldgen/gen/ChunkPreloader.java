package com.rpgworldgen.gen;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Non-blocking chunk preparation for the generation pipeline.
 *
 * <p>Builders must never trigger synchronous chunk <em>generation</em> on the server
 * thread: on a fresh world each ungenerated chunk can stall the tick and trip the
 * server watchdog (observed live on Paper 1.21.10: {@code getHighestBlockYAt} inside
 * the scheduler tick blocked on terrain gen). Instead every build step pre-touches
 * its footprint via {@link #touch}: already-generated chunks load fast from disk,
 * ungenerated ones are queued for background generation and the work item is
 * deferred to a later tick.
 *
 * <p>Background requests alone are not enough: an async-loaded chunk with no
 * ticket unloads immediately, so polling {@code isChunkLoaded} never converges
 * (observed live: job stuck at 0/25 in "waiting-for-chunks" forever). Every
 * touch therefore also pins the chunk with a plugin ticket, keeping it loaded
 * until the work is done. Tickets are LRU-bounded ({@value #MAX_TICKETS}) and
 * released via {@link #releaseAll}.
 *
 * <p>The async API ({@code World.getChunkAtAsync}) is Paper-only, so it is probed
 * reflectively on the {@link World} interface (robust across CraftBukkit/
 * remapped runtimes): on Spigot runtimes the scheduler falls back to a bounded
 * number of synchronous generations per tick instead of stalling.
 * Chunk-set math ({@link #chunksForCircle}, {@link #chunksForPath}) is pure Java
 * and unit-tested; only {@link #touch} needs a live server.
 */
public final class ChunkPreloader {

    private ChunkPreloader() {}

    /** Owning plugin for chunk tickets; set once at enable. Until set, pinning is a no-op. */
    private static volatile Plugin owner;

    private static final Map<String, Method> asyncMethods = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> asyncSupport = new ConcurrentHashMap<>();

    /** Max pinned chunks; LRU-evicted to bound memory (a kingdom footprint is ~200). */
    static final int MAX_TICKETS = 300;
    /** Access-order LRU: key "world,cx,cz" -> ticket. Server thread only. */
    private static final LinkedHashMap<String, Ticket> tickets =
        new LinkedHashMap<>(512, 0.75f, true);

    private static final class Ticket {
        final String world;
        final int cx, cz;
        Ticket(String world, int cx, int cz) { this.world = world; this.cx = cx; this.cz = cz; }
    }

    /** Register the owning plugin (call once at enable). */
    public static void init(Plugin plugin) { owner = plugin; }

    /** True if this world exposes an async chunk API (Paper). Reflective, cached. */
    public static boolean hasAsync(World world) {
        return asyncSupport.computeIfAbsent(world.getClass().getName(), cls -> {
            try {
                // Probe the interface, not the impl class: robust across remaps.
                Method m = World.class.getMethod("getChunkAtAsync", int.class, int.class);
                asyncMethods.put(cls, m);
                return true;
            } catch (NoSuchMethodException e) {
                return false;
            }
        });
    }

    /**
     * Returns true if the chunk is loaded and safe to query synchronously.
     * Otherwise pins it if loaded, queues background generation (when
     * supported), fast-loads it when already generated, and returns whether
     * it ended up loaded — callers must still defer their work item on false,
     * never block. Never triggers synchronous <em>generation</em> itself.
     *
     * <p>On async runtimes the background future pins the chunk on completion:
     * ticket-less async results can unload before the next poll, so without
     * this the core-ready gate never converges (observed live: stuck at 0/25).
     * The completion callback runs off-thread, so it only issues the ticket
     * via the sync scheduler — never touching chunk state directly.
     */
    public static boolean touch(World world, int cx, int cz) {
        Plugin p = owner;
        if (world.isChunkLoaded(cx, cz)) { pin(world, cx, cz); return true; }
        if (hasAsync(world) && p != null && p.isEnabled()) {
            try {
                // CompletableFuture<Chunk>: generation runs off-thread; the
                // follow-up pins the result back on the server thread.
                Object fut = asyncMethods.get(world.getClass().getName()).invoke(world, cx, cz);
                if (fut instanceof CompletableFuture) {
                    String wname = world.getName();
                    ((CompletableFuture<?>) fut).thenAccept(chunk -> {
                        try {
                            if (p.isEnabled()) {
                                p.getServer().getScheduler().runTask(p, () -> {
                                    World w = p.getServer().getWorld(wname);
                                    if (w != null) pin(w, cx, cz);
                                });
                            }
                        } catch (Exception ignored) {
                            // Best effort; next touch retries.
                        }
                    });
                }
            } catch (Exception ignored) {
                // Fall through to the generated-check below.
            }
        }
        if (world.isChunkGenerated(cx, cz)) {
            world.getChunkAt(cx, cz); // fast path: load from disk, no terrain gen
            if (world.isChunkLoaded(cx, cz)) { pin(world, cx, cz); return true; }
        }
        return false;
    }

    /** Bounded synchronous generation fallback (Spigot path, or already-generated). */
    public static void generateSync(World world, int cx, int cz) {
        pin(world, cx, cz);
        world.getChunkAt(cx, cz);
    }

    /**
     * Pin a loaded chunk with a plugin ticket so background generation results
     * stay loaded until the work is done. ONLY pins already-loaded chunks:
     * {@code addPluginChunkTicket} synchronously <em>loads</em> ungenerated
     * chunks on the calling thread (observed live: 10s watchdog stall), so it
     * must never be called for an unloaded chunk on the tick thread. Unloaded
     * chunks are handled by async futures ({@link #touch}) instead.
     * LRU-bounded to {@value #MAX_TICKETS}; eviction releases the eldest ticket.
     * No-op until {@link #init} runs at enable.
     */
    static void pin(World world, int cx, int cz) {
        Plugin p = owner;
        if (p == null || !p.isEnabled()) return;
        if (!world.isChunkLoaded(cx, cz)) return; // never sync-load here
        String key = world.getName() + "," + cx + "," + cz;
        Ticket existing = tickets.get(key);
        if (existing != null) return; // access-order touch refreshes LRU
        try {
            world.addPluginChunkTicket(cx, cz, p);
        } catch (Exception ignored) {
            return; // tickets unavailable (e.g. disabled world); rely on futures
        }
        tickets.put(key, new Ticket(world.getName(), cx, cz));
        evict(world);
    }

    private static void evict(World current) {
        while (tickets.size() > MAX_TICKETS) {
            Iterator<Map.Entry<String, Ticket>> it = tickets.entrySet().iterator();
            if (!it.hasNext()) break;
            Map.Entry<String, Ticket> eldest = it.next();
            it.remove();
            releaseTicket(current, eldest.getValue());
        }
    }

    private static void releaseTicket(World current, Ticket t) {
        try {
            World w = current != null && current.getName().equals(t.world) ? current
                : Bukkit.getWorld(t.world);
            Plugin p = owner;
            if (w != null && p != null) w.removePluginChunkTicket(t.cx, t.cz, p);
        } catch (Exception ignored) {
            // Best effort; stale tickets expire with the plugin.
        }
    }

    /** Release all pinned tickets (job complete / disable). Keeps DB state intact. */
    public static void releaseAll() {
        try {
            Plugin p = owner;
            for (Ticket t : tickets.values()) {
                try {
                    World w = Bukkit.getWorld(t.world);
                    if (w != null && p != null) w.removePluginChunkTicket(t.cx, t.cz, p);
                } catch (Exception ignored) {
                    // Continue releasing the rest.
                }
            }
        } finally {
            tickets.clear();
        }
    }

    /** Chunk coordinates covering a circle of center chunk + radius (in chunks). */
    public static List<int[]> chunksForCircle(int centerCx, int centerCz, int radiusChunks) {
        Set<String> seen = new LinkedHashSet<>();
        List<int[]> out = new ArrayList<>();
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                if (dx * dx + dz * dz > (radiusChunks + 0.5) * (radiusChunks + 0.5)) continue;
                String key = (centerCx + dx) + "," + (centerCz + dz);
                if (seen.add(key)) out.add(new int[]{centerCx + dx, centerCz + dz});
            }
        }
        return out;
    }

    /** Chunk coordinates along a waypoint polyline, plus a chunk margin. Pure math. */
    public static List<int[]> chunksForPath(List<int[]> waypoints, int margin) {
        Set<String> seen = new LinkedHashSet<>();
        List<int[]> out = new ArrayList<>();
        for (int i = 1; i < waypoints.size(); i++) {
            int[] a = waypoints.get(i - 1), b = waypoints.get(i);
            double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
            int steps = Math.max(1, (int) (len / 8));
            for (int s = 0; s <= steps; s++) {
                double t = (double) s / steps;
                int x = (int) Math.round(a[0] + (b[0] - a[0]) * t);
                int z = (int) Math.round(a[1] + (b[1] - a[1]) * t);
                int ccx = x >> 4, ccz = z >> 4;
                for (int dx = -margin; dx <= margin; dx++) {
                    for (int dz = -margin; dz <= margin; dz++) {
                        String key = (ccx + dx) + "," + (ccz + dz);
                        if (seen.add(key)) out.add(new int[]{ccx + dx, ccz + dz});
                    }
                }
            }
        }
        return out;
    }
}
