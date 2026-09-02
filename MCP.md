# TotalDebug Companion MCP

The persistent Companion process hosts a Streamable HTTP MCP server at `http://127.0.0.1:32123/mcp`. The port is stable, the endpoint is loopback-only, and this trusted local developer endpoint has no token. Companion keeps MCP available while Minecraft is offline. Runtime tools become usable when Minecraft connects.

Codex launches `CompanionMcpSidecar` over stdio instead of connecting to HTTP directly. The sidecar publishes the tool catalog even when Companion is absent. It reconnects before each call, so one Codex task can outlive Companion and Minecraft starts, stops, and restarts.

The active endpoint is written to `<companion-app-home>/mcp-endpoint.json`:

```json
{
  "schema_version": 2,
  "transport": "streamable-http",
  "url": "http://127.0.0.1:32123/mcp",
  "process_id": 1234,
  "started_at": "2026-08-23T12:00:00Z"
}
```

Companion removes the descriptor when it closes. A Minecraft disconnect does not stop MCP.

## Response policy

Each tool returns only the values needed to use that tool. Runtime paths, hashes, profile metadata, timestamps, and artifact locations do not appear in normal status or job responses.

- `status` returns `companion_available`, `minecraft_connected`, and `debugger_connected`.
- Job responses return `job_id`, `state`, and any available `logs`, `result`, or `error`.
- A sidecar connection failure returns one `error` object with `code`, `stage`, `endpoint_health`, and `retryable`.

Every tool advertises an `outputSchema` covering its exact success result and the shared structured error result. Companion returns `structuredContent` and otherwise keeps MCP Java's default structured-output handling, including its equivalent JSON text block for clients that need it. SDK input validation remains disabled because its failures are text-only. The shared tool catalog validates the advertised input schemas inside both the HTTP server and sidecar, so invalid calls return structured errors.

## Tools

- `status` reports Companion, Minecraft, and debugger connectivity.
- `client_code_execute` runs a value-returning Java body in the Minecraft client JVM.
- `server_code_execute` runs the same contract with server authority.
- `job_wait` waits for one asynchronous code job.
- `job_cancel` requests cancellation of one code job.
- `job_source` returns the exact generated Java source for one job.
- `search_classes` resolves an exact binary name first, then performs a case-insensitive literal search over complete binary names. Results include decoded class kind and modifiers plus the owning runtime module's `id`, `name`, and `kind`.
- `runtime_source` returns one exact class, method, field, or record-component source scope. Package and imports are separate metadata rather than part of the source snippet.
- `search_symbols` searches fields and methods globally by literal name, or lists the declarations of one exact owner. Results contain the exact owner, name, JVM descriptor, decoded modifiers, and runtime module.
- `find_usages` accepts an exact class, field, or method target and returns declaration sites, semantic relationships, occurrence counts, runtime modules, and whether the result was truncated. Each result contains a `source_target` accepted unchanged by `runtime_source`.
- `search_literals` searches indexed Java string values and returns the modules containing each value.

Literal searches return at most 100 matches. Class and symbol searches use the same fixed cap. A search adds `truncated: true` only when additional matches were omitted.

For source investigations, resolve the exact symbol, call `find_usages`, and pass only relevant `source_target` values to `runtime_source`. Request class scope when member source does not contain enough surrounding state or initialization logic.

Execution tools accept imports, worker-thread or tick-thread scheduling, and `wait_ms` up to 120 seconds. The submitted body must return its JSON-serializable value directly:

```java
logln("inspecting runtime");
return Map.of("players", getServerPlayers().size());
```

Return collections, maps, arrays, records, numbers, booleans, and strings as their real Java values. Formatting a collection or object with `toString()` intentionally produces one scalar string and discards its structure.

`log` and `logln` write the optional `logs` field. A failed job preserves logs written before the exception. Editor scripts and MCP jobs share one body-snippet contract: Companion generates the hidden `ScriptProgram` subclass and `Object run()` entry point, while the submitted source contains only imports and statements. The game serializes an explicit return value as the structured result.

Trusted snippets can directly use non-public fields, methods, and constructors on application types that are themselves accessible. The compiler and runtime linker provide that access without a reflection helper in the submitted code; inaccessible private types remain inaccessible.

There is no sandbox. Code can read or mutate anything available to the Minecraft process. Server-side execution follows TotalDebug's server script policy. Cancellation is cooperative. Code already running on a tick thread cannot be interrupted safely.

## Proof and artifacts

Companion retains the exact merged source and its internal job record under `<companion-app-home>/mcp/artifacts/<job-id>`. Only the source is public through MCP, addressed by `job_id`; internal paths and provenance are not tool output.

The MCP layer does not execute Java. It sends the existing authenticated SCNet script messages to TotalDebug, which compiles and runs the source inside Minecraft. Companion owns indexing, job coordination, and retained evidence. The game process owns runtime authority.

## Codex sidecar

Launch the sidecar from the shaded Companion JAR:

```text
java -cp TotalDebugCompanion.jar com.github.minecraft_ta.totalDebugCompanion.mcp.CompanionMcpSidecar
```

The sidecar owns Codex's long-lived stdio MCP session. It does not start Companion. `status` succeeds while Companion is offline and returns all three booleans as `false`. Operational tools return a retryable connection error. When Companion starts later, the next call connects without restarting Codex. The sidecar also pings an existing HTTP session before forwarding each call, which detects a Companion restart.

The HTTP server and sidecar load one tool catalog from the same JAR. Installing a JAR with changed schemas still requires a new Codex task because an already-running sidecar retains the catalog it loaded at startup.

## Deferred runtime profiling

Runtime profiling is intentionally not part of the current MCP implementation. A prototype drove JFR by submitting generated Java through the execution tool. That proved JFR can collect execution and allocation samples, but it put profiler control in the wrong layer. Every start, query, and stop operation required script compilation. Recording ownership relied on global JFR names, and snapshots crossed the boundary through temporary files.

The preferred design is a persistent profiling service inside TotalDebug's Minecraft process. Companion should send dedicated start, snapshot, and stop messages over SCNet and expose small MCP tools over that service. The first version should allow one active process-wide profile and require no tuning settings. Keeping profile state in the game lets a restarted Companion reconnect to an active recording. A Minecraft restart ends the recording because it ends the profiled JVM.

JFR is the right first backend for broad CPU hotspot sampling and sampled allocation estimates. Exact invocation counts or timings for named methods are a separate feature and may justify targeted instrumentation later. A Java agent is not needed for the initial sampler. Spark or async-profiler ingestion can remain optional if native stack profiling or flame graphs become necessary.

Before implementation, settle the result shape, whether blocked-thread events belong in the first version, and whether one active profile is sufficient. No profiling MCP tools are shipped yet.
