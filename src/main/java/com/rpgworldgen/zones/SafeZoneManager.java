package com.rpgworldgen.zones;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight built-in protection system (used when WorldGuard is absent).
 * Circle-based zones with per-zone flags. All checks are O(zones-in-world)
 * with early-out on distance; safe for event handlers.
 */
public class SafeZoneManager implements Listener {

    public static class Zone {
        public final String id;
        public final String world;
        public final String name;
        public final int cx, cz, radius;
        public final Set<String> flags; // pvp-deny, mob-spawn-deny, break-deny, place-deny, explosion-deny, fire-deny
        public final String enterMessage;
        public final String exitMessage;

        public Zone(String id, String world, String name, int cx, int cz, int radius,
                    Set<String> flags, String enterMessage, String exitMessage) {
            this.id = id; this.world = world; this.name = name;
            this.cx = cx; this.cz = cz; this.radius = radius;
            this.flags = flags; this.enterMessage = enterMessage; this.exitMessage = exitMessage;
        }

        public boolean contains(int x, int z) {
            long dx = (long) x - cx, dz = (long) z - cz;
            return dx * dx + dz * dz <= (long) radius * radius;
        }

        public boolean has(String flag) { return flags.contains(flag); }
    }

    private final Map<String, Zone> zones = new ConcurrentHashMap<>();
    // Track which zone each player is in for enter/exit messages.
    private final Map<String, String> playerZone = new ConcurrentHashMap<>();

    public void addZone(Zone zone) { zones.put(zone.id, zone); }
    public void removeZone(String id) { zones.remove(id); }
    public void clear() { zones.clear(); playerZone.clear(); }
    public Map<String, Zone> getZones() { return zones; }
    public Zone getZone(String id) { return zones.get(id); }

    /** All zones registered for a world (for WorldGuard mirroring / status). */
    public java.util.List<Zone> zonesInWorld(String world) {
        java.util.List<Zone> out = new java.util.ArrayList<>();
        for (Zone zone : zones.values()) {
            if (zone.world.equals(world)) out.add(zone);
        }
        return out;
    }

    public Zone zoneAt(String world, int x, int z) {
        for (Zone zone : zones.values()) {
            if (zone.world.equals(world) && zone.contains(x, z)) return zone;
        }
        return null;
    }

    public boolean isProtected(String world, int x, int z) {
        return zoneAt(world, x, z) != null;
    }

    /** Called by a movement listener task (throttled) to emit enter/exit messages. */
    public void checkMovement(Player player) {
        Location l = player.getLocation();
        Zone zone = zoneAt(l.getWorld().getName(), l.getBlockX(), l.getBlockZ());
        String key = player.getUniqueId().toString();
        String current = zone == null ? null : zone.id;
        String previous = playerZone.get(key);
        if (current == null && previous == null) return;
        if (current != null && current.equals(previous)) return;
        if (previous != null && (current == null || !current.equals(previous))) {
            Zone prev = zones.get(previous);
            if (prev != null && prev.exitMessage != null && !prev.exitMessage.isEmpty()) {
                player.sendMessage(prev.exitMessage.replace("{zone}", prev.name));
            }
        }
        if (current != null) {
            playerZone.put(key, current);
            if (zone.enterMessage != null && !zone.enterMessage.isEmpty()) {
                player.sendMessage(zone.enterMessage.replace("{zone}", zone.name));
            }
        } else {
            playerZone.remove(key);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Zone z = zoneAt(e.getBlock().getWorld().getName(),
            e.getBlock().getX(), e.getBlock().getZ());
        if (z != null && z.has("break-deny") && !e.getPlayer().hasPermission("rpgworld.admin")) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Zone z = zoneAt(e.getBlock().getWorld().getName(),
            e.getBlock().getX(), e.getBlock().getZ());
        if (z != null && z.has("place-deny") && !e.getPlayer().hasPermission("rpgworld.admin")) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Zone z = zoneAt(e.getEntity().getWorld().getName(),
            e.getEntity().getLocation().getBlockX(), e.getEntity().getLocation().getBlockZ());
        if (z != null && z.has("pvp-deny") && e.getDamager() instanceof Player) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMobSpawn(CreatureSpawnEvent e) {
        if (e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CUSTOM) return;
        Zone z = zoneAt(e.getEntity().getWorld().getName(),
            e.getLocation().getBlockX(), e.getLocation().getBlockZ());
        if (z != null && z.has("mob-spawn-deny")) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        Zone z = zoneAt(e.getBlock().getWorld().getName(),
            e.getBlock().getX(), e.getBlock().getZ());
        if (z != null && z.has("explosion-deny")) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        Zone z = zoneAt(e.getEntity().getWorld().getName(),
            e.getLocation().getBlockX(), e.getLocation().getBlockZ());
        if (z != null && z.has("explosion-deny")) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent e) {
        Zone z = zoneAt(e.getBlock().getWorld().getName(),
            e.getBlock().getX(), e.getBlock().getZ());
        if (z != null && z.has("fire-deny")) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockIgnite(BlockIgniteEvent e) {
        Zone z = zoneAt(e.getBlock().getWorld().getName(),
            e.getBlock().getX(), e.getBlock().getZ());
        if (z != null && z.has("fire-deny")) e.setCancelled(true);
    }
}
