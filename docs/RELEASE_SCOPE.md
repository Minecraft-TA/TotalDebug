# Release scope and known limits

This is the scope for the first stable candidate, not a published release announcement. Final artifact pairing and live acceptance are still pending.

## Supported environment and features

The candidate targets Minecraft 1.21.1 with NeoForge 21.1, Windows x64 and Java 21. The Companion runtime requires the Java compiler and debugger modules. Linux/macOS application support and other Minecraft versions are outside this candidate.

The release includes source browsing and decompilation, class/member search, Find Usages and hierarchy navigation, client/server Java scripts, debugger attachment, Evaluate Everywhere, breakpoint actions, the local MCP endpoint and shared profile/storage support. The evaluator and compiled Code mode remain separate supported paths.

Packet logging/blocking, chunk-grid inspection, runtime class patching, item rendering and project switching are outside this release. Their absence is not a hidden runtime configuration option.

## Source and debugger behavior

The class index describes the selected runtime archives and prepared class files. It does not reconstruct every transformation applied inside the running JVM. Decompiler output is an aid to navigation; invented local names do not create real debugger local-variable metadata.

Debugger attachment requires a reachable JDWP listener. Compiled evaluation also needs usable frame/type metadata and the prepared target helper. Missing local metadata, inaccessible or unnamed binding types, ambiguous loader definitions and unsupported lexical contexts can require an explicit refusal before execution. Flow-scoped patterns remain unsupported in compiled frame adaptation.

Interpreted evaluation covers the tested declared-type overloads, numeric/boolean unboxing, string conversion and conditional promotion. It is not a complete Java compile-time binder. Generic/poly overload binding, constant-field folding and unrelated reference-conditional type inference remain outside the verified parity matrix. Code mode remains available where its frame requirements can be met.

Set Value uses the debugger adapter's typed value parser. It is not an arbitrary Java expression assignment field. Use evaluation or Code mode for executable Java within their supported contexts.

## Execution and retained results

Java evaluation and scripts execute in the target process with its access and side effects. They are not sandboxed, and failed execution does not roll back prior object mutations. The MCP endpoint is intended for trusted local clients; exposing it as a public service is outside this release's contract.

Stop requests cancellation. If target code ignores interruption, the operation remains cancellation-pending until it actually ends. Disconnecting a client or closing a result panel does not prove that its target code stopped.

Evaluation histories retain at most 128 operations per history owner. Open inspectors and the latest breakpoint action can retain their own results. Resume/detach expires references belonging to that pause. These ownership limits do not impose a byte limit on arbitrary target object graphs.

Transport payloads default to 16 MiB, and at most 1024 accepted messages may await complete transmission per connection. A receiver that cannot keep up eventually causes a reported connection failure. No queued message is replayed on reconnect.

## Candidate verification still required

Use the [release audit](RELEASE_AUDIT.md) for the live dev-instance and ATM10 acceptance matrix, including integrated/dedicated server behavior, reconnect/stop/close, offline restart and navigation. The [progress record](RELEASE_PROGRESS.md) distinguishes completed local checks from those remaining gates.

The independent ASM member oracle scans the captured corpus and compares all members declared by Block, Blocks, String and List. It does not establish independent parity for every target, annotation, generic class-reference site or literal. Snapshot integrity checks reject tested corrupt links/ranges and excessive signature nesting; arbitrary hostile snapshot allocation remains a hardening gap. These limits must remain visible when deciding the final candidate scope.
