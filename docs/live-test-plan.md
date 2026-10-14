# Live Server Test Plan

Unit tests (`mvn test`, 44 tests) cover: presets, borders, seed determinism,
overlap detection, region mapping, road routing, dungeon connectivity, config
validation, permissions, persistence round-trips, safe-zone math, schematic
rotation, integration id mapping (WorldGuard region ids, WG deny flags,
Multiverse import command).

## Setup

- Clean Paper 1.20.4 server, Java 17, plugin JAR installed, no other plugins.
- Run each case on all three presets (5000 / 6000 / 8000).

## Cases

1. **Create**: `/rpgworld create T1 5000`, `T2 6000`, `T3 8000` → worlds exist,
   border size matches, spawn set, `worlds.yml` + DB rows written.
2. **Preview**: `/rpgworld preview <w>` → counts match preset table; no stack traces.
3. **Generate**: `/rpgworld generate <w>` → completes with 0 errors in status;
   console shows structure/road/wilderness phases.
4. **Boundaries**: teleport to (±half, ±half) corners; all settlement centers +
   radii inside the border (`regions` output vs border).
5. **Settlements**: visit 1 village, 1 city, 1 capital per world — houses,
   farms, walls/gates, keep present; villagers spawned.
6. **Dungeons**: `/rpgworld locate <w> dungeon` → enter 2 dungeons; walk from
   entrance shaft to boss room without breaking blocks; loot chests filled.
7. **Roads**: follow one village→city road; no 10+ block cliff gaps; bridges
   over water.
8. **Safe zones**: non-admin cannot break/place in spawn; PvP blocked; mobs
   don't spawn; enter/exit messages appear.
9. **Pause/resume**: pause mid-generation, restart server, confirm auto-resume
   and completion.
10. **Regenerate**: `regenerate <w> <name>` + `--confirm` rebuilds only matches.
11. **Integrations**: install Multiverse-Core → world registered; install
    WorldGuard → regions mirrored; remove both → core unaffected.
12. **Performance**: record ticks, timings, and wall-clock per preset at default
    budget; note in release notes.

## Sign-off

Record server version, Java version, timings, and any failures. Do not claim
release readiness until all 12 cases pass on all 3 presets.
