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

- `status` returns `companion_available` and `minecraft_connected`.
- Job responses return `job_id`, `state`, and any available `output`, `result`, or `error`.
- A sidecar connection failure returns one `error` object with `code`, `stage`, `endpoint_health`, and `retryable`.
- Full job provenance remains available through the `job` artifact.

## Tools

- `status` reports whether Companion and Minecraft are connected.
- `code_execute` runs Java statements in the Minecraft JVM. It waits up to 10 seconds by default and accepts `wait_ms` up to 120 seconds.
- `jobs_get`, `jobs_wait`, `jobs_list`, and `jobs_cancel` inspect, wait for, list, and cooperatively cancel code jobs.
- `search_classes` resolves an exact binary name first, then performs a package-aware class-name search. Results contain decoded class kinds and modifiers instead of numeric access flags.
- `class_source` returns decompiled Java source for one exact binary name.
- `class_bytecode` returns ASM text for the runtime class bytes.
- `class_origin` returns the logical source, resource name, and runtime module that supplied the class.
- `class_members` returns declared fields and methods with descriptors, signatures, decoded modifiers, and declared exceptions.
- `artifacts_read` returns the exact generated source or full job record for a job ID.

`code_execute` accepts imports, client or server execution, and worker-thread or tick-thread scheduling. Submitted code can call `result(value)` to return JSON-serializable structured data. `log` and `logln` remain available for supplemental text. A failed job preserves output and structured results produced before the exception.

There is no sandbox. Code can read or mutate anything available to the Minecraft process. Server-side execution follows TotalDebug's server script policy. Cancellation is cooperative. Code already running on a tick thread cannot be interrupted safely.

## Proof and artifacts

Companion stores the exact merged source and full job record under `<companion-app-home>/mcp/artifacts/<job-id>`. The full record contains source hashes and sizes, timestamps, execution settings, runtime provenance, output, structured result, error, and artifact paths. These values stay out of normal tool responses because `artifacts_read` already addresses them by job ID.

The MCP layer does not execute Java. It sends the existing authenticated SCNet script messages to TotalDebug, which compiles and runs the source inside Minecraft. Companion owns indexing, job coordination, and retained evidence. The game process owns runtime authority.

## Codex sidecar

Launch the sidecar from the shaded Companion JAR:

```text
java -cp TotalDebugCompanion.jar com.github.minecraft_ta.totalDebugCompanion.mcp.CompanionMcpSidecar
```

The sidecar owns Codex's long-lived stdio MCP session. It does not start Companion. `status` succeeds while Companion is offline and returns both booleans as `false`. Operational tools return a retryable connection error. When Companion starts later, the next call connects without restarting Codex. The sidecar also pings an existing HTTP session before forwarding each call, which detects a Companion restart.

The HTTP server and sidecar load one tool catalog from the same JAR. Installing a JAR with changed schemas still requires a new Codex task because an already-running sidecar retains the catalog it loaded at startup.

## Deferred runtime profiling

Runtime profiling is intentionally not part of the current MCP implementation. A prototype drove JFR by submitting generated Java through `code_execute`. That proved JFR can collect execution and allocation samples, but it put profiler control in the wrong layer. Every start, query, and stop operation required script compilation. Recording ownership relied on global JFR names, and snapshots crossed the boundary through temporary files.

The preferred design is a persistent profiling service inside TotalDebug's Minecraft process. Companion should send dedicated start, snapshot, and stop messages over SCNet and expose small MCP tools over that service. The first version should allow one active process-wide profile and require no tuning settings. Keeping profile state in the game lets a restarted Companion reconnect to an active recording. A Minecraft restart ends the recording because it ends the profiled JVM.

JFR is the right first backend for broad CPU hotspot sampling and sampled allocation estimates. Exact invocation counts or timings for named methods are a separate feature and may justify targeted instrumentation later. A Java agent is not needed for the initial sampler. Spark or async-profiler ingestion can remain optional if native stack profiling or flame graphs become necessary.

Before implementation, settle the result shape, whether blocked-thread events belong in the first version, and whether one active profile is sufficient. No profiling MCP tools are shipped yet.
