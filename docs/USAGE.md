# Usage and limitations

## Environment

TotalDebug and Companion support Minecraft 1.21.1, NeoForge 21.1, Windows x64 and Java 21. Companion uses the Java compiler and debugger modules, so install a full JDK. TotalDebug launches Companion with Minecraft's Java runtime.

## Source navigation

Press F6 over a block, entity or inventory item to open its runtime class. Companion provides class and member search, Find Usages, hierarchy navigation and archive resources.

Companion stays open when Minecraft exits and reconnects when a matching TotalDebug client starts. Cached source, search and reference navigation remain available offline while the referenced archives and Java installation are present.

The index describes selected runtime archives and prepared class files. It does not reconstruct every transformation inside the running JVM. Decompiled source can differ from original source, and generated local names do not replace missing debugger metadata.

## Projects

Companion remembers one project per Minecraft instance and keeps one selected at a time. Scripts, watches, history and caches remain in that instance's existing `total-debug` directory. Standalone startup reopens the selected project, including cached source access while Minecraft is offline.

F6 or an explicit source-open request from another game selects its project before connecting. An ordinary handshake cannot replace the selected project. Selection uses an authenticated loopback request separate from the occupied game socket; it does not depend on the optional MCP host. Companion protocol 15 requires a matching mod/Companion pair.

Switching saves and closes project editors; a failed save prevents the switch. It detaches the debugger, requests cancellation of owned execution jobs, clears project views and pending results, and restores the selected instance's state. Minecraft processes remain running, and the existing MCP endpoint stays available. Disconnection does not prove arbitrary target code has stopped.

If remembering the selection fails, the new project remains open and Companion reports the save error. Selecting it again retries persistence; until then, restarting reopens the last successfully remembered project.

The application API supports listing known projects and opening a `CompanionProfile`, including one created with `CompanionProfile.forGame(path)`. Project-selector UI, project MCP tools, launcher controls and restoring open tabs are subsequent work.

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
