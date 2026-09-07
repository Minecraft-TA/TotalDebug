# TotalDebug Companion MCP

The persistent Companion process hosts a Streamable HTTP MCP server at `http://127.0.0.1:32123/mcp`. The port is stable, the endpoint is loopback-only, and this trusted local developer endpoint has no token. Companion keeps MCP available while Minecraft is offline. Runtime tools become usable when Minecraft connects.

Codex launches `CompanionMcpSidecar` over stdio instead of connecting to HTTP directly. The sidecar publishes the tool catalog even when Companion is absent. It reconnects before each call, so one Codex task can outlive Companion and Minecraft starts, stops, and restarts.

The active endpoint is written to `<companion-app-home>/run/companion/mcp-endpoint.json`:

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

## Debugger tools

Debugger tools control the same session, breakpoints, and paused JVM as Companion's UI. They attach to the Minecraft process already published by TotalDebug, not an arbitrary PID or host. The integrated server is in that JVM; a separate dedicated server is not a debugger target yet.

- `debugger_status` returns `revision`, `phase`, the available `target`, and a `pause` summary when stopped. A pause contains its unique `id`, stop reason, thread ID, suspension scope, and top frame when available.
- `debugger_wait(after_revision, wait_ms)` waits for a state or breakpoint revision change, defaulting to 30 seconds and allowing up to 120 seconds. At timeout it returns the current snapshot. Waiting does not occupy the debugger command queue.
- `debugger_control(action, ...)` accepts `attach`, `detach`, `pause`, `continue`, `step_over`, `step_into`, or `step_out`. Pause requires `thread_id`. Continue and step require the exact `pause_id`. Pause and step wait up to 10 seconds by default; the other actions return after acknowledgement. `wait_ms` overrides that wait. Detach does not terminate Minecraft.
- `debugger_threads` lists attached JVM thread IDs and names.
- `debugger_breakpoints` lists the shared breakpoints and global mute state.
- `debugger_breakpoint_set(binary_name, line, condition?, hit_condition?, enabled?, action?)` deterministically creates or replaces one breakpoint. Omitted conditions are cleared and enabled defaults to true. `line` is the absolute displayed Vineflower line, not a line relative to a `runtime_source` member snippet. Use its `start_line` to calculate that absolute line. Source is loaded without opening an editor; method declarations use the same executable-line resolver as the UI.
- `debugger_breakpoint_remove(binary_name, line)` returns whether a breakpoint was removed.
- `debugger_frames(pause_id)` returns the stack of the thread that caused the stop, with frame IDs, method names, and available binary names and displayed lines.
- `debugger_variables(pause_id, frame_id | value_ref, start?, count?)` returns frame locals or one level of object/array children. The default page has 100 entries, the maximum is 500, and `truncated: true` means another page exists. Only returned expandable values have a `value_ref`.
- `debugger_evaluate(pause_id, frame_id, source, wait_ms)` evaluates an expression or a Java statement body. Bodies may use `return`; without a return they produce `void`. Imports can precede the source. The default wait is 1000 ms. The response contains `operation_id`, `state`, elapsed time and cancellation status, plus `result` or an execution `error` when complete.
- `debugger_evaluation_wait(operation_id, wait_ms)` waits on that same execution. It never runs the source again. `debugger_evaluation_cancel(operation_id)` requests cancellation between operations. A target method already executing must return before cancellation can take effect.
- Results contain display `value`, Java `type`, an optional live `value_ref`, and `scalar` for primitives, strings, null and void. Long scalar values are decimal strings; nonfinite floats are `NaN`, `Infinity` or `-Infinity` strings. Null and void have distinct scalar kinds.

Breakpoint actions accept either inline `source` or a saved `script` path relative to the active workspace scripts directory. They run after a true condition. The default `completion` is `stay_paused`; `continue_on_success` is explicit and requires a scalar result. A slow, failed, cancelled or invalidated action stays paused. An object result also stays paused and remains inspectable. Saved scripts are read again on every hit. `debugger_status.breakpoint_action` contains the latest result or error, not a log history.

```json
{
  "binary_name": "example.MyClass",
  "line": 42,
  "action": {
    "source": "localCount += 1; return localCount;",
    "completion": "stay_paused"
  }
}
```

Operation IDs reported in `debugger_status.evaluation` use the same wait/cancel tools, including automatic evaluations. Wait returns terminal status for those operations; breakpoint results appear in `debugger_status.breakpoint_action`.

Only one target evaluation runs at a time. Other evaluations, variable reads/writes and resume/step commands report busy. Status, cancellation and explicit detach remain available. A slow call never causes an automatic detach, resume or retry. Method calls and assignments can mutate Minecraft; cancellation does not undo them.

Compiled fragments require the matching preloaded TotalDebug helper, JDK 21 in Companion, prepared runtime classpath files and local-variable debug metadata. Ordinary loops, declarations, construction, lambda capture and local writeback are supported. Local writes survive a thrown exception. The current compiled adapter rejects inaccessible or unnamed frame types, ambiguous loader-specific classpath types, nested type declarations and lexical `super`. These are explicit capability limits, not interpreter fallbacks. Compilation happens in Companion before target invocation, without Minecraft ticks or an SCNet response.

The latest 128 operations in the current engine remain available by operation ID, including conditions, actions and previews. Explicit evaluations also retain their pause binding for result retrieval. Completed metadata survives resume; live results expire with their pause and return `result_expired: true`. For an investigation, inspect variables first, collect related facts in one fragment, and wait on its operation ID. Stop the probe sequence after a failure.

Pause IDs are unique for each stop and expire when execution resumes, the target changes, or the debugger detaches. Frame IDs and value references are accepted only with the current pause ID. A stale request fails explicitly, even if the adapter reuses the same numeric IDs. Breakpoints created through MCP are visible and persisted through the existing UI controller.

The sidecar's request deadline is 150 seconds so a 120-second wait can finish. TCP connection and initialization attempts remain bounded separately. Forwarded tool calls run concurrently, so a wait does not block status or control requests.

## Execution lifetime

Companion keeps the exact generated source, status, logs, result, and runtime context in the same in-memory job record. `job_source` reads that record. Completed records are evicted oldest-first when a new submission exceeds the 256-record retention target; active jobs are never evicted. An unknown or expired job reports an error. Restarting Companion discards all job records. No execution source or result files are written.

The MCP layer does not execute Java. It sends the existing authenticated SCNet script messages to TotalDebug, which compiles and runs the source inside Minecraft. Companion owns indexing and in-memory job coordination. The game process owns runtime authority.

## Codex sidecar

Launch the sidecar from the shaded Companion JAR:

```text
java -cp TotalDebugCompanion.jar com.github.minecraft_ta.totalDebugCompanion.mcp.CompanionMcpSidecar
```

The sidecar owns Codex's long-lived stdio MCP session. It does not start Companion. `status` succeeds while Companion is offline and returns all three booleans as `false`. Operational tools return a retryable connection error. When Companion starts later, the next call connects without restarting Codex. The sidecar also pings an existing HTTP session before forwarding each call, which detects a Companion restart.

The HTTP server and sidecar load one tool catalog from the same JAR. Installing a JAR with changed schemas still requires a new Codex task because an already-running sidecar retains the catalog it loaded at startup.

Set `tool_timeout_sec = 180` under `[mcp_servers.totaldebug-companion]` in Codex's `config.toml`. Its [default tool timeout is 60 seconds](https://developers.openai.com/codex/mcp), shorter than the supported 120-second waits.

## Deferred runtime profiling

Runtime profiling is intentionally not part of the current MCP implementation. A prototype drove JFR by submitting generated Java through the execution tool. That proved JFR can collect execution and allocation samples, but it put profiler control in the wrong layer. Every start, query, and stop operation required script compilation. Recording ownership relied on global JFR names, and snapshots crossed the boundary through temporary files.

The preferred design is a persistent profiling service inside TotalDebug's Minecraft process. Companion should send dedicated start, snapshot, and stop messages over SCNet and expose small MCP tools over that service. The first version should allow one active process-wide profile and require no tuning settings. Keeping profile state in the game lets a restarted Companion reconnect to an active recording. A Minecraft restart ends the recording because it ends the profiled JVM.

JFR is the right first backend for broad CPU hotspot sampling and sampled allocation estimates. Exact invocation counts or timings for named methods are a separate feature and may justify targeted instrumentation later. A Java agent is not needed for the initial sampler. Spark or async-profiler ingestion can remain optional if native stack profiling or flame graphs become necessary.

Before implementation, settle the result shape, whether blocked-thread events belong in the first version, and whether one active profile is sufficient. No profiling MCP tools are shipped yet.
