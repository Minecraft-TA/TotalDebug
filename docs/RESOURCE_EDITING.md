# Resource editing

Status: design recorded 2026-09-26. Implemented: configuration settings written to their files, with NeoForge's config watcher applying them; key bindings set in the running game, or in `options.txt` while it is closed; change record format 2 with levels and resources; `PACK_STACK`, `RELOAD` and `RELOAD_RESULT` with problem collection; Pack-level editing of text resources in their tab, with the Changes page listing and reverting them; the Game level for resources (the in-memory pack, `SET_OVERLAY`) and for configuration values (`SET_CONFIG_VALUE`, `CONFIG_VALUE_RESULT`); protocol 27. Not yet: the Resource packs row, texture editing and forced values. Writing into mod JARs was dropped on 2026-09-26 (see [Levels](#levels)).

## Goal

A pack maker changes any configuration value, key binding or resource of the pack, sees the result in the running game without restarting, and keeps, ships or reverts the change. One model covers all of them.

## Scope

This design covers the **pack's content**: what ships with the pack and is the same in every world.

| In scope | Status |
|---|---|
| Configuration settings, as values and as file text | Done |
| Key bindings | Done |
| Text resources: models, blockstates, language files, atlases, sounds, and data such as recipes, tags and loot tables | This step |
| Textures, including animation frames | Next step |
| Forced configuration values that a reload does not reach | Step after textures |

**Not in scope:** the state of a running world. It lives in the world save, differs per world, and has no pack-level file to revert to.

| Out of scope | Where it belongs |
|---|---|
| NBT of block entities, entities and item stacks | Its own step on the inspection page, after the inspection redesign lands. Needs its own design: how an edit reaches the server, what undo means for world state, and whether it enters the change record |
| Fields of Java objects in the running game | Its own step after forced values, reusing their field writing and instance lookup. Arbitrary writes stay with the evaluator and debugger |

Forced values are the one place this design writes Java fields: only fields that cache a configuration setting, and only to the setting's value.

## Levels

A change is written at one of two levels. A target can hold changes at both at once, such as a value tried in the game and then written to the pack.

| Level | Configuration setting | Key binding | Resource | Lasts | Revert |
|---|---|---|---|---|---|
| Game | The loaded `ModConfigSpec` value, then a reload event | Not offered; the game saves bindings at once | An in-memory pack at the top of the game's pack stack | Until the game closes | Removes the value from memory and reloads |
| Pack | The configuration file, keeping comments | `options.txt`, set live through the game when it runs | A file in the pack Companion manages | Until reverted | Writes the replaced value back, or deletes the managed file |

- **Game** is for trying. It needs a connected game and writes no file.
- **Pack** is the default. It survives restarts, lives outside mod files and ships with the pack. As with key bindings, a running game applies it live and a closed game reads it at the next start.
- **Not offered: writing into a mod's JAR.** It was planned for resources a mod reads straight from its own JAR and for packs shipped without extra packs, and dropped on 2026-09-26 as destructive and more than the pack needs now. Those resources take effect only through the mod's own mechanism or a restart.

## The managed pack

Companion writes Pack-level resources into ordinary packs, so they keep working without TotalDebug:

| Content | Folder |
|---|---|
| Assets | `resourcepacks/TotalDebug/` |
| Data | `saves/<world>/datapacks/TotalDebug/` of the current world |

- Companion creates `pack.mcmeta` with the running game's pack format when it writes the first file.
- **Enabling:** with a game connected, the mod selects the managed resource pack and moves it to the top of the stack before it reloads, and saves `options.txt`. Offline, Companion appends `file/TotalDebug` to `resourcePacks` in `options.txt`. A new world datapack is enabled by the game itself when the world loads or on the next data reload.
- **Overridden:** a pack above the managed pack that supplies the same path makes the edit ineffective. The resource page names the pack that wins.
- **The current world:** the world the game has open, or the one played last while none is open. Companion shows one world at a time; choosing among other worlds is deferred ([MODPACK.md](MODPACK.md#the-current-world)). The resource tab and the Changes page name the world a data change was saved in.
- **Global data:** vanilla has no datapack for every world, so a data change applies to the current world only. Global datapacks through Open Loader, Paxi or similar mods are an extension ([MODPACK.md](MODPACK.md#rows)).

## When a change applies

Each resource kind has a reload that makes the running game use it. The change shows its effect the way configuration edits do today ("the game reloaded it", "takes effect after rejoining the world", "takes effect after restarting the game").

| Resource | Reload | Effect |
|---|---|---|
| `assets/*/lang/*.json` | Language only | Now, in about a second |
| Other assets: models, blockstates, textures, atlases, sounds, fonts, particles, shaders | Full client resource reload | Now, after the reload |
| Textures of a stitched atlas | Upload into the atlas (texture editing step) | Now, without a reload |
| Data: recipes, tags, loot tables, advancements, functions, predicates, item modifiers, data maps, mods' own reload listeners | Server data reload, as `/reload` | Now, after the reload |
| Data read when a world loads: worldgen, dimension types, damage types, other dynamic registries | None | After rejoining the world; generated chunks keep their content |
| Resources a mod reads from its JAR or only at startup | None | After restarting the game |

- Requests made during a reload are merged into one more reload after it ends.
- **Problems:** during a reload the mod collects warnings and errors that name an edited path or resource location, such as a model that failed to parse, and returns them with the result. The resource page shows them on the edited file.
- **Offline checks:** Companion checks JSON syntax, and that a language file is a flat map of strings, before it writes.
- **Singleplayer only for data:** data reloads, data tried in the game and server configuration values tried in the game need a singleplayer world. On a server the game holds only a copy of the server's configuration, so such a try is refused. A dedicated server is not covered yet; it would take the forwarded server channel and the server's script policy.

## Protocol

New messages follow the key binding pair (`SET_KEY_BINDING`, `KEY_BINDING_RESULT`): a request with a request id, and a result carrying the value before and after, or why nothing changed.

| Message | Direction | Content |
|---|---|---|
| `PACK_STACK` | Game to Companion | Enabled resource packs and datapacks in order, with their files; sent when the stack changes |
| `RELOAD` | Companion to game | Request id, what to reload: language, resources, data |
| `RELOAD_RESULT` | Game to Companion | Request id, duration, problems naming edited paths, or the error |
| `SET_OVERLAY` | Companion to game | A resource path and its bytes to put into the in-memory pack, or no bytes to remove it; no answer of its own, the `RELOAD` sent after it reports the result |
| `SET_CONFIG_VALUE` | Companion to game | Request id, configuration file name, setting path, literal |
| `CONFIG_VALUE_RESULT` | Game to Companion | Request id, value before and after, or the error |

- An overlay entry is limited to 8 MiB, half of SCNet's message size.
- **The in-memory pack** is one pack for assets and data, fixed at the top of each stack and always enabled, registered through `AddPackFindersEvent`. It is emptied when Companion disconnects, and the game reloads what it showed.
- **A configuration value in memory** is set the way NeoForge applies a reloaded file: the loaded configuration takes the value, the specification drops its caches and the mod receives `ModConfigEvent.Reloading`. It lasts until NeoForge reads the file again; writing the file ends the try.
- These are kernel services every extension needs, so they are native messages rather than scripts run through the evaluator.
- One protocol version bump for the step: 27.
- Forced values add their own pair in their step.

## Change record

Targets are `Setting`, `KeyBinding` and `Resource`. A resource is its pack path, such as `assets/ns/models/block/slab.json`, and the managed pack it was written to, which also names the world for data, or none in the game's memory. Every entry has a level: Game or Pack. Settings and key bindings are Pack. The format is 2; Companion reports a file of another format with its path instead of reading it.

| Field | Setting and key binding | Resource |
|---|---|---|
| Original | The value before the first change | SHA-256 of what the managed pack held before, kept in `total-debug/originals`; empty when it held nothing, and revert deletes the file |
| Current | The value written last | SHA-256 of the bytes written last |
| State | Applied, pending with the effect | Applied, or changed outside Companion since |

- Game-level entries are not saved and are removed when the game disconnects.
- A file whose hash no longer matches `current` was edited outside Companion; the entry shows it, as `observed` does for settings today.
- Texture edits are resource entries. Forced values (below) are Game-level entries with the field they set.

## Forced values

Some mods keep a configuration value where a reload does not reach it. Companion finds each place a mod reads a setting, tells when the running game picks up a new value, and forces it where that is safe.

### Where mods keep values

A scan of the bytecode that calls `ModConfigSpec` getters in All the Mods 10 To the Sky on 2026-09-25 (342 mod files, 89,214 classes, 154 mods with such code) sorts every read site:

| Where the value goes | Sites | Reload reaches it | Examples |
|---|---|---|---|
| Read where it is used | 2,531 | Yes | Most mods |
| Returned by the mod's own wrapper method | 692 | Depends on the wrapper's callers | AE2, Reliquary, Just Dire Things |
| Copied into a field by a reload handler | 136 | Yes | FramedBlocks, Lootr (which clears its lazy caches on reload) |
| Cached by a wrapper that clears itself on reload | 10 | Yes | Mekanism `CachedValue` |
| Instance field, set when an object is made | 195 | New objects only | 91 in block entities, 16 in screens, 4 in entities, the rest in goals, particles, renderers and upgrade wrappers |
| Static field, lazy cache never cleared | 5 | No | RFTools Builder `quarryReplaceBlock`, RFTools Utility `trueTypeFont`, ModernFix `jeiPluginBlacklist`, Not Enough Wands `cachedWandUsage` |
| Static field, set in a static initializer | 17 | No | Modular Bees (8, final), Ars Energistique (2, final), Occultism `ClientPentacleManager`, Extreme Reactors `TurbineData`, Sophisticated Backpacks `DIFFICULTY_BACKPACK_CHANCES` |
| Used during startup, no field | 73 | No | Farmer's Delight village buildings at server start, Ars Nouveau forest weight in common setup, Railcraft `Seasons` |
| Read only by a handler for the first load | 4 | No | Sauce, Ars Controle |

### What Companion does for each

| Kind | Generic action | Effect shown |
|---|---|---|
| Instance field in a block entity or entity | Set the field on loaded objects when it is a plain copy | Now after forcing, otherwise after rejoining (chunks recreate them) |
| Instance field in a screen, menu, particle or sound | None needed | Next time it opens or appears |
| Lazy static cache never cleared | Reset it to `null`, the mod's own initial state; the next use reads the new value | Now after forcing |
| Non-final static field, plain copy | Set it by reflection | Now after forcing |
| Final static field | Refused; the JIT may have folded the value into compiled code | After restarting the game |
| Derived value (parsed list, map, product) | Extension for that mod | After restarting the game |
| Startup use or first-load handler | None | After restarting the game, or after rejoining for server-start uses |

- **Finding the sites:** a site is the getter call, the `ConfigValue` field it reads from, and the field its result is stored in. A plain copy allows only casts and boxing in between; anything else is derived. The game resolves the `ConfigValue` field to the setting's path.
- **Reload reach:** a site counts as refreshed when a `ModConfigEvent` handler reaches it through calls or lambdas in the mod's classes. A handler that only takes `ModConfigEvent.Loading` does not count.
- **Verifying before offering Force:** after a reload, the game reads each cached field. Only a plain copy that differs from the setting's new value shows "the game still uses X" and Force. An unfilled lazy cache (`null`) is not stale.
- **Wrappers:** a mod method that returns a getter's result is treated as a getter, one level deep, so its callers are sorted the same way.
- **Final fields and derived values:** rewriting the reading code in the running game would cover them, and belongs to runtime class patching, which needs its own design decision.
- **Not covered:** mods whose configuration does not use `ModConfigSpec`.
- Forcing never replaces the file write. The value is written at the Pack level too, so a restart gives the same result.

## Kernel and extensions

Following the dividing rule in [EXTENSIBILITY.md](EXTENSIBILITY.md#the-dividing-rule):

| Kernel | Extension |
|---|---|
| Levels, the change record, the managed pack and its enabling | Reloads of mod-specific content: KubeJS and CraftTweaker scripts, Patchouli books, shader packs |
| Game overlay pack, language, resource and data reloads, problem collection | Checks of mod-specific formats |
| Offline enabling of the managed pack in `options.txt` | Global datapacks through loader mods |
| `SET_CONFIG_VALUE` and reload events; read-site analysis; forcing plain copies, lazy caches and loaded block entities | Forcing derived values for one mod |
| JSON checks, pack stack capture | Mods that read their own resources at startup and can re-read them |

Extensions contribute through two points once the extension API exists: a **reload** for a resource path pattern, and a **force** for a setting. Until then, built-in reloads are ordinary code shaped like those points.

## Companion

Wording follows [UI_GUIDE.md](UI_GUIDE.md).

- **Resource tab:** text resources (JSON, `.mcmeta`, `.lang`, `.mcfunction`, `.snbt`) are editable, showing what the game uses: the tried copy, the managed pack's copy or the opened file. The bar names that source and when the game uses it; Try in Game, Save, Discard and Revert in Game appear when they would change something. A pack above the managed one that supplies the file too is named in a warning.
- **Configuration table:** Try Value in Game… opens the value's field, and the accepted value goes into the game's memory; the tooltip shows a tried value.
- **Changes page:** a Resources tab for resources in the managed packs, and an In the game tab for everything tried in the game's memory. Revert follows the level; several rows revert together, as for settings and key bindings.
- **Modpack tree:** the Resource packs row is the next step, and a World root for the current world follows it ([MODPACK.md](MODPACK.md#order)).

## Order

This step, in the worktree `resource-editing` (branch `claude/resource-editing`):

1. Change record: `Resource` target and levels, format 2.
2. `PACK_STACK`, `RELOAD` and `RELOAD_RESULT`, with problem collection.
3. The managed pack and Pack-level text resource editing.
4. Game level: `SET_OVERLAY`, `SET_CONFIG_VALUE` and `CONFIG_VALUE_RESULT`.

Later steps:

| Step | Content |
|---|---|
| Texture editing | Pixel tools in the image viewer with animation frames, live atlas upload, save at the Pack level |
| Forced values | Read-site analysis, verification after reloads, Force |
| Catalog and program insights | As planned in [MODPACK.md](MODPACK.md#order) |
| World data editing | NBT of block entities, entities and item stacks from the inspection page; own design first |
| Object fields | Setting fields of inspected objects, building on forced values; own design first |

## Reported in testing

Found on 2026-09-26 in Codex-HDR-Audit, to be designed later:

- **Search froze Companion:** a search was slow enough to block the window.
- **Unformatted JSON:** resources written on one line are hard to edit. Options are a formatted view that saves in the original layout, formatting when saving, or completion and a structured editor later.

## Decisions

Taken on 2026-09-26:

- **Levels:** Game and Pack; writing into mod JARs is dropped.
- **Reload on save:** automatic, merging saves made during a reload into one more reload.
- **Managed pack location:** the vanilla folders above, so the pack works without TotalDebug.
- **Format-1 change record:** reported and not read.
- **World data and object fields:** separate steps after this series.
