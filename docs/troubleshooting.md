# Troubleshooting

## Plugin won't enable

- `Database init failed` → check `plugins/RPGWorldGenerator/` is writable;
  delete a corrupt `rpgworldgen.db` (plans re-derive from `worlds.yml` seeds).
- `NoClassDefFoundError: org/sqlite/...` → use the shaded JAR from `mvn package`
  (not `target/classes`).

## Commands

- `Unknown world` → run `/rpgworld create <world> <size>` first; `preview` and
  `generate` need a created world.
- `Nothing running/paused` → check `/rpgworld status <world>`; jobs are per-world.
- `Confirmation expired` → run `regenerate` without `--confirm`, then again with
  `--confirm` within 60 seconds. Back up the world folder first.

## Generation

- Slow progress → raise `generation.blocks-per-tick` (default 2000) or run on an
  empty server; `status` shows live stage and queue drain.
- `FAILED ... Too many errors` → read the console error lines (usually entity
  spawns or chunk loads); fix cause, then `regenerate <world> <name> --confirm`.
- Structures floating/sunk → builders follow `getHighestBlockYAt`; extreme
  amplified/custom terrain may need manual touch-up.
- `Dropped ops` counter (queue full) → raise `generation.max-queue-size`.

## Safe zones

- Players can still build → they hold `rpgworld.admin`, or the zone radius in
  `safezones.yml` doesn't cover the spot (`regions <world>` shows centers).
- WorldGuard overlaps → built-in zones run at `HIGH`; order WorldGuard
  accordingly or disable `prefer-worldguard` mirroring.

## Recovery

- After a crash, unfinished worlds resume automatically ~2s after enable
  (`Recovery: resumed generation...` in console). Progress is checkpointed per
  structure in SQLite (`progress` table).
