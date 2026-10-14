# RPGWorldGenerator

Procedural RPG adventure world generator for Spigot/Paper 1.20.4 (Java 17+).

Automatically generates large, playable RPG adventure worlds: villages, cities,
kingdoms with border outposts, room-and-corridor dungeons, wilderness points of
interest, safe zones, terrain-following roads, and tiered progression regions —
across three world-size presets (5,000 / 6,000 / 8,000 blocks).

## Tested compatibility

| Component | Tested | Notes |
|---|---|---|
| Paper 1.20.4 | Yes (compile + unit tests) | Primary target |
| Spigot 1.20.4 | Expected | Same Bukkit API surface; live-server test pending |
| Other 1.20.x | Not claimed | Likely works; not tested |
| Java 17 / 21 | Yes (17 target, 21 build env) | `maven.compiler.source/target = 17` |
| Multiverse-Core | 5.8.2-pre live-verified (Paper 1.21.10); 4.x via command fallback | Native `WorldManager.importWorld` API on generate (idempotent); re-verify anytime with `/rpgworld link <world>` |
| WorldEdit / FAWE | 7.3.10 live-verified (Paper 1.21.10) | Schematic validation + fallback; required by WorldGuard for region mirroring |
| WorldGuard | 7.0.12 live-verified (Paper 1.21.10) | Spawn/capital zones mirrored as cuboid regions with real DENY flags + greet/farewell; deterministic ids (`rpg_<world>_<x>_<z>`); built-in protection always applies |
| Vault / Citizens / PlaceholderAPI | Detected only | No hard logic; placeholders not yet exposed |

Live-verified on Paper 1.21.10 (via PluginRemapper): small world generate
COMPLETE 16/16 with 0 errors, Multiverse import confirmed (`mv list`),
WorldGuard region persisted on disk with 9 DENY flags + greeting/farewell.
Full preset sweep (5000/6000/8000) still pending — see `docs/live-test-plan.md`.

## Quick start

1. Build: `mvn package` → `target/RPGWorldGenerator-1.0.0.jar`.
2. Drop the JAR into `plugins/`, start the server (Paper/Spigot 1.20.4).
3. In-game or console:
   ```
   /rpgworld create Eldoria 6000
   /rpgworld preview Eldoria
   /rpgworld generate Eldoria
   /rpgworld status Eldoria
   ```
4. Generation runs incrementally (one structure + bounded block budget per tick).
   Pause/resume any time: `/rpgworld pause Eldoria`, `/rpgworld resume Eldoria`.

## World sizes

| Preset | Size | Villages | Cities | Kingdoms | Dungeons | Landmarks |
|---|---|---|---|---|---|---|
| Small | 5,000 × 5,000 | 6 | 2 | 1 | 10 | 3 |
| Medium | 6,000 × 6,000 | 9 | 3 | 2 | 14 | 4 |
| Large | 8,000 × 8,000 | 14 | 5 | 3 | 20 | 6 |

Coordinate convention: worlds are centered on block (0, 0); a size-S world spans
-S/2 … +S/2 on X and Z, and the world border is set to match exactly. Custom
sizes 2,000–12,000 (multiples of 100) are accepted.

## Commands & permissions

See `docs/commands.md`. Destructive `regenerate` requires a two-step
`--confirm` within 60 seconds. The plugin never overwrites an existing world
folder.

## Performance

Tune in `config.yml` (`generation.blocks-per-tick`, `max-queue-size`,
`wilderness-budget`, `error-halt-threshold`). Lower `blocks-per-tick` (e.g. 500)
for near-zero gameplay lag during generation; raise it (e.g. 5000+) on an empty
build server. An 8,000 × 8,000 world is tens of thousands of block ops across
~40 structures — expect tens of minutes at default speed, not seconds.

## Limitations (explicit)

- Timed dungeon resets (`reset-policy: timed:<hours>`) are parsed but not yet
  scheduled — use `manual` (current behavior: dungeons persist once built).
- WorldEdit schematic *pasting* is availability-checked but not fully
  implemented (API varies by WE/FAWE version); missing templates always fall
  back to procedural builders with a warning. See `docs/integrations.md`.
- Vault/Citizens/PlaceholderAPI are detected and logged; economy, advanced NPC,
  and placeholder features are not yet wired.
- Terrain suitability sampling applies at build time (flatness/water checks in
  builders); the planner itself does not yet read live chunk data.
- No live-server generation test has been executed in this environment yet.

## Building

```
mvn package
```

Requires Maven 3.8+ and Java 17+. SQLite JDBC is bundled (shaded); Paper API is
`provided`. See `docs/troubleshooting.md` and `CHANGELOG.md`.
