# The Modpack tree

Status: design recorded 2026-09-25. Implemented: the Modpack root with Mods, Configuration and Changes; the change record holds configuration settings. Every other row below arrives with the feature that gives it content; the tree never shows a row with nothing behind it.

## Purpose

The Project tree separates what a pack is made of from the code behind it:

- **Modpack** holds the pack's content and everything a pack maker changes: mods, their configuration, resource packs, worlds and the changes Companion made.
- **Runtime** holds classes and libraries as the game loaded them.
- **Scripts** and **decompiled-files** stay where they are.

A pack-wide view belongs under Modpack, not under one of its mods. Views about one mod stay on that mod's page and link to the pack-wide view where one exists.

## Rows

| Row | Content | Source | Owner |
|---|---|---|---|
| Overview | Minecraft, loader and Java versions, memory settings, mod count, catalog state; later the differences between two captures of the pack | Catalog, launcher instance | Built in |
| Mods | Installed mods and other namespaces, later disabled mods | Catalog, runtime modules | Built in |
| Configuration | Every setting of every mod, modified ones by default; later `defaultconfigs` and configuration files NeoForge does not manage | Catalog, `config/`, worlds' `serverconfig/` | Built in |
| Resource packs | Order, enabled packs, which files each pack overrides, the pack Companion manages | `resourcepacks/`, `options.txt`, captured pack stack | Built in |
| Worlds | Server configuration and datapacks of each world, which world is open | `saves/` | Built in |
| Game options | `options.txt`, with key binding conflicts first | `options.txt`, captured key mappings | Built in |
| Logs | `latest.log` and crash reports, linked to the classes and mods they name | `logs/`, `crash-reports/` | Built in |
| Mixins | Mixins by target class, where several mods change the same member | Captured mixin configurations | Built in |
| Changes | Every change Companion made, with its level, the value it replaced and a revert | Companion's change record | Built in |
| Shader packs | The active shader pack and its options | `shaderpacks/` | Extension for Iris or Oculus |
| Pack scripts | KubeJS and CraftTweaker scripts, reloaded through their mod | `kubejs/`, `scripts/` | Extension per scripting mod |
| Global datapacks | Datapacks loaded for every world | Folder of Paxi, Open Loader or a similar mod | Extension per loader mod |

The owner follows the dividing rule in [EXTENSIBILITY.md](EXTENSIBILITY.md): vanilla and NeoForge concepts every pack has are built in; a row that exists because of one mod is an extension.

## Modpack rows as an extension point

Extensions contribute Modpack rows through a Companion extension point. A contribution declares:

- its row: name, icon, sort position and a count or state shown beside it,
- whether it applies to the open pack, such as whether Iris is installed,
- the page it opens, or the children it lists.

Built-in rows use the same point. Until the extension API exists, built-in rows are ordinary tree items in `ModTreeItems`; they move onto the point when it is introduced, without changing what the tree shows.

## The change record

Changes becomes the one record of what Companion wrote, replacing per-view undo history as the lasting source:

- **Entry:** what changed (a setting, a resource, a texture), the level it was written at (the running game's memory, the managed pack, a mod JAR), when, the value it replaced and the value written.
- **Revert:** writes the replaced value back at the same level. A JAR entry reverts from its backup.
- **Storage:** kept per instance in `total-debug/changes.json`, so it survives restarts of Companion and the game.
- **Views read from it:** the configuration table marks values edited by the user from this record, and the game's pending restarts are derived from it.

Editing a configuration file as text records one entry per setting it changed.

## Order

| Step | Rows |
|---|---|
| Configuration editing | Modpack root, Mods, Configuration |
| Editing configuration files as text | Changes |
| Layered writes and text resources | Resource packs, Worlds |
| Texture editing | Resource packs gain the managed pack's textures |
| Catalog and program insights | Mixins, Game options, Logs, Overview with update differences |
| Extension API for Companion | Shader packs, Pack scripts, Global datapacks |
