# Resource editing

Status: design recorded 2026-09-26, simplified 2026-09-27. Implemented: configuration settings written to their files, with NeoForge's config watcher applying them; key bindings set in the running game, or in `options.txt` while it is closed; text resources written into the packs Companion manages and reloaded in the running game (`PACK_STACK`, `RELOAD`, `RELOAD_RESULT`, protocol 28); textures drawn pixel by pixel, saved the same way and put in place in the running game without a full reload; the change record holds all of them and reverts them. Not yet: ordering and enabling packs, and forced values.

## Goal

A pack maker changes any configuration value, key binding or resource of the pack, sees the result in the running game without restarting, and keeps, ships or reverts the change. One model covers all of them.

## Scope

This design covers the **pack's content**: what ships with the pack and is the same in every world.

| In scope | Status |
|---|---|
| Configuration settings, as values and as file text | Done |
| Key bindings | Done |
| Text resources: models, blockstates, language files, atlases, sounds, and data such as recipes, tags and loot tables | Done |
| Textures, including animation frames | Done |
| Forced configuration values that a reload does not reach | Step after textures |

**Not in scope:** the state of a running world. It lives in the world save, differs per world, and has no pack-level file to revert to.

| Out of scope | Where it belongs |
|---|---|
| NBT of block entities, entities and item stacks | Its own step on the inspection page. Needs its own design: how an edit reaches the server, what undo means for world state, and whether it enters the change record |
| Fields of Java objects in the running game | Its own step after forced values, reusing their field writing and instance lookup. Arbitrary writes stay with the evaluator and debugger |

## Every change is a file

A change is written where the pack keeps it: the configuration file (keeping its comments), `options.txt`, or a file in the pack Companion manages. The running game applies it live where it can, and a closed game reads it at the next start. It lives outside mod files and ships with the pack.

The change record is the history: every change keeps the value it replaced and can be reverted. That is how a change is tried: make it, look at the game, keep it or revert it.

Decided on 2026-09-27:

- **No changes in the game's memory only.** A separate level for trying values in memory was built and removed. It duplicated what saving and reverting already do, since saves reload live, and it multiplied the states to handle: disconnects midway, pack order, tried values that outlived a reload. Forced values will need memory writes of their own; they get their own design then.
- **No writing into mod JARs.** Dropped on 2026-09-26 as destructive and more than the pack needs. Resources a mod reads only from its own JAR take effect through the mod's own mechanism or a restart.

## The managed pack

Companion writes resources into ordinary packs, so they keep working without TotalDebug:

| Content | Folder |
|---|---|
| Assets | `resourcepacks/TotalDebug/` |
| Data | `saves/<world>/datapacks/TotalDebug/` of the current world |

- Companion creates `pack.mcmeta` with the running game's pack format when it writes the first file.
- **Enabling:** with a game connected, the mod selects the managed pack before it reloads: above every pack the player orders, below packs fixed at the top, and saves `options.txt`. A data reload enables the managed datapack even where the world had disabled it. Offline, Companion appends `file/TotalDebug` to `resourcePacks` in `options.txt`; while a game runs without a connection, it leaves `options.txt` alone, since the game writes it.
- **Overridden:** a pack above the managed pack that supplies the same path makes the edit ineffective. The resource tab names the pack that wins.
- **The current world:** the world the game has open, or the one played last while none is open. Companion shows one world at a time; choosing among other worlds is deferred ([MODPACK.md](MODPACK.md#the-current-world)). A file opened from a world's managed datapack, such as from the Changes page, is saved back into that pack; the resource tab and the Changes page name the world.
- **The player's own packs:** a file opened from a folder pack, a resource pack in `resourcepacks/` or a datapack in a world's `datapacks/`, is saved in that pack, as a pack's author expects. A file of a mod or another archive is saved into the working pack of its side, chosen under Save into in the resource tab: the managed pack, or a folder pack of the player's own, remembered for the instance. Companion creates, enables and places on top only the managed pack. A pack of the player's keeps its place and state; the resource tab says when it is not enabled, or when a pack above it supplies the file too. Edits of the player's packs are recorded and reverted like any other.
- **Global data:** vanilla has no datapack for every world, so a data change applies to the current world only. Global datapacks through Open Loader, Paxi or similar mods are an extension ([MODPACK.md](MODPACK.md#rows)).

## When a change applies

Each resource kind has a reload that makes the running game use it. The change shows its effect the way configuration edits do ("the game reloaded it", "takes effect after rejoining the world", "takes effect after restarting the game").

| Resource | Reload | Effect |
|---|---|---|
| `assets/*/lang/*.json` | Language only | Now, in about a second |
| `assets/*/textures/**.png` and its `.png.mcmeta`, while a frame keeps its size | Put in place: a texture of its own read again, a sprite of an atlas given the new pixels and mipmaps, or a new animation, where it is | Now, at once |
| Other assets: models, blockstates, atlases, sounds, fonts, particles, shaders, and textures the quick way cannot show | Full client resource reload | Now, after the reload |
| Data: recipes, tags, loot tables, advancements, functions, predicates, item modifiers, data maps, mods' own reload listeners | Server data reload, as `/reload` | Now, after the reload |
| Data read when a world loads: worldgen, dimension types, damage types, other dynamic registries | None | After rejoining the world; generated chunks keep their content |
| Resources a mod reads from its JAR or only at startup | None | After restarting the game |

- **Textures the quick way:** the game reads the PNG from its resource manager, so the pack on top wins, and shows it where the texture is: a texture of its own, such as an entity's, is loaded again; a sprite of an atlas, such as a block's, gets the new pixels and mipmaps in its place, which models and animations already point to. A changed `.mcmeta` gives the sprite new contents with the new animation in the same place, and the atlas's animations start over. It takes the full reload instead when a frame's size changed, when no atlas holds the texture under its path (atlases such as the GUI sprites and particles name theirs otherwise), when the managed pack was not enabled yet, and for a save into the player's own pack, which names no pack to check the copy on top against.
- **A texture's animation:** the game reads a texture's `.mcmeta` only from the pack that supplies the texture or one above it. Saving an animated texture into another pack therefore writes the animation it was opened with beside it when the save makes that pack's copy of the texture, as a change of its own, written before the texture and reloaded with it; a pack's own `.mcmeta` stays.
- Requests made during a reload are merged into one more reload after it ends, and writes queued together, such as Revert All on the Changes page, ask for one reload once the last of them is written.
- **Problems:** during a reload the mod collects warnings and errors that name an edited path or resource location whole, such as a model that failed to parse, and returns them with the result. The resource tab shows them on the edited file.
- **Offline checks:** before writing, Companion reads JSON the way the game does: models and data strictly, language files leniently. A language file is one object whose values are text, or lists of components as NeoForge allows.
- **Singleplayer only for data:** data reloads need a singleplayer world. A dedicated server is not covered yet; it would take the forwarded server channel and the server's script policy.

## Protocol

| Message | Direction | Content |
|---|---|---|
| `PACK_STACK` | Game to Companion | Enabled resource packs and datapacks in order, with their files, and the pack formats; sent when the stack changes and after every handshake |
| `RELOAD` | Companion to game | Request id, what to reload (language, textures, resources, data), the managed pack and the edited paths |
| `RELOAD_RESULT` | Game to Companion | Request id, duration, problems naming edited paths, or the error |

- These are kernel services every extension needs, so they are native messages rather than scripts run through the evaluator.
- One protocol version bump for the step: 27; textures shown the quick way: 28.

## Change record

Targets are `Setting`, `KeyBinding` and `Resource`. A resource is its pack path, such as `assets/ns/models/block/slab.json`, and the managed pack it was written to, stored relative to the game directory like a setting's file. Only whole `.` and `..` path segments are refused.

| Field | Setting and key binding | Resource |
|---|---|---|
| Original | The value before the first change | SHA-256 of what the managed pack held before, kept in `total-debug/originals`; empty when it held nothing, and revert deletes the file |
| Current | The value written last | SHA-256 of the bytes written last |
| State | Applied, pending with the effect | Applied, or changed outside Companion since |

- A file whose hash no longer matches `current` was edited outside Companion; the entry shows it, and leaves the record once the original is back, as `observed` does for settings.
- Texture edits are resource entries.

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
- Forcing never replaces the file write. The value is written to the file too, so a restart gives the same result.

## Kernel and extensions

Following the dividing rule in [EXTENSIBILITY.md](EXTENSIBILITY.md#the-dividing-rule):

| Kernel | Extension |
|---|---|
| The change record, the managed pack and its enabling | Reloads of mod-specific content: KubeJS and CraftTweaker scripts, Patchouli books, shader packs |
| Language, resource and data reloads, problem collection | Checks of mod-specific formats |
| Offline enabling of the managed pack in `options.txt` | Global datapacks through loader mods |
| Read-site analysis; forcing plain copies, lazy caches and loaded block entities | Forcing derived values for one mod |
| JSON checks, pack stack capture | Mods that read their own resources at startup and can re-read them |

Extensions contribute through two points once the extension API exists: a **reload** for a resource path pattern, and a **force** for a setting. Until then, built-in reloads are ordinary code shaped like those points.

## Companion

Wording follows [UI_GUIDE.md](UI_GUIDE.md).

- **Resource tab:** text resources (JSON, `.mcmeta`, `.lang`, `.mcfunction`, `.snbt`) are editable, showing the copy the game uses: the managed pack's, or the opened file. The bar names that source and when the game uses it; Save and Discard appear while the text has unsaved changes. A pack above the managed one that supplies the file too is named in a warning. An open tab follows reverts made on the Changes page.
- **Reformat Code:** in a JSON resource (`.json`, `.mcmeta`), Ctrl+Alt+L or the text's context menu lays the JSON out one value per line, indented by two spaces, as one edit that Save writes and Undo takes back. Keys keep their order and numbers their spelling; text that is not strict JSON, such as JSON with comments, is left as it is, saying why. A file of one long line, such as a minified language file, offers it above the text.
- **Texture tab:** a PNG under `assets/` opens in the image viewer with drawing tools, saved like a text resource, with the same bar, Save into and notices. The tools draw on the frame shown, or on the whole sheet: Pencil (B), Eraser (E), Fill (G), which fills the pixels of one color that touch by an edge, and Color Picker (I); Escape leaves the tools, and a drag then moves the view, as the middle button always does. With the pencil, Alt+click picks the color under it and Shift+click draws a line from the last pixel drawn. The color button opens a chooser with transparency; beside it are the texture's 16 most used colors. Ctrl+Z undoes a whole stroke, Ctrl+Shift+Z or Ctrl+Y redoes it. A texture is saved as a PNG with red, green, blue and alpha, at its size; resizing and editing the animation's `.mcmeta` as text stay separate.
- **Changes page:** a Resources tab for resources in the managed packs, beside Configuration and Key bindings. Revert, or Delete, reverts the selected rows; reverting a file Companion added deletes it, and Delete asks first. Revert All reports every failure in one status.
- **Modpack tree:** the Resources row lists every resource as the game uses it ([MODPACK.md](MODPACK.md#resources)); its Packs tab lists the resource packs, and the World root the current world's datapacks; ordering and enabling them is a later step.

## Order

Later steps:

| Step | Content |
|---|---|
| Packs tab of Resources | Order and enable packs |
| Forced values | Read-site analysis, verification after reloads, Force; its own design for writes into the game's memory |
| Catalog and program insights | As planned in [MODPACK.md](MODPACK.md#order) |
| World data editing | NBT of block entities, entities and item stacks from the inspection page; own design first |
| Object fields | Setting fields of inspected objects, building on forced values; own design first |

## Reported in testing

Found on 2026-09-26 in Codex-HDR-Audit, to be designed later:

- **Search froze Companion:** a search was slow enough to block the window.
- **Unformatted JSON:** resources written on one line are hard to edit. Options are a formatted view that saves in the original layout, formatting when saving, or completion and a structured editor later.
