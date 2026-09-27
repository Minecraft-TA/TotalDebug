# The Modpack tree

Status: design recorded 2026-09-25. Implemented: the Modpack root with Mods, Content, Configuration, Resources (its files; the Packs tab follows), Key bindings, Logs and Changes; the change record holds configuration settings, key bindings and resources. Every other row below arrives with the feature that gives it content; the tree never shows a row with nothing behind it.

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
| Resources | Files: every namespace joined as the game uses it, vanilla, the mods and the enabled packs in the game's order, each file with the copy that wins and what it overrides; each mod's Resources tab is the same view filtered to that mod. Packs: the enabled packs in the order the game applies them, then the rest of `resourcepacks/`, including the pack Companion manages; packs are ordered, enabled and disabled here, and choosing one filters Files | Mod files, `resourcepacks/`, `options.txt`, captured pack stack | Built in |
| Key bindings | Every binding with its key, default, context and mod; collisions split into those on the same key press, modifier overlaps and equal keys in contexts that never meet; searchable by text, by key such as `ctrl+g`, or by pressing the key; keys are set in the running game, or in `options.txt` while it is closed, one binding or a selection at a time | Captured key mappings, contexts and key names, `options.txt` | Built in |
| Game options | The rest of `options.txt` | `options.txt` | Built in |
| Logs | `latest.log` and crash reports, linked to the classes and mods they name | `logs/`, `crash-reports/` | Built in |
| Mixins | Mixins by target class, where several mods change the same member | Captured mixin configurations | Built in |
| Changes | Every change Companion made, with the value it replaced and a revert | Companion's change record | Built in |
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

## Resources

Every resource of the pack, each file once, in the copy the game uses:

- **Order:** assets follow the resource pack stack and data the datapack stack, lowest first, and the highest pack that supplies a path wins.
  - With a game connected, both stacks are the ones it names (`PACK_STACK`), so data includes the current world's datapacks. A game at its menu or on a server has no data of its own, so none is listed then.
  - Without one, assets follow `options.txt`, and data comes from Minecraft and the mods. When `options.txt` leaves them out, vanilla goes at the bottom and the mods' resources at the top, as the game adds them.
  - The mods stack as NeoForge stacks them: one pack per mod file, in the order the game loaded the mods, which the catalog keeps. A file with several mods is one pack named by all of them.
- **Unreadable packs:** a pack the game builds in memory, such as a mod's generated assets, a mod inside another mod's file, and the built-in Programmer Art and High Contrast packs add nothing Companion can read, so they are skipped.
- **Rows:** a row names its namespace and folder and the pack its copy comes from; its tooltip lists the lower packs it hides. The filter matches the path and the pack's name. The list is read again when the game's packs change and after a save or revert has finished, keeping the selection. A pack that cannot be read is skipped.
- **Opening:** opening a row opens the winning copy, which is also the one the resource editor saves over in the managed pack.
- **Speed:** the join and its sort run off the Swing thread. The list has one row height and follows the view's width, so it never renders every row to measure itself. In All the Mods 10 To the Sky (352,924 resources on 2026-09-27), filtering takes about 30 ms per keystroke.

## Logs

The game's `logs/latest.log` and `logs/debug.log`, then its crash reports, the newest first. The page reads them again whenever it is shown, since the game writes them while it runs.

- **A log** lists its warnings and errors, each with the lines that follow it, such as a stack trace, in its tooltip. A row opens the log at that line, reading the tab again if the game wrote to the log since. The file's row counts every warning and error; the page lists the first 2,000. A log larger than Companion opens (16 MiB) still lists its entries, with their text in their tooltips.
- **A crash report** is listed by what happened and when, as the report says. It shows the mods that failed to load and why, from a crash report of failed mod loading, then each exception and its causes with their stack frames. A frame names the mod whose module holds it, as `TRANSFORMER/total_debug@2.0.0/...` says, or the mod whose mixin added a handler such as `handler$zfe000$sodium$onTick`, and opens its class; other rows open the report at that line.
- **Reading:** a file is read again only when it changed, and the page keeps the selected file and row.
- **Not yet:** archived logs (`.log.gz`), naming the mod behind a log line's logger, and pack health checks built on these.

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

- **Entry:** what changed (a setting, a key binding, a resource, a texture), when, the value it replaced and the value written. Every change is written to a file of the pack; see [RESOURCE_EDITING.md](RESOURCE_EDITING.md#change-record).
- **Revert:** writes the replaced value back, or deletes a file Companion added.
- **Storage:** kept per instance in `total-debug/changes.json`, so it survives restarts of Companion and the game.
- **Views read from it:** the configuration table marks values edited by the user from this record, and the game's pending restarts are derived from it.

Editing a configuration file as text records one entry per setting it changed.

## Order

| Step | Rows |
|---|---|
| Configuration editing | Modpack root, Mods, Configuration |
| Editing configuration files as text | Changes |
| Text resources in the managed packs | Changes gains resources |
| Resources across the pack | Resources, with its Packs tab |
| The current world | World root: overview, game rules, datapacks |
| Texture editing | Resources gains the managed pack's textures |
| Catalog and program insights | Mixins, Game options, Logs, Overview with update differences |
| Extension API for Companion | Shader packs, Pack scripts, Global datapacks |
