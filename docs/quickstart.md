# Installation & Quick Start

## Requirements

- Paper or Spigot 1.20.4, Java 17+.
- ~500 MB free disk per RPG world (large worlds need more).
- No mandatory dependencies. Multiverse-Core recommended for multiworld hubs.

## Install

1. Build (`mvn package`) or download `RPGWorldGenerator-1.0.0.jar`.
2. Place it in the server `plugins/` folder.
3. Start the server once to generate default configs (`plugins/RPGWorldGenerator/`).
4. Review `config.yml` (especially `generation.blocks-per-tick` for busy servers).

## Create your first world

```
rpgworld create Eldoria 6000
rpgworld preview Eldoria
rpgworld generate Eldoria
rpgworld status Eldoria
```

Teleport in: `/mv tp Eldoria` (Multiverse) or vanilla `/execute in ... run tp`.

## Recommendations

- Small community (<20 players): `5000`.
- Medium server (20–80): `6000` (default).
- Large network hub world: `8000` — generate during off-hours.

## Performance guidance

| Server state | `blocks-per-tick` | Effect |
|---|---|---|
| Live, players online | 500–1000 | Minimal tick impact, slower build |
| Quiet / night | 2000 (default) | Balanced |
| Empty build server | 5000–10000 | Fastest (watch memory) |

Pause/resume is safe at any time; progress checkpoints persist every structure.
After an unclean restart, unfinished worlds resume automatically on enable.
