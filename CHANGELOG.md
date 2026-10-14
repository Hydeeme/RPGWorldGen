# Changelog

## 1.0.0 (2026-10-14)

Initial release.

- Deterministic world planner: spawn, villages, cities, kingdom capitals +
  border outposts, dungeons, landmarks, road links; overlap-free; persisted.
- Procedural builders: houses, towers, wells, farms, stalls, keeps, walls with
  gates, dungeon rooms, corridors, entrance shafts, roads with bridges,
  wilderness features (shrines, camps, watchtowers, groves, ruins), landmarks.
- Bounded scheduler: one structure per tick + configurable block budget; road,
  wilderness, and drain phases; pause/resume/cancel; SQLite checkpoints;
  restart recovery from real DB records.
- Safe zones: spawn + capitals, break/place/PvP/mob-spawn/explosion/fire flags,
  enter/exit messages, optional WorldGuard mirror.
- Commands: full `/rpgworld` tree with granular permissions, tab completion,
  two-step regenerate confirmation, `link` verify/repair for integrations.
- Integrations: soft-dependency adapters (Multiverse, WorldEdit, WorldGuard,
  Vault, Citizens, PlaceholderAPI); core runs without all of them.
  WorldGuard mirror sets real DENY flags + greet/farewell (live-verified 7.0.12);
  Multiverse uses the native 5.x import API with command fallback (live-verified
  5.8.2-pre).
- 44 unit tests green. Small-world live generation verified (16/16, 0 errors);
  full preset sweep pending (see docs/live-test-plan.md).
