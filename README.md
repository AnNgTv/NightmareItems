# NightmareItems

Custom YAML item plugin for **Paper 1.20.6** / Java 21, intended for NIGHTMARE TCYT.

## Features in this starter version

- One YAML file per item under `plugins/NightmareItems/items/`.
- Custom display name, lore, material, enchantments, and unbreakable flag.
- Skills triggered by right click / left click, including sneak variants.
- Supported skills: `DAMAGE`, `POTION`, `LIGHTNING`, `HEAL`.
- Per-skill cooldowns.
- In-game item browser GUI (`/nitems editor`); click an item to receive it.
- `/nitems give <player> <item_id>`, `/nitems list`, `/nitems reload`.
- GitHub Actions build artifact.

> This is an initial implementation. The GUI browses and gives items; item properties and skill definitions are edited in YAML, not through a full in-game form editor.

## Build

Requires JDK 21 and Gradle 8.7+.

```bash
gradle --no-daemon clean build
```

JAR output: `build/libs/NightmareItems-1.0.0.jar`

## Install

1. Stop the Paper server and back up the server.
2. Put the JAR in `plugins/`.
3. Start the server once.
4. Edit `plugins/NightmareItems/items/blood_blade.yml`.
5. Run `/nitems reload`.

## Commands

- `/nitems editor` — open item browser (admin permission).
- `/nitems list` — list configured item IDs.
- `/nitems give <player> <item_id>` — give configured item.
- `/nitems reload` — reload config and items.

Permission: `nightmareitems.admin` (default OP).

## Add an item

Copy `plugins/NightmareItems/items/blood_blade.yml` to another `.yml` file and change the `id`. Supported enchantment names are Bukkit enchantment names, for example `SHARPNESS`.

## GitHub Actions

Push this repository to GitHub. The workflow builds on every push and on manual dispatch. Open **Actions → Build NightmareItems → latest run → Artifacts** to download the JAR.
