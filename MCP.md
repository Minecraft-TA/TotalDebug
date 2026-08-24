# TotalDebug Companion MCP

The persistent, single-instance Companion hosts a Streamable HTTP MCP server for its full lifetime. The server remains available while Minecraft is offline and listens at `http://127.0.0.1:32123/mcp`. Runtime execution becomes available only while an authenticated Minecraft session is connected.

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

The endpoint rejects non-loopback host and origin headers, and the descriptor is removed when the owning Companion process closes. A Minecraft disconnect does not stop MCP. This is a trusted local developer endpoint and does not require MCP authentication.

## Tools

- `status` reports the live Companion, loaded runtime profile, and Minecraft execution boundary.
- `code_execute` submits Java statements to the existing TotalDebug script compiler inside the Minecraft JVM. The tool returns immediately with a job UUID.
- `jobs_get`, `jobs_list`, and `jobs_cancel` inspect and cooperatively cancel jobs.
- `search_classes` searches the exact JIndex snapshot for the loaded runtime profile, including while Minecraft is offline.
- `artifacts_read` returns the exact generated source or the current job record.

`code_execute` accepts optional imports, client or server execution, and worker-thread or tick-thread scheduling. Use `log` or `logln` in submitted code to return observed values. The generated class directly extends `BaseScript`, so its reflection helpers and the full game process remain accessible.

There is no sandbox. An MCP client can read or mutate anything available to the Minecraft process. Server-side execution still follows TotalDebug's existing server script policy. Cancellation is cooperative. Code already running on a tick thread cannot be interrupted safely.

## Proof and artifacts

Each job records:

- the SHA-256 and UTF-8 size of the exact merged Java source;
- submission, update, and completion timestamps;
- target side and execution environment;
- terminal output or error;
- the captured profile id and runtime signature;
- durable `source` and `job` artifacts under `<companion-app-home>/mcp/artifacts/<job-id>`.

The MCP layer does not execute Java itself. It queues the existing authenticated SCNet messages, and TotalDebug compiles and runs the source in the target Minecraft JVM. This keeps static indexing and orchestration in Companion while runtime authority remains in the game process.
