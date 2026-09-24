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
| Pack catalog capture of mods, registries and configuration files | mod and storage | Kernel capture |
| Mod and definition subjects, mod and definition pages, Mods tree, catalog search | protocol and Companion | Kernel |
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

## Worked example: AE2 item flow

This example shows the intended result end to end. The AE2 calls and parts of the TotalDebug API are illustrative, not settled signatures. The question it answers is: how much redstone does my network produce, and how much will I have in a given time?

### Experience

1. **Open the network.** F6 on an ME controller, cable or terminal opens the **ME Network** page, because the module adds the subject kind `ae2:grid`. The page shows:
   - stored items as a searchable slot grid,
   - power and channels,
   - crafting CPUs.
2. **Open an item's flow.** Selecting Redstone opens the **Flow** view for `ae2:grid/minecraft:redstone`:

```
ME Network · Base (controller 120 64 -35) ▸ Redstone                 [Live] [1 h]
Redstone                                                        Stock 184,302

Net +1,240 /min (+74,400 /h)    In 1,510 /min    Out 270 /min
Stock over the last hour: sparkline                     observed, 3,600 samples

Sources                                      Sinks
Import Bus  Mining line       +1,020/min     Assembler  Repeaters        -180/min
Interface   Ore washing         +410/min     Export Bus Smelter           -90/min
Crafting    dust -> block        +80/min

Target 1,000,000 reached in about 11 h at the current net rate      (projection)
How much in 30 min? about 37,200 more, ±8% over the last hour       (projection)

[Record] [Save as check] [Pin to HUD]
```

3. **Use the view:**
   - Sources and sinks open the corresponding bus, interface, crafting job or pattern as their own subjects.
   - **Record** stores the stream in the project for replay and before/after comparison.
   - **Save as check** turns a condition such as "net rate at least 1,000 per minute" into a check that runs after pack updates or on demand.
   - **Pin to HUD** shows the net rate in the game through the kernel's HUD extension point.

### Evidence

- Net, in and out rates are **observed** from sampled stock and recorded transfers.
- Attribution to sources and sinks requires the module's transfer hook. Without it, the view shows only the net rate and states that sources are unknown.
- Targets and "how much in" answers are **projections** from the current window and say so.

### Module

```
modules/ae2-flow/
  module.toml
  shared/   FlowSample.java, FlowSource.java
  game/     GridKind.java, GridReader.java, FlowProbe.java, FlowService.java
  desktop/  FlowView.java, Ae2Terminal.java   (the AE2-styled panel is optional)
```

```toml
id = "ae2-flow"
version = "0.3.0"
api = "1"
requires = { ae2 = ">=19.0", td-std = "^1", td-charts = "^1" }

[game]
side = "server"
subjects = ["ae2:grid", "ae2:grid/*"]
actions = { changes-world = false }

[desktop]
views = ["ae2:grid", "ae2:grid/*"]
```

```java
// shared
public record FlowSource(String kind, String label, SubjectRef subject, long perMinute) {}
public record FlowSample(long tick, long stock, long in, long out, List<FlowSource> sources) {}

// game: F6 on any AE2 part resolves to its network
@SubjectKind("ae2:grid")
public Optional<SubjectRef> from(ScriptTarget.PlacedBlock block) {
    return Ae2.gridNodeAt(block).map(node -> SubjectRef.custom("ae2:grid", Ae2.gridId(node)));
}

// game: the network page, presented entirely by the kernel
@Reads("ae2:grid")
public void read(Ae2Grid grid, Facts facts) {
    facts.section("Network")
         .bar("Power", grid.storedPower(), grid.maxPower(), "AE")
         .text("Channels", grid.usedChannels() + " / " + grid.maxChannels());
    Facts.Section items = facts.section("Items");
    grid.storedItems().stream().sorted(byCountDesc()).limit(512)
        .forEach(stack -> items.stack(stack.name(), stack).link(subject("ae2:grid/" + stack.id())));
}

// game: one sample per second, pushed to Companion
@Stream(subject = "ae2:grid/*", everyTicks = 20)
public FlowSample sample(Ae2Grid grid, ItemKey item, ProbeContext context) {
    Ledger ledger = context.state(Ledger::new);
    return new FlowSample(context.gameTime(), grid.count(item),
            ledger.drainIn(item), ledger.drainOut(item), ledger.sources(item));
}

// game: attribute transfers; registered through the context, removed on replacement or stop
@OnInstall
public void hook(ModuleContext context) {
    context.onEvent(Ae2StorageEvent.class, event ->
            ledger(context).record(event.item(), event.amount(), event.actionSource()));
}

// game: requests made once by the view
@Remote public Projection project(Ae2Grid grid, ItemKey item, Duration window) { ... }

// desktop: a declarative view using the td-charts library
@View("ae2:grid/*")
public ViewModel flow(Subject subject, Channel game) {
    var samples = game.stream(FlowProbe::sample, subject).window(Duration.ofHours(1));
    return View.page(
            Header.item(subject.item()).value("Stock", samples.latest(FlowSample::stock)),
            Row.of(Stat.rate("Net", samples.netPerMinute()),
                    Stat.rate("In", samples.inPerMinute()),
                    Stat.rate("Out", samples.outPerMinute())),
            Chart.sparkline(samples.map(FlowSample::stock)).evidence(Evidence.OBSERVED),
            Table.of("Sources", samples.latest(FlowSample::sources), FlowSource::subject),
            Form.question("How much in", Duration.ofMinutes(30),
                    window -> game.call(FlowService::project, subject, window)),
            Actions.record(samples),
            Actions.check("Net rate", samples.netPerMinute(), ">= 1000"),
            Actions.pinToHud(Hud.line(subject.item(), samples.netPerMinute())));
}
```

### What the example exercises

| Piece | Kernel or module |
|---|---|
| F6, subject references, page shell, Live, tabs | Kernel |
| `ae2:grid` subject kind and network page | Module |
| Slot grid, icons, bars, tables, forms | Kernel renderer |
| Charts | `td-charts` library |
| Pushed samples | Kernel stream |
| Attribution of transfers | Module hook, owned by its context |
| Projections | Module remote call |
| Recording, replay and checks | Kernel |
| HUD overlay | Kernel extension point, module content |
| AE2-styled terminal panel | Optional mixed module part |
| Agent questions such as "redstone per hour" | Automatic through the MCP bridge |

Most of the module is game-only: a subject kind, a reader, a stream and a remote call rendered by the kernel. Only the optional AE2-styled panel needs custom Companion code.

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
- the [AE2 item flow](#worked-example-ae2-item-flow) module, which needs a new subject kind, streams, recording, a remote call, a declarative view and an optional custom panel.

## Open decisions

- **Distribution:** modules per project only, or also a user-level library shared across projects that projects reference by version.
- **Channels:** remote calls and streams from the start, or streams first.
- **Bundled modules:** compiled from source inside the Companion JAR, or published as a forkable user-level library.
- **Unauthored desktop parts:** whether Companion runs them without asking, which decides how much permission UI is needed.
