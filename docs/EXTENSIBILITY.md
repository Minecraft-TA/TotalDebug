# Extensibility

Status: design proposal, recorded 2026-09-24. Nothing here is implemented beyond the current script tools unless it says so. It changes script and evaluator architecture, so each phase still needs an explicit decision before it starts.

## Goal

Everything specific to a mod, a pack or a user's own interest is code that runs dynamically: written, compiled and replaced inside Companion without relaunching Minecraft or Companion. This covers readers, actions, custom windows, custom rendering, renders taken from the game, offline answers and simulation.

TotalDebug and Companion provide the infrastructure that makes this possible. They contain no knowledge about particular mods, blocks or behavior.

## The dividing rule

For any piece of code, ask whether a different pack, mod or user could reasonably want different code there.

- **Yes:** it is an extension. Examples: how a Mekanism machine explains its sides, how a Create block renders, what a Botania flower's page shows, how a setup is simulated.
- **No, every extension needs it:** it is kernel. Examples: loading code, talking between JVMs, drawing a tab, capturing resources, compiling.

Built-in features are written as extensions through the same API. If a shipped feature cannot be expressed that way, the API is missing something.

## Hosts

Extension code runs in three places:

| Host | Typical extension work |
|---|---|
| Game server, dedicated or integrated | Read authoritative state, act on the world, sample ticks and events, run simulations |
| Game client | Read client state, render items, blocks or entities in a real frame, capture resources, use client-only mod APIs |
| Companion | Windows and panels, custom fact widgets, offline rendering, offline answers, analysis, agent tools |

## Kernel boundary

### Mod kernel

- Transport, authentication and permission policy.
- Receiving, defining and owning extension code:
  - one class loader per module build,
  - install, replace and unload,
  - a lifecycle context through which every registration happens, so that unloading removes it.
- Tick scheduling on the client and server.
- Primitive subjects (block position, entity, stack) and their resolution without loading chunks.
- Services that must be native:
  - resource capture,
  - render capture (an offscreen image of an item, block or entity),
  - item icon model and tint lookup,
  - raw object capture.
- Input hooks, which only report that a subject was selected.

### Companion kernel

- Projects, storage, the runtime index and the compiler.
- The extension manager: resolve module dependencies, compile, load, hot-replace, report diagnostics, and isolate failures.
- The UI shell with extension points: tabs, dockable windows, subject views, toolbar, menus, context actions and status bar.
- The view model and its renderer: facts today, growing into declarative views, with a registry for presenters of extension-defined fact kinds.
- The render pipeline:
  - the offline renderer core,
  - a model-loader extension point,
  - a fallback to render capture in the game.
- The offline store: data captured per project, versioned by runtime inventory.
- Channels: typed messages and streams between the halves of a module, across JVMs.
- The MCP bridge, source browsing and the debugger.

## Current code, sorted

| Today | Location | Proposed role |
|---|---|---|
| `StorageReader`, `CapabilityReader`, `NbtReader` | mod `inspection` | Bundled reader module |
| Reader snippet in `InspectionPanel.readerSource` | Companion | Removed; the page runs every applicable reader |
| `// inspect:` directive in `InspectionTool` | Companion | Manifest applicability, read by the kernel |
| JEI hovered-stack resolver | mod `client/integration/jei` | Module |
| Fusion and OBJ item render integrations | Companion `itemrender/integration` | Model-loader modules |
| `neoforge:fluid_container` compositor | Companion renderer | Bundled model-loader module |
| Fluid appearance capture in `ResourceSnapshots` | mod | Capture module; the snapshot mechanism stays kernel |
| Loot-table guard, capability walking, NBT conversion | mod | Standard library module |
| Subject references, `target()`, `facts()`, script runner, transport, permissions, scheduling | mod and protocol | Kernel |
| Resource snapshots, `ItemIcons`, raw value capture | mod | Kernel services |
| Compiler, `ItemIconService`, facts renderer, inspection page shell, live refresh, editor, debugger, MCP | Companion | Kernel |

## Two API layers

### Kernel API

It contains only what the kernel calls into or guarantees:

- the extension-point interfaces,
- the lifecycle context,
- `target()`, `facts()` and subject types,
- the rules for records that cross a JVM boundary.

It is hard code in the mod and Companion JARs, versioned and kept small. It cannot be hot-reloaded: every extension class loader delegates to it, and it must exist before any extension loads.

### Standard library

Everything convenient is an ordinary module that can be hot-reloaded, read and forked:

- **`td-std`:** loot-safe container reads, capability iteration, NBT conversion, side utilities, formatting and common fact layouts.
- **`td-render`:** model-loader helpers, texture tinting and fluid composition.
- Libraries for particular mods build on these.

**Rule:** if the kernel neither calls a piece of code nor has to guarantee its behavior, it belongs in a library. When a library needs a guarantee, the missing piece is a small kernel primitive, not the library.

## Extension points

### Game side

| Point | Purpose |
|---|---|
| `SubjectReader` | Subject to facts. The current script tools are the smallest form. |
| `Action` | Named operation on a subject with arguments, offered as a button and to agents |
| `Stream` | Values sampled every N ticks or on an event, pushed to Companion |
| `SubjectKind` | New kinds of subjects, such as a network, multiblock or contraption |
| `Capture` | Data exported for offline use: recipes, configuration, appearances, structures |
| Simulation host | A setup run under stated conditions; needs its own design |

### Companion side

| Point | Purpose |
|---|---|
| `SubjectView` | The page for a subject kind; the facts page is the default |
| `FactPresenter` | A widget for an extension-defined fact kind |
| Window or panel | Declarative views first, Swing when needed |
| `ModelLoader` | An offline renderer for a model format |
| `OfflineReader`, `Simulator` | Answers from the offline store without a running game |
| Command, context action, MCP tool | Entry points into an extension |

Extensions may still call any Minecraft, mod or Companion class directly. The API covers what TotalDebug mediates; it does not wrap Minecraft.

## Modules

There is one concept at different sizes:

- **Snippet:** a single `.tdscript` file. It remains the smallest module: one game-side function, run once or as a reader.
- **Module:** a folder with sources, resources and a manifest. It can have up to three parts:
  - `game/`, declaring whether it runs on the server, the client or both,
  - `desktop/`, running in Companion,
  - `shared/`, holding records both parts exchange.
- **Library:** a module that other modules depend on.

The manifest declares:

- the module id and version, and the kernel API version it targets,
- its dependencies on other modules,
- the mods and version ranges it expects,
- the game side its game part runs on,
- the subjects its readers apply to,
- whether its actions change the world,
- whether anything in it requires a restart.

Companion compiles every part against the pack's runtime index. A pack update recompiles all modules and reports failures as checks rather than as crashes in the game.

Bundled modules ship as source and are compiled like user modules. This keeps the API sufficient for real features and lets users copy and change them.

## Three levels of modules

1. **Game-only.** Readers, actions and streams produce facts, links, actions and values that the kernel presents. No desktop code is involved. Most integrations fit here.
2. **Desktop-only.** Model loaders, offline readers and windows over the offline store. They need no game connection.
3. **Mixed.** Custom Companion UI driven by live game data. Only this level has two parts talking through a channel.

Levels 1 and 2 come first. They avoid cross-JVM programming entirely.

## Mixed execution

Game parts use the route scripts use today. Companion compiles the code; the client runs it or forwards it to the server; replies return through the forwarded payload channel. A module's game part is installed through that route instead of being run once, and it keeps a channel open. Direct server access later replaces the route without changing module code.

To an author, a mixed module looks like this:

```java
// shared: compiled once, loaded separately in each JVM
public record SideConfig(Map<Direction, Mode> modes) {}

// game: runs on the server thread
@Remote public SideConfig read(ScriptTarget target) { ... }
@Remote public void set(ScriptTarget target, Direction face, Mode mode) { ... }
@Stream(ticks = 10) public SideConfig watch(ScriptTarget target) { return read(target); }

// desktop: runs on the event dispatch thread
game.call(SideConfigService::read, subject).thenAccept(this::show);
game.stream(SideConfigService::watch, subject, this::show);
```

The kernel handles the following once for every module:

- **Serialization:** shared records may contain primitives, strings, enums, lists, maps and nested records, the same limits as facts. Other types are rejected when the module compiles.
- **Threads:** game methods run on the owning game thread and desktop callbacks run on the event dispatch thread.
- **Versions:** each installation carries a module build id. Replies to an older build are discarded after a replacement.
- **Placement:** in singleplayer both game sides share a JVM but keep separate class loaders and threads, as on a dedicated server.
- **Failures:** a call completes with its reason, such as no connection, a world that was left or missing permission. A desktop part stays loaded while the game is gone and can fall back to an offline reader.
- **Streams:** only the latest value is kept, so a slow Companion never delays the server tick. Recorded streams are the basis for history and replay.
- **Lifecycle order:** install shared, then game, then desktop parts; unload in reverse. A game part that fails to install leaves the previous build running and reports why.

## Hot replacement limits

Replacement is clean only for what an extension registers through its lifecycle context.

- **State:** replacing a module drops its state unless the module saves and restores it explicitly. The previous build keeps running until the new build has compiled and started.
- **Requires restart:** some changes cannot be made live, such as additions to frozen registries or mixins applied at class load. Modules that need them are marked as requiring a restart, and the manager shows this instead of pretending they are dynamic.
- **Runtime class patching:** allowed as a privileged kind of extension. Restoring bytecode does not undo effects that already happened in the world, and the manager states this.

## Trust

- A user's own modules run with full trust.
- **Modules from others show what they declare** before they run:
  - which sides they run on,
  - which actions change the world,
  - whether they need server operator permission.
- Server-side parts follow the server's script policy.

## Migration order

1. Settle the module format and the kernel boundary in this document.
2. Add folders, manifests and library dependencies. Move the three readers into a bundled module, replacing the hard-coded reader snippet and the `// inspect:` directive.
3. Add the installed lifecycle in the game: the owned context, channels and streams. Live refresh moves from polling to pushed values.
4. Add Companion extension points: fact presenters and declarative panels, then Swing windows.
5. Add rendering extension points: move the Fusion and OBJ loaders out and add render capture in the game.
6. Add the offline store and offline readers, then design the simulation host.

Before freezing the API, port at least these through it:

- the bundled readers,
- the Fusion model loader,
- the JEI hover integration,
- one new mixed module: a side-configuration view with an action for a mod that supports configurable sides.

## Open decisions

- **Distribution:** modules per project only, or also a user-level library shared across projects that projects reference by version.
- **Channels:** remote calls and streams from the start, or streams first.
- **Bundled modules:** compiled from source inside the Companion JAR, or published as a forkable user-level library.
- **Unauthored desktop parts:** whether Companion runs them without asking, which decides how much permission UI is needed.
