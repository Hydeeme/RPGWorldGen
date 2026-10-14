# Integrations

All integrations are **soft dependencies** (`softdepend` in `plugin.yml`).
Missing plugins only disable their own adapter — the core always runs.

| Plugin | Tested version | Behavior when present | When absent |
|---|---|---|---|
| Multiverse-Core | 5.8.2-pre (live, Paper 1.21.10); 4.x via console-command fallback | World auto-registered via native `WorldManager.importWorld` API on generate (idempotent; `register-multiverse: true`), re-verifiable anytime with `/rpgworld link <world>`; status shows `Multiverse=on(registered)` | Nothing; use vanilla world access |
| WorldEdit / FAWE | 7.3.10 (live, Paper 1.21.10) | `schematics:` section of `structures.yml` is validated at enable; missing files log warnings and fall back to procedural builders; required by WorldGuard for region mirroring | Procedural builders only; `hasSchematicSupport()` returns false |
| WorldGuard | 7.0.12 (live, Paper 1.21.10) | Spawn/capital zones mirrored as cuboid regions reflectively with real DENY flags (PVP, BLOCK_BREAK, BLOCK_PLACE, MOB_SPAWNING, TNT/OTHER/CREEPER explosion, FIRE_SPREAD/LAVA_FIRE) + greet/farewell messages (GREET_MESSAGE alias-aware); deterministic ids `rpg_<world>_<x>_<z>` so `/rpgworld link` is idempotent; status shows mirrored ids | Built-in `SafeZoneManager` protection applies (break/place/PvP/mob-spawn/explosion/fire) |
| Vault | any | Detected + logged | No economy features (none required by core) |
| Citizens | any | Detected + logged; villages spawn vanilla villagers regardless | Vanilla villagers only |
| PlaceholderAPI | any | Detected + logged | No placeholders |

## Schematic templates (Mode B)

1. Create `plugins/RPGWorldGenerator/schematics/`.
2. In `structures.yml`, add entries:
   ```yaml
   schematics:
     village_house_1:
       file: village_house_1.schem
       category: house
       rotation: true
   ```
3. Missing files → warning at enable + procedural fallback at build time.
   Limitation: the reflective paste adapter verifies availability but does not
   yet execute version-pinned WE/FAWE edits; treat schematics as
   validation-ready, paste-pending (see README limitations).

Event priorities for the built-in protection are `HIGH` with
`ignoreCancelled=true`, documented so protection-plugin admins can order
accordingly. The built-in manager deliberately bypasses only for
`rpgworld.admin`.
