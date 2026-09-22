# Automatic connection to Companion

Status: implemented, with startup and reconnect regressions covered by automated tests. Isolated live development-client acceptance passed on 2026-09-21. The live test used a development NeoForge client with the packaged Companion; paired-JAR packaging was verified separately. Prism Play launching is covered in [Usage](USAGE.md#projects). The sections below retain the implementation contract; the Game popup's explicit Reconnect operation is described at the end.

## Expected behavior

With the game's project selected in Companion:

- Start Companion, then Minecraft: TotalDebug finds Companion and connects automatically.
- Start Minecraft, then Companion: TotalDebug waits for Companion to publish its endpoint, then connects automatically.
- Close Companion: Minecraft stays running. Restart Companion with the same project selected: TotalDebug connects again.
- Select another project: use the existing project-switch lifecycle. A background connection attempt cannot select a project or undo the user's selection.
- With no project selected, or a different project selected, remain disconnected. Opening the matching project enables automatic connection. F6 retains its explicit ability to start Companion and select the game's project.
- Automatic connection does not focus Companion, release the game's mouse, install Companion, or show the F6 progress messages in chat.

Use the existing `useCompanionApp` setting. This does not introduce a launcher dependency, another transport, a running-game registry, or idle registered sessions. Keep the existing single active game connection.

## Existing code to reuse

- `CompanionApp` owns the application lock, secret creation, and startup/shutdown file cleanup.
- `CompanionSession.bindAndPublish()` opens the loopback server before publishing its endpoint.
- `CompanionAppClient` already discovers Companion, connects, authenticates, handles runtime announcements, and closes the session.
- Its current `ensureConnectedAndReady()` also launches Companion and sends an explicit project-selection request. Those actions must remain exclusive to the F6/source-open path.
- `CompanionApplication.attachSelectedProfile()` validates the current project. The descriptor is a discovery hint; this server-side check remains authoritative.
- `AtomicFiles` already provides complete-file publication through atomic replacement.

## Files and descriptor

Keep the existing application directory from `AppPaths`, including its explicit app-home override:

```text
<app-home>/run/companion/
  instance.lock        Companion holds the OS lock while running
  instance.key         existing authentication secret
  instance.properties  existing endpoint descriptor
```

Add one optional field to `CompanionSessionDescriptor`: `selectedProfileId`. A present value must be nonblank. An absent field means that Companion is not advertising a project for automatic connection. It covers startup without a selected project and a project switch in progress.

Retain `protocol`, `port`, `pid`, and `projectPort`. The explicit F6 project-selection endpoint is still needed. Do not copy the token, project paths, or project registry into this descriptor. `ClientHelloMessage` already carries the full profile for final validation.

The selected profile comes from the active `ProjectScope`. The descriptor is derived output, not another owner of selection. Do not read `projects.json` from the mod: it is persisted application state and does not represent connection readiness.

The current descriptor reader rejects unknown fields. Update both consumers and bump `CompanionProtocol.VERSION` to the next available version in the same change. Do not introduce a separate descriptor-version negotiation or a legacy parser. Background discovery reports an incompatible running Companion once and waits for a changed publication; it does not install or restart it.

## Basic flow

```mermaid
sequenceDiagram
    participant G as Minecraft / TotalDebug
    participant F as AppData discovery directory
    participant C as Companion
    G->>F: Register watcher, then read current descriptor
    Note over G,F: Connect now if a matching Companion is already available
    C->>C: Open loopback server
    C->>F: Publish endpoint and selected profile
    F-->>G: Directory change notification
    G->>F: Read current descriptor and key; validate owner
    G->>C: Open existing TCP connection
    G->>C: ClientHello with protocol, token, and profile
    C->>C: Authenticate and validate current selected project
    C-->>G: ServerHello accepted, then Ready
    G->>C: Existing debugger and runtime inventory messages
    Note over G,C: Runtime indexing completes separately
```

There is one authentication handshake. `ReadyMessage` marks protocol-session readiness, not completion of runtime indexing. Preserve that distinction in status and tests.

## Publishing project availability

`CompanionSession` owns descriptor serialization. `CompanionApplication` supplies the currently connectable profile. Use one publication method rather than writing the descriptor in several places.

1. At startup, publish the restored active profile after the server is bound, or no profile if none is open.
2. At the beginning of a project switch, publish no automatic target before disconnecting the old game. Keep the endpoint and explicit project-selection port available.
3. After the switch completes, publish the replacement profile only after its scope is ACTIVE and `switching` is false.
4. If an editor veto or save failure cancels the switch, republish the original profile. Preserve its existing connection when the current switch lifecycle permits that.
5. Shutdown remains owned by the existing bootstrap cleanup. Do not add a second file-cleanup owner.

Use the project worker for switch-related writes, outside Swing work. Take the current selection under the existing lifecycle lock; serialize publications with project operations so a late write cannot advertise an older selection. Shutdown must drain/stop that owner before removing its files.

Publication errors must be visible. Failure to pause discovery aborts the switch before retiring the old project. Failure to publish after replacement does not roll back a retired project: keep the new project, leave automatic discovery unavailable, and report the file error. Reopening the same project or repeating the explicit operation should attempt publication again. Do not add a background publication queue.

Availability governs new connections. A descriptor temporarily lacking a target is not an instruction for the mod to close a working socket. Companion's existing switch lifecycle performs the actual disconnect. Compare the process/endpoint separately from the advertised profile when deciding whether an established connection can be reused.

## Mod discovery and connection ownership

Add one small owned `CompanionDiscovery` helper beside `CompanionAppClient`. It handles the directory watcher and the next retry deadline. Keep transport state, authentication futures, and runtime preparation in `CompanionAppClient`; do not duplicate those states in the helper.

Start discovery explicitly from `TotalDebugClient` after script, stop, server-source, and session-close handlers have been installed. Do not start it inside the `CompanionAppClient` constructor. This also lets existing isolated tests construct a client without starting background discovery.

Use one new daemon discovery thread. Retain the existing F6 request worker and runtime inventory worker. Do not add a general scheduler, connection manager hierarchy, or an executor for each event.

The discovery loop:

1. Ensure the known application discovery directory exists and register it with Java `WatchService`.
2. Read the current files immediately after registration. This covers both startup orders without a check-then-watch gap.
3. On descriptor/key creation, modification, or deletion, re-read the current state. Treat notifications as hints, not commands, and collapse a batch into one check. Ignore unrelated MCP files and temporary staging names.
4. On `OVERFLOW`, re-read the same known files. If the watch key becomes invalid, re-register the known directory with delayed retries. Never search parent trees, drives, or Minecraft instances.
5. Validate protocol, owner lock/process, key availability, and matching profile before attempting a connection. Never log the key. Discovery must not delete descriptor/key files, including stale ones; the existing Companion lock owner replaces or removes them.
6. If already connected and authenticated to the same endpoint, do nothing.

A bounded `WatchService.poll` wait can inspect in-memory shutdown/disconnection flags without reading files. A one-second maximum wait is sufficient for recovery detection. File reads happen at startup, on relevant events, or when an actual connection/watch-registration retry is due. This is not a once-per-second filesystem scan.

Extract the common socket-connect/authenticate/readiness code from `ensureConnectedAndReady()`:

- Automatic path: discover an already-running matching Companion, then call the common connection operation.
- Explicit F6 path: discover/start Companion, request project selection, then call that same operation and perform the existing foreground handoff.

Serialize these two callers through the existing client connection ownership. F6 must reuse a successful automatic connection; a watcher event must not reset an in-flight explicit connection. Re-read current endpoint/selection before beginning an automatic attempt after waiting for ownership.

Socket callbacks must not wait for the monitor held by a caller awaiting the handshake. Preserve `CompanionHandshakeConcurrencyTest`. Callbacks complete the existing futures and report disconnection; reconnection runs off the transport callback thread.

Closing marks discovery/client state closed, closes the watcher and transport, and completes pending waits before waiting for worker termination. Do not wait for the handshake timeout merely to acquire the synchronized foreground-operation monitor. Reuse existing runtime-task cleanup, and prevent queued retries from reopening the socket after close.

## Retry rules

| Observation | Action |
| --- | --- |
| No live descriptor, no advertised project, or another project advertised | Wait for a relevant file change. No connection attempt and no chat error. |
| Compatible matching endpoint appears | Attempt connection once. |
| Refused connection, transport loss, or handshake timeout | Re-read and retry with delays of 1, 2, 4, then at most 10 seconds while the endpoint remains eligible. |
| Descriptor or key temporarily inaccessible | Retry with the same delay schedule; releasing a Windows file handle does not necessarily produce a file-change event. |
| Endpoint/key changes | Re-evaluate promptly and reset the retry delay. |
| Protocol mismatch, invalid key, or explicit authentication/profile rejection | Report the reason once. Wait for a changed descriptor/key or an explicit F6 action; do not loop on permanent rejection. |
| Successful handshake | Reset retry state. Ordinary watcher events do not create another connection. |
| Mod closes or Companion use is disabled | Stop automatic attempts; perform the existing session cleanup as applicable. |

Track a rejected file publication, not merely its profile ID. Cancelling a switch can publish the original profile again; that fresh atomic replacement must permit a new attempt. Use the file publication metadata to distinguish it from duplicate notifications for the same file. Do not add a persistent request counter or another request file.

Use the existing connection and handshake timeouts. Do not combine SCNet's optional retry overload with a second nested retry loop. Honor the existing Companion enable flag at startup and before attempts; this slice does not add a new live configuration system.

Keep background errors in logs with repeated identical failures suppressed. Existing Companion status still shows its authenticated connection and index state. F6 retains user-visible action errors. No new Connect window, notification stream, or automatic focus behavior is needed.

## File-level scope

| File | Responsibility/change |
| --- | --- |
| [CompanionSessionDescriptor.java](../storage/src/main/java/com/github/minecraft_ta/totaldebug/storage/CompanionSessionDescriptor.java) | Optional advertised profile, strict parsing, atomic publication. |
| [CompanionProtocol.java](../protocol/src/main/java/com/github/minecraft_ta/totaldebug/protocol/CompanionProtocol.java) | Coordinated version bump for both consumers. |
| [CompanionSession.java](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/session/CompanionSession.java) | Reusable descriptor publication using its bound endpoint. |
| [CompanionApplication.java](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/CompanionApplication.java) | Publish availability at startup and project-switch completion/cancellation; preserve admission checks. |
| [CompanionAppClient.java](../mod/src/main/java/com/github/minecraft_ta/totaldebug/client/companion/CompanionAppClient.java) | Shared connect path, read-only discovery, retry/foreground coordination and shutdown. Remove mod-side stale-file deletion. |
| `CompanionDiscovery.java`, new file beside `CompanionAppClient.java` | New focused watcher/retry helper, owned by the client. |
| [TotalDebugClient.java](../mod/src/main/java/com/github/minecraft_ta/totaldebug/client/TotalDebugClient.java) | Start discovery after handler registration. |
| Existing session, descriptor, handshake, and project-switch tests | Extend the real lifecycle coverage; add watcher/startup-order tests. |
| `docs/USAGE.md`, `companion/README.md`, `companion/MCP.md` | Describe actual automatic connection, selected-project requirement, protocol pairing, and unchanged MCP lifecycle. |

Keep SCNet, launcher integration, and the evaluator/compiled-code responsibilities unchanged. Execution admission must wait for the live inventory, independently of cached browsing. UI changes cover the existing Game popup's reconnect action and icon-only copy/retry controls. The current code already has stale-descriptor and foreground tests; update their expectations deliberately rather than leaving a parallel discovery implementation.

## Implementation order and acceptance

1. **Extract and verify the common connection operation.** Existing F6, installation, focus, authentication, and close tests must still pass. No automatic startup yet.
2. **Publish the active profile.** Add descriptor tests for a selected profile and no target. Cover initial restore, project change, editor veto, failed publication, and no publication after shutdown. Preserve the server's final profile validation.
3. **Add automatic discovery and recovery.** Use real temporary directories, actual atomic replacement, and a real local test socket. Test both startup orders and reconnect without calling private hello handlers as a substitute for the connection flow.
4. **Audit interactions, document, and validate both consumers.** Keep the acceptance cases below as the review checklist. A passing unit suite alone is not the final acceptance condition.

Required cases:

- Both startup orders reach an authenticated connection without F6, installer calls, project-selection requests, focus changes, or mouse release.
- Companion absence causes no repeated directory reads or connection attempts. Disabled Companion support starts no automatic connection.
- Wrong/no selected project waits; selecting the matching project connects. An editor-vetoed switch restores availability and does not lose its working session.
- File replacement, duplicate events, `OVERFLOW`, missing/recreated directory, stale PID/lock, incomplete startup, and incompatible protocol have bounded, observable behavior.
- Transient failures retry without needing another file event. Permanent rejection does not produce an endless retry/log loop.
- F6 concurrent with discovery produces one usable connection and preserves explicit project selection. Delayed callbacks cannot complete the next attempt's handshake.
- Companion restart reads the new endpoint/key. A newly published endpoint does not inherit authentication from the old socket.
- Close during watcher wait, connect, or handshake returns promptly, terminates owned work, and cannot reconnect afterward.
- Existing runtime inventory, source opening, server comparison, script admission, and MCP endpoint behavior still work after automatic connection and reconnection.

Run the owning mod and Companion tests during implementation. Finish with root `:check :localBundle` using published dependencies and warnings treated as failures. Validate the packaged matching mod/Companion pair in a disposable instance for both startup orders, Companion restart, F6, and project-switch cancellation. Use the normal selected-project case first; do not expand this into a multi-instance UI project.

Java watcher contract reference: [WatchService, Java 21](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/WatchService.html). It specifies queued events, `OVERFLOW`, invalid keys, and close behavior. Re-reading current state is necessary even with a watcher.

## Explicit Reconnect operation

`ProjectControls.reconnectGame(expectedProject)` captures the exact active project scope, publishes PENDING, and queues the existing project worker. That worker withdraws the advertised profile, requests cancellation of owned MCP jobs, disconnects the socket, and publishes the selected profile again. Atomic replacement wakes the same mod discovery loop. No additional request file or socket owner is introduced.

The returned future completes on authentication, not descriptor publication. An asynchronous 30-second deadline reports that no matching Minecraft connected. Switching or closing cancels the owned wait, and late completions cannot modify another request. A cancelled switch restores a non-pending status. Publication failure before teardown preserves an authenticated connection and reports one explicit-operation failure notification.

Each mod transport attempt owns its authentication/readiness futures and message handlers. Closed-attempt callbacks cannot authenticate its replacement. Terminal cleanup finishes before another attempt can activate; this keeps old script cleanup from cancelling replacement-session scripts. Reconnect keeps the installed index and offline browsing, while previous live execution observations become disconnected.

## Verification record

On 2026-09-21, the coordinating UI task completed root `:check :localBundle` with published dependencies and `--warning-mode fail`. All module suites passed with zero failures, errors, or skipped tests:

| Module | Tests |
| --- | ---: |
| Companion | 1,087 |
| Mod | 169 |
| Protocol | 30 |
| Storage | 13 |
| Evaluation | 293 |

Packaging and build-logic checks passed. Focused regressions cover both connection startup orders, restart, transient recovery, explicit F6 concurrency, stale messages, cleanup ordering, project-switch cancellation, truthful pending/failure states, index and MCP job ownership, and shutdown.

The coordinating UI task also completed an isolated live test using Minecraft 1.21.1 / NeoForge 21.1.250 through `:mod:runClient`, with development mod classes and the packaged Companion from `build/local-bundle`. It used a disposable game directory and application home. These cases passed:

- Game first, then Companion: automatic authenticated connection.
- Companion first, then a new game process: automatic authenticated connection.
- The actual Game popup reconnect icon, invoked through Swing `doClick`: the request completed after authentication.
- Companion close/restart while Minecraft remained running: automatic authenticated reconnection.
- A `return 1` script compiled and completed with `RUN_COMPLETED` before and after reconnect, and after the game restarted.
- Intentional termination of the disposable game followed by a new game process: connection recovered.
- With no game running, the reconnect request failed after 30 seconds with `No matching Minecraft connected within 30 seconds.` The icon became enabled and accepted a second request.
- Closing Companion during that second pending request cancelled it with `Companion is closing` and the process exited with code 0.

Both disposable Minecraft processes and both live-test Companion processes were stopped after testing. The isolated descriptor and key were removed. The user's existing processes were not changed. Logs and artifacts remain under ignored `build/reconnect-smoke`. The forced disposable-game stops caused the associated `runClient` Gradle sessions to exit with code 1; these were deliberate transport-loss tests, not product failures.

This was not a packaged-mod installation into a modpack. Paired-JAR packaging passed the separate automated checks. F6 concurrency and project-switch veto were covered by automated socket/UI tests, not physical live input in this smoke test.

## Cached-runtime startup follow-up

Real-socket regression tests reproduced two failures after the acceptance run above: authentication could authorize execution against restored inventory A before the live game supplied inventory B, and an offline restore completing after live preparation started could re-enable that stale compiler. Both tests failed against the previous implementation.

After validating the selected profile, Companion now suspends compiler admission before completing the authentication handshake. Suspension immediately clears readiness without waiting for the compiler lock; entering runtime preparation also retires a pending offline restore. The existing index remains available for browsing. Live inventory installation restores compilation, including when the confirmed inventory matches the retained binding and no browsing replacement is needed.

Runtime status changes refresh the actual Evaluate action. The startup regression covers its disabled state during preparation and its recovery after both A-to-B replacement and a same-B reconnect. A retired tree's asynchronous load failure no longer logs an obsolete cache error; failures belonging to the active tree remain visible.

The deterministic socket/cache regressions and full `:companion:test :localBundle` run passed with published dependencies and `--warning-mode fail` in 4 minutes 14 seconds. This includes the real Evaluate action assertions, server-baseline preservation through suspension, and active-versus-retired tree failure tests. The live smoke record above predates this follow-up. The user reports that the running modpack works, but no restart, deployment, or live cache-reset test was performed for this latest fix.

## Audit findings and ownership

The architecture review retained the existing owners: the mod watches the application directory and owns each socket attempt; Companion serializes project/reconnect operations; runtime installation owns compilation readiness; shared storage owns atomic replacement. No additional connection coordinator or general retry framework was introduced.

The audit found and corrected these behaviors:

- Same-project reopening and a queued index retry could restore cached compilation while authentication was finishing. Both failed a gated real-socket regression before the fix. Offline reopen, retry, and local-source rescan now share one admission check, with filesystem discovery outside the lifecycle lock and restore submission checked under it.
- A transient Windows read-sharing failure was classified as a permanent rejected descriptor. A real conflicting file handle reproduced this. Discovery now retries filesystem access failures while malformed descriptors and protocol/authentication rejections still wait for a changed publication.
- Watcher re-registration cleared its remembered error before reading publication metadata, repeating identical warnings. Failure memory now survives failed watch attempts.
- Windows readers could prevent atomic descriptor replacement and abort startup. Storage retries only the atomic move for up to one second, preserves the previous publication, honors interruption, and still reports a persistent lock. Tests cover temporary readers, persistent locks, cancellation, and staging cleanup.

The audit also removed a redundant connection-state predicate and a manifest-send pass-through. Protocol 16 requires the paired mod and Companion; there is no compatibility path for the earlier descriptor contract.

Final local verification: Java 21, published dependencies, and :check :localBundle with --warning-mode fail passed in 6 minutes 14 seconds. The five module suites report 1,602 tests, zero failures/errors/skips; packaging and build-logic verification also passed. Independent standards and behavior re-reviews found no outstanding blockers after these corrections.

## PR review follow-up

Reconnect completion now requires both teardown and republication to finish. A request-owned flag is checked under the lifecycle lock, and the worker also checks for a replacement that authenticated during publication. Cancelled requests restore the selected-project advertisement after withdrawal. A real socket test gates job cleanup, authenticates during that gate, and verifies that only the post-reset connection completes reconnect; a Windows reader test covers cancellation while withdrawal is blocked.

An early disconnect resumes a cancelled initial offline restore on the project worker. Recovery checks the original scope, absence of a replacement client, WAITING status, and absence of an installed binding. It preserves retained indexes and newer live loads. Cached-runtime and local-mod fallback tests both failed before this fix and now recover browsing without enabling execution offline.

Review follow-up verification: the full Companion suite passed with 1,097 tests and no failures/errors/skips, and the paired bundle rebuilt successfully in 5 minutes 24 seconds. Independent behavior and architecture re-reviews found no blockers in the follow-up.
