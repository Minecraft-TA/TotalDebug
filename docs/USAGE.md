# Usage and limitations

## Environment

TotalDebug and Companion support Minecraft 1.21.1, NeoForge 21.1, Windows x64 and Java 21. Companion uses the Java compiler and debugger modules, so install a full JDK. TotalDebug launches Companion with Minecraft's Java runtime.

## Source navigation

Press F6 over a block, entity or inventory item to open its runtime class. Companion provides class and member search, Find Usages, hierarchy navigation and archive resources.

Companion stays open when Minecraft exits and reconnects when a matching TotalDebug client starts. Cached source, search and reference navigation remain available offline while the referenced archives and Java installation are present.

The index describes selected runtime archives and prepared class files. It does not reconstruct every transformation inside the running JVM. Decompiled source can differ from original source, and generated local names do not replace missing debugger metadata.

## Scripts and evaluation

Saved scripts contain imports and Java statements. Use `return` to produce a structured value, and `log` or `logln` for output. The client and server execution choices target their respective game contexts. Server execution follows the server's script configuration and operator restrictions.

Evaluate Everywhere supports expressions and compiled Java statement bodies. The interpreter supports common Java operations but is not a complete Java compile-time binder. Generic overload binding and some conditional type inference can differ from compiler behavior.

Scripts and evaluation execute in the target process. They can mutate game state, and an exception does not undo earlier mutations. Stop requests cooperative cancellation; code that ignores interruption remains running until it returns. Closing a result panel or disconnecting does not prove that target code stopped.

## Debugger

Debugger attachment requires a reachable JDWP listener on the Minecraft client JVM. The integrated server shares that JVM; a separate dedicated server is not a Companion debugger target.

Method invocation requires a thread suspended by a debugger event, such as a breakpoint. Manual suspension can permit plain value inspection while method invocation fails.

Compiled evaluation requires the prepared target helper, an application class loader, usable local/type metadata and the matching runtime classpath. Inaccessible or unnamed binding types, ambiguous loader definitions, nested type declarations, lexical `super` and flow-scoped patterns are unsupported in compiled frame adaptation. Bootstrap-loaded JDK frames and generated script receivers absent from the classpath cannot be compiled in that context.

**Set Value** accepts the debugger adapter's typed value syntax. Use evaluation for executable Java expressions or statements.

Resume and detach expire live references from that pause. Evaluation history keeps at most 128 operations per owner; open inspectors and breakpoint actions can retain additional results. This limits records, not the size of target object graphs.

## Local integrations

Companion exposes source navigation, script execution and debugger operations through its [MCP API](https://github.com/Minecraft-TA/TotalDebugCompanion/blob/master/MCP.md). Use it with trusted local clients.

The transport limits payloads to 16 MiB by default and queues at most 1024 accepted messages per connection. A receiver that cannot keep up causes a reported connection failure. Messages are not replayed after reconnect.

Index snapshots are generated caches. They are not a supported format for importing arbitrary untrusted files. See [storage](STORAGE.md) for cache ownership and recovery.
