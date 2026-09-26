# The Modpack tree

Status: design recorded 2026-09-25. Implemented: the Modpack root with Mods, Content, Configuration, Key bindings and Changes; the change record holds configuration settings, key bindings and resources. Every other row below arrives with the feature that gives it content; the tree never shows a row with nothing behind it.

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
| Content | Every registered block, item, entity type, fluid and sound event with the mod that registered it, by kind; later tags, recipes, loot tables, biomes and enchantments, read from the current world | Catalog | Built in |
| Configuration | Every setting of every mod, modified ones by default; later `defaultconfigs` and configuration files NeoForge does not manage | Catalog, `config/`, worlds' `serverconfig/` | Built in |
| Resources | Every namespace joined as the game uses it: vanilla, the mods and the enabled packs in the game's order, each file with the copy that wins and what it overrides; each mod's Resources tab is the same view filtered to that mod | Mod files, `resourcepacks/`, captured pack stack | Built in |
| Resource packs | Enabled packs in the order the game applies them, then the rest of `resourcepacks/`, and the pack Companion manages | `resourcepacks/`, `options.txt`, captured pack stack | Built in |
| Key bindings | Every binding with its key, default, context and mod; collisions split into those on the same key press, modifier overlaps and equal keys in contexts that never meet; searchable by text, by key such as `ctrl+g`, or by pressing the key; keys are set in the running game, or in `options.txt` while it is closed, one binding or a selection at a time | Captured key mappings, contexts and key names, `options.txt` | Built in |
| Game options | The rest of `options.txt` | `options.txt` | Built in |
| Logs | `latest.log` and crash reports, linked to the classes and mods they name | `logs/`, `crash-reports/` | Built in |
| Mixins | Mixins by target class, where several mods change the same member | Captured mixin configurations | Built in |
| Changes | Every change Companion made, with its level, the value it replaced and a revert | Companion's change record | Built in |
| Shader packs | The active shader pack and its options | `shaderpacks/` | Extension for Iris or Oculus |
| Pack scripts | KubeJS and CraftTweaker scripts, reloaded through their mod | `kubejs/`, `scripts/` | Extension per scripting mod |
| Global datapacks | Datapacks loaded for every world | Folder of Paxi, Open Loader or a similar mod | Extension per loader mod |

The owner follows the dividing rule in [EXTENSIBILITY.md](EXTENSIBILITY.md): vanilla and NeoForge concepts every pack has are built in; a row that exists because of one mod is an extension.

Each pack-wide row that also exists per mod, such as Key bindings or Content, shows the same table as the mod's own tab, with a Mod column added.

## The current world

Companion shows one world: the one the game has open, or the one played last while none is open. Choosing among other worlds is deferred until it has a clear design. Decided on 2026-09-26.

- **Pack content read from the world:** tags, recipes, loot tables, biomes and enchantments belong to a world in the game's terms, but they are the pack's content. They are listed under Content, read from the current world, not presented as world content.
- **World state:** a **World** root beside Modpack holds what is really the world's own: its overview (seed, time, weather, difficulty, spawn), game rules and datapacks. Later: loaded chunks and the tickets that keep them loaded, players and saved data.
- **Server configuration** stays under Configuration, with the current world's file first.
- **Writes:** a data change is saved in the current world's datapack, and says so.

## Content kinds

Content is kept per registry. The game captures each listed registry in one shape: an entry's id, the name the game shows, the class of the registered object, an item that draws it, links to related entries such as a block's item or an entity type's spawn egg, and further facts by a stable key. Companion lists every captured registry the same way, under Content in the pack and on each mod's Content tab, and opens any entry on a definition page with its facts and links. Only the rendering of blocks and items on that page is specific to them.

Adding a kind is one capture description in the game's `CatalogRegistries`. Companion lists a registry it has no description for under a name made from its id; `ContentKinds` gives the known ones their names and icons. Registries only a loaded world holds, such as biomes, tags and recipes, need a capture of the open world first.

## Key bindings in code

A binding's menu finds usages of its name, such as `key.jei.toggleOverlay`, which is where the mod creates the binding. The code that reacts to the key reads the field holding the binding, not its name. Following the name to that field and then to the field's readers was measured in All the Mods 10 To the Sky on 2026-09-25, 389 bindings:

- 179 names are followed in the same method by a store into a `KeyMapping` field; the chain works for them.
- About 35 names occur only in language generators or screen code, not where the binding is created.
- 18 bindings are held by a mod's own wrapper type, or created in a lambda such as NeoForge's `Lazy<KeyMapping>`.
- 159 names occur nowhere in mod code: Minecraft's own, and names that are concatenated at runtime, as in Create, Mekanism, Jade, PneumaticCraft and Sophisticated Backpacks.

Following names covers about half of the mod bindings, so it is left out. Every binding, however its name is built, passes through `RegisterKeyMappingsEvent.register`. Recording the calling method there gives the registration site for all of them, and the field passed at that call leads to its readers. That belongs with listing which mods read which bindings, including mods that read raw keys directly.

## Modpack rows as an extension point

Extensions contribute Modpack rows through a Companion extension point. A contribution declares:

- its row: name, icon, sort position and a count or state shown beside it,
- whether it applies to the open pack, such as whether Iris is installed,
- the page it opens, or the children it lists.

Built-in rows use the same point. Until the extension API exists, built-in rows are ordinary tree items in `ModTreeItems`; they move onto the point when it is introduced, without changing what the tree shows.

## The change record

Changes becomes the one record of what Companion wrote, replacing per-view undo history as the lasting source:

- **Entry:** what changed (a setting, a key binding, a resource, a texture), the level it was written at (the running game's memory or the pack's files), when, the value it replaced and the value written. See [RESOURCE_EDITING.md](RESOURCE_EDITING.md#change-record).
- **Revert:** writes the replaced value back at the same level.
- **Storage:** kept per instance in `total-debug/changes.json`, so it survives restarts of Companion and the game.
- **Views read from it:** the configuration table marks values edited by the user from this record, and the game's pending restarts are derived from it.

Editing a configuration file as text records one entry per setting it changed.

## Order

| Step | Rows |
|---|---|
| Configuration editing | Modpack root, Mods, Configuration |
| Editing configuration files as text | Changes |
| Layered writes and text resources | Changes gains resources and what was tried in the game |
| Resource packs | Resource packs |
| Resources across the pack | Resources |
| The current world | World root: overview, game rules, datapacks |
| Texture editing | Resource packs gain the managed pack's textures |
| Catalog and program insights | Mixins, Game options, Logs, Overview with update differences |
| Extension API for Companion | Shader packs, Pack scripts, Global datapacks |
