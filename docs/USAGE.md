# Usage and limitations

## Environment

TotalDebug and Companion support Minecraft 1.21.1, NeoForge 21.1, Windows x64 and Java 21. Companion uses the Java compiler and debugger modules, so install a full JDK. TotalDebug launches Companion with Minecraft's Java runtime.

## Source navigation

Press F6 over a block, entity or inventory item to open its runtime class. Companion provides class and member search, Find Usages, hierarchy navigation and archive resources.

Companion stays open when Minecraft exits. With the game's project selected, TotalDebug connects automatically whether Minecraft or Companion starts first, and reconnects after Companion restarts. Discovery watches the local application endpoint files; background connection never launches or focuses Companion or changes its selected project. Cached source, search and reference navigation remain available offline while the referenced archives and Java installation are present.

The index describes selected runtime archives and prepared class files. It does not reconstruct every transformation inside the running JVM. Decompiled source can differ from original source, and generated local names do not replace missing debugger metadata.

## Projects

Companion remembers one project per Minecraft instance and keeps one selected at a time. Scripts, watches, history and caches remain in that instance's existing `total-debug` directory. Standalone startup reopens the selected project, including cached source access while Minecraft is offline.

F6 or an explicit source-open request from another game selects its project before connecting. An ordinary handshake cannot replace the selected project. Selection uses an authenticated loopback request separate from the occupied game socket; it does not depend on the optional MCP host. Companion protocol 16 requires a matching mod/Companion pair.

The Game popup provides an icon-only Reconnect action for the selected project. It republishes discovery information and resets the active connection while keeping indexed browsing available. Pending means the authenticated connection has not completed; after 30 seconds without a matching game, the popup reports that timeout and permits another attempt. It does not claim Minecraft crashed. A stale action from another project is rejected. If discovery publication fails before disconnecting, the existing connection remains usable and the failure appears in notifications.

The top-bar Play button launches the selected Prism instance using its existing account, Java, and instance settings. Companion identifies the owning Prism data directory and launches by instance folder name. Installed and running Prism executables, portable layouts, and PATH are checked automatically; if Prism cannot be found, open Prism and try again. Unsupported projects keep Play disabled with an explanatory tooltip.

Starting remains pending until that instance authenticates with Companion. Prism's command process may exit after forwarding the request to an existing launcher; that does not complete the launch. Duplicate clicks are disabled. After ten minutes without a connection, Companion reports that no connection arrived and allows another attempt; check Prism for sign-in or launch errors. Switching projects or closing Companion cancels the pending intent without terminating Prism or Minecraft. Play does not attach the debugger.

Switching saves and closes project editors; a failed save prevents the switch. It detaches the debugger, requests cancellation of owned execution jobs, clears project views and pending results, and restores the selected instance's state. Minecraft processes remain running, and the existing MCP endpoint stays available. Disconnection does not prove arbitrary target code has stopped. See [ownership](../companion/README.md#ownership) for the switch phases and resource lifetimes.

If remembering the selection fails, the new project remains open and Companion reports the save error. Selecting it again retries persistence; until then, restarting reopens the last successfully remembered project.

The project menu at the top left opens a Minecraft directory or an instance found in the default Prism installation. The selected project appears above Projects, the complete remembered list ordered by recent use. Game connectivity remains in the status bar. Names come from the instance folder above `minecraft` or `.minecraft`; development `run` folders include their parent name. Rename is optional, and Reset to automatic name removes the override. Removing a project never deletes its files; the currently selected project cannot be removed.

The Prism picker shows instance artwork, Minecraft and loader versions, with the current project first. Search filters by name or version. Double-click an instance or select it and press Enter to open its project in Companion. This does not launch Minecraft or change Prism's configuration.

Open accepts an instance directory, its `mods/` or `total-debug/` child, or a Prism instance directory. The instance needs a mods directory or saved TotalDebug project data. Unsupported folders are rejected before creating files or changing projects. Opening an empty instance creates no scripts or state file.

Companion first restores a usable saved runtime snapshot. Otherwise it shows the installed mod archives and builds a local index in the background. Textures, JSON and other archive resources can be opened before indexing finishes, and their tabs stay open when the index becomes ready. Local class search, decompilation and usages cover top-level enabled mod JARs and the Java runtime. Embedded dependencies and Minecraft's loader transformations are not reconstructed. Broken archives and duplicate classes are reported; duplicates resolve in filename order.

The status bar distinguishes a local index from a runtime index. A local index supports inspection, not script execution. Minecraft's published runtime replaces it when ready and remains available after disconnection. Reopening the same offline project rescans its mod files. Detecting a changed archive during source reading retires the stale index and requests a rescan. A failed runtime refresh preserves an existing local view. See the [implementation plan](PROJECT_OPENING_PLAN.md) for scope and validation.

The Notifications button retains the last 100 operation outcomes for the current Companion session. Open it to read or copy details, dismiss an entry, or clear history. Closing a source tab does not remove its notifications. Open source uses the recorded location in the originating project; it opens the current file rather than a historical copy. Repeated autosave failures update one entry until a save succeeds.

Script activity is separate from notification history. Its popup lists active scripts and their Stop buttons; closing an editor requests cancellation. Game, MCP, index, and debugger status popups expose selectable details and Copy. Index status remains accessible while indexing, with Retry available after failure. A listening MCP server's copied detail is its endpoint URL.

The header uses the project name and the shared dropdown SVG. Project rows use readable names and muted paths without logos or initials. Right-click a project for rename, reset and removal actions. The MCP tools `project_list` and `project_open` use the same project selection backend. Selecting a project advertises it to an already-running matching game. Other launcher integrations and restoring open tabs remain subsequent work.

## Scripts and evaluation

Saved scripts contain imports and Java statements. Use `return` to produce a structured value, and `log` or `logln` for output. Companion compiles scripts using its existing runtime index and sends the generated classes to Minecraft for execution. Wait for the current runtime index to become ready before running a script.

The client and server execution choices target their respective game contexts. Integrated and dedicated servers publish an ordered archive baseline after joining. Companion compares archive hashes using the existing client files and index. Matching archives need only local class entry names. Companion requests declaration fingerprints for differing or unmatched server sources, one source at a time, then compares the actual winning class definitions in source order. Directories use the detailed path.

The server hashes its archives in the background once per server lifetime. Requested source details are calculated lazily and cached once for all players as compressed metadata. Each player connection has a fresh handshake identity. Minecraft relays the comparison messages and retains only the baseline for opening Companion later. Each Companion retains its own unsupported-class names, bound to the current server session and client inventory; temporary class fingerprints are discarded. There is no second index or automatic download of server class files.

Server compilation waits for the entire comparison to finish. Javac then checks that local set when reading a class, with no network requests or fingerprint calculation during compilation. Missing classes and changed declarations fail with the class name. Method implementations may differ, but fields, signatures, inheritance, access, generic metadata and compile-time constants must match. This is an exact class-level check, so even an unused declaration change can reject that class. Comparison and compilation share one worker. During joining, reconnecting or index replacement, client compilations may wait behind comparison work. Compilation uses the local result once comparison finishes.

Disconnecting invalidates pending server compilations, and the receiving server rejects bytecode carrying an old identity. Reopening Companion or replacing its client index repeats comparison against the retained baseline; delayed replies from older comparisons cannot complete the new one. The server keeps shared source details until that server runtime ends.

The comparison describes prepared filesystem class files, not final post-Mixin or agent-transformed definitions. It does not guarantee identical behavior or validate types named dynamically through reflection. Server-only types remain unavailable to client-index completion. Source inspection and remote debugger support are separate from compilation.

Server execution follows the server's script configuration and operator restrictions. Install matching TotalDebug builds on both endpoints and use the matching Companion build. Compiled scripts are limited to 1 MiB, with a 30,000-byte compressed limit for server runs.

Evaluate Everywhere supports expressions and compiled Java statement bodies. The interpreter supports common Java operations but is not a complete Java compile-time binder. Generic overload binding and some conditional type inference can differ from compiler behavior.

Scripts and evaluation execute in the target process. They can mutate game state, and an exception does not undo earlier mutations. Stop requests cooperative cancellation; code that ignores interruption remains running until it returns. Closing a result panel or disconnecting does not prove that target code stopped.

## Debugger

Debugger attachment requires a reachable JDWP listener on the Minecraft client JVM. The integrated server shares that JVM; a separate dedicated server is not a Companion debugger target.

Method invocation requires a thread suspended by a debugger event, such as a breakpoint. Manual suspension can permit plain value inspection while method invocation fails.

Compiled evaluation requires the prepared target helper, an application class loader, usable local/type metadata and the matching runtime classpath. Inaccessible or unnamed binding types, ambiguous loader definitions, nested type declarations, lexical `super` and flow-scoped patterns are unsupported in compiled frame adaptation. Bootstrap-loaded JDK frames and generated script receivers absent from the classpath cannot be compiled in that context.

**Set Value** accepts the debugger adapter's typed value syntax. Use evaluation for executable Java expressions or statements.

Resume and detach expire live references from that pause. Evaluation history keeps at most 128 operations per owner; open inspectors and breakpoint actions can retain additional results. This limits records, not the size of target object graphs.

## Local integrations

Companion exposes source navigation, script execution and debugger operations through its [MCP API](../companion/MCP.md). Use it with trusted local clients.

The transport limits payloads to 16 MiB by default and queues at most 1024 accepted messages per connection. A receiver that cannot keep up causes a reported connection failure. Messages are not replayed after reconnect.

Index snapshots are generated caches. They are not a supported format for importing arbitrary untrusted files. See [storage](STORAGE.md) for cache ownership and recovery.
