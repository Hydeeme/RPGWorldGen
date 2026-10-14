# Commands & Permissions

## Commands (`/rpgworld`)

| Command | Permission | Description |
|---|---|---|
| `help` | — | Show command help |
| `create <world> <5000\|6000\|8000\|custom> [seed]` | `rpgworld.create` | Create a new world (border + spawn). Fails if the name is loaded or a folder exists — never overwrites |
| `preview <world>` | `rpgworld.preview` | Plan without building; prints structure counts, roads, warnings |
| `generate <world>` | `rpgworld.generate` | Plan (if needed) and start incremental generation |
| `pause <world>` | `rpgworld.generate` | Pause a running job (progress checkpoint persists) |
| `resume <world>` | `rpgworld.generate` | Resume a paused job |
| `cancel <world>` | `rpgworld.generate` | Cancel a job (clears the remaining queue) |
| `status <world>` / `info <world>` | `rpgworld.info` | World status: preset, seed, planning, structures done/total, stage, errors |
| `regions <world>` | `rpgworld.info` | List planned structures with coords and tiers |
| `locate <world> <village\|city\|kingdom\|dungeon>` | `rpgworld.info` | Coordinates of matching structures |
| `regenerate <world> <region-id>` | `rpgworld.regenerate` | Two-step rebuild: run once for a warning, repeat with `--confirm` within 60s |
| `link <world>` | `rpgworld.generate` | Verify/repair Multiverse registration + WorldGuard mirrors (idempotent) |
| `reload` | `rpgworld.reload` | Reload all YAML configs |

Tab completion covers subcommands, known worlds, sizes, and locate types.

## Permissions

| Node | Default | Grants |
|---|---|---|
| `rpgworld.admin` | op | All of the below |
| `rpgworld.create` | op | `create` |
| `rpgworld.generate` | op | `generate`, `pause`, `resume`, `cancel` |
| `rpgworld.preview` | op | `preview` |
| `rpgworld.regenerate` | op | `regenerate` (with confirmation gate) |
| `rpgworld.reload` | op | `reload` |
| `rpgworld.info` | true | `status`, `info`, `regions`, `locate` |

Checks live in `PermissionManager` (admin wildcard or the granular node).
Safe-zone build protection bypasses with `rpgworld.admin`.
