# Release stabilization progress

Updated 2026-09-05. Findings C1-C4, C6, C7 and C10 are fixed in committed local slices. C9's icon build failure is also resolved. The project is still not ready for stable publication; the remaining work is tracked in [the release audit](RELEASE_AUDIT.md).

## Baseline after the other task finished

The performance and evaluation task had finished and committed its work before this batch began. Verification used the following revisions plus the fixes described below, rather than the partial performance state excluded from the initial audit.

| Repository | Starting revision |
| --- | --- |
| TotalDebug | `df84f0522fa4c3bef90f616ed2894036d2d13c08` |
| TotalDebugCompanion | `aa9cc53c76434d7f6c049a166be977e93db0aad7` |
| SCNet | `802221b70ef5a35b63491b15f52ceca8b1795ae3` |
| JIndex | `80f8b43742bd38583d0c9a28bef6e4813edf761b` |

The prior task's completed performance work is included in these consumer builds. Passing their suites does not replace the final performance review or live acceptance. JIndex was unchanged; its Java/native verification from the initial audit was not repeated for these transport and script changes.

## Fixes

C1: `ScriptRunner` queues results under its run-state lock and delivers them outside that lock. Each run has one result-delivery owner, so compilation progress precedes its terminal result even when Stop races with completion. A callback can request Stop without causing a nested result callback. The run stays registered until its terminal callback finishes. Existing service-side synchronization remains; the runner no longer holds the opposing lock while entering that service.

Six deterministic cases cover Stop, disconnect and Close during compilation or terminal callbacks. All six failed before the fix and passed afterward. A seventh test verifies callback ordering when the compilation callback itself requests Stop. The original production `ClientScriptService` probe was adapted to assert successful completion and also passed.

C2: `Client.connect` serializes connection attempts separately from the client monitor used by Close. It waits for the old transport to finish without holding that monitor. Reconnect from an active connected/message callback is rejected before acquiring the attempt lock, which prevents a callback from waiting behind the very reconnect that needs it to return.

Four deterministic connected/message callback cases failed before the fix and passed afterward. Existing disconnected-callback, drain-close and connection tests also passed. Repeating the disconnected-callback test exposed an old assertion that assumed Close always followed replacement. Concurrent Close can now complete before replacement, as required to release the original callback. The test checks completion of both operations and verifies that Close after reconnect returns closes the current connection. It then passed 200 consecutive runs.

No wire format or script cancellation status was changed. C6 remains open: a timeout can still report a terminal status while target Java continues running.

## Verification

All commands used each repository's checked-in wrapper and Java 21 at `C:\Users\Admin\.jdks\temurin-21.0.12`.

| Check | Result |
| --- | --- |
| SCNet `clean build publishToMavenLocal '-PscnetVersion=2.0.0' --warning-mode fail` | 63 tests passed |
| SCNet `build publishToMavenLocal '-PscnetVersion=2.0.0' --warning-mode fail` after the test ordering correction | 63 tests passed; production JAR unchanged |
| TotalDebug `clean build :storage:publishToMavenLocal :evaluation:publishToMavenLocal '-PtotaldebugUsePublishedCompanion=true' --no-build-cache --warning-mode fail` | 427 mod tests and 13 storage tests passed |
| Companion `clean build --no-build-cache --warning-mode fail` | 426 tests passed |
| Production service/run lock probe | Stop returned, terminal result arrived, JVM reported no deadlock |
| Repeated disconnected-callback reconnect test | 200 runs passed after correcting the ordering assertion |
| Packaged transport comparison | Mod's embedded SCNet JAR and Companion's modified transport classes match the locally published build |

SCNet was published to Maven Local before the full consumer builds. Shared modules were published before Companion was built. These are local verification artifacts with development coordinates, not an approved release pair. The published-Companion build flag suppresses the automatic sibling build; it does not repair the stale public Companion download pin in R1.

There were 929 passing suite tests, with no failures, errors or skipped tests. Companion's icon test passes at the completed baseline, resolving C9's build failure. The evaluation module still has no independent test source set; its current tests run in TotalDebug.

The initial root regression command selected `storage:test` as well and failed because no storage tests matched the filter. It was corrected to `:test` before recording the six failing regressions. This was a command-selection error, not another product defect.

Evidence is retained under [audit evidence](audit-evidence/2026-09-05/README.md), including pre-fix failures, the service probe and [artifact hashes](audit-evidence/2026-09-05/deadlock-fix-artifact-hashes.json). Full wrapper logs are `.codex/deadlock-*-build*.log` in TotalDebug.

## Remaining work

C6 and C10 are now resolved by the execution-ownership slices below. Evaluation semantics and target pin ownership remain C5 and C8. The cleanup, CI/publication, paired installer and live acceptance work in W3-W6 remains required.

Keep Packagecloud for this release and use the upstream SCNet/JIndex repositories, as decided in the audit. Account access and public candidate publication are still pending.

The user authorized committing and continuing through the remaining correctness slices. The first fixes are committed as TotalDebug `421c534` and SCNet `59c8efa`. Nothing was pushed, published remotely or deployed to Minecraft. Existing unrelated documentation and workspace files were preserved.

## Subsequent committed slices

| Slice | Commit | Verification |
| --- | --- | --- |
| C3, message dispatch | SCNet `989b339` | Four new failure cases reproduced before the fix; five added tests pass, including callback failures. Full suite: 68 tests. |
| C4, server endpoint lifecycle | SCNet `0971750` | Three deterministic ordering tests reproduced before the fix. Five added cases cover publication, shutdown during preparation, configuration, close before startup and executor rejection. Full suite: 73 tests. |
| C7, compiled lexical binding | Companion `6e390ed` | Seven real JDWP cases reproduced before the fix. Twelve added cases cover blocks, loops, lambda parameters, catch/resource scope, declaration order and exception writeback. Full suite: 438 tests. |

C3 snapshots callbacks and claims one-shot listeners before invocation. Registration/removal affects the next dispatch, including nested posts. Concurrent posts may invoke persistent listeners concurrently; callback errors retain the existing explicit stderr reporting. Consumer listener-removal call sites were inspected.

C4 reserves configuration during endpoint preparation, publishes the endpoint before dispatch and installs close cleanup before publication. Shutdown can complete while preparation is blocked, and a subsequently prepared endpoint is closed without starting. No server state lock is held while an executor or application callback runs. The executor seam preserves the existing accept executor and per-client transport executors.

C7 uses declaration visibility ranges instead of a fragment-wide set of names. Enhanced-for variables do not hide fields in their iterable expression; resource variables do not hide fields in catch/finally clauses. Flow-scoped patterns are explicitly refused before target invocation because the adapter does not implement their control-flow binding. That supported-context limit belongs in the next evaluation design decision.

SCNet was published to Maven Local as `2.0.0` before the consumer builds. TotalDebug `build '-PtotaldebugUsePublishedCompanion=true' --warning-mode fail` passed, with 427 mod tests and the unchanged 13-test storage suite verified. Companion `build --warning-mode fail` passed all 438 tests. The latest verified suite total is 951, with no failures, errors or skipped tests. JIndex's source and native artifact remain unchanged.

The original Companion audit probe was rerun against these completed builds. C7 now returns the correct field value. C5's incorrect overload/numeric/string behavior remains, and C8 still retains 140 target pins with only 128 history entries. The new `ScriptSessionIdentityProbe` confirms C10: after Companion session replacement, an old server completion settles a new run with the same integer ID and the actual new completion is discarded.

Pre-fix logs are `.codex/dispatch-red.log`, `.codex/server-lifecycle-red.log` and `.codex/compiled-scope-red.log`. Full build logs are `.codex/dispatch-green.log`, `.codex/server-lifecycle-build.log`, `.codex/transport-consumer-totaldebug.log` and `.codex/compiled-scope-build.log`. Probe outputs are retained in the evidence directory.

The evaluator decision is recorded in [release architecture decisions](RELEASE_DECISIONS.md). The user chose to retain the existing evaluator for this release. Continue focused correctness work within that design; no compiler-only switch or broad evaluator rewrite is planned.

## Execution ownership slices

C10 is fixed in TotalDebug `7112d14`. Each execution receives an internal integer ID that is never reused during the Minecraft client's lifetime. Companion editor/job IDs remain separate. Closing a Companion session detaches its observers before requesting cancellation; late completions cannot settle a replacement session's runs. Exhausting the ID range requires restarting Minecraft rather than wrapping. Two service regressions and the production forwarded-result probe pass. The probe now receives only `[new session]` after deliberately delivering the old completion first.

C6 is fixed in TotalDebug `5728b95` and Companion `32214c6`. `CANCELLATION_PENDING` keeps worker and tick runs registered until their code returns. Stop's grace-period expiry reports continued execution without claiming completion. UI buttons, snippets and MCP jobs wait for the real terminal result. Failed cancellation delivery keeps the job correlated and permits an explicit retry. Server encoder failures preserve whether the original status was terminal.

The application protocol is now 10 on both sides; the Minecraft client/server payload negotiation version is 2. Both sides' shared hello-byte fixtures were updated. The stale public Companion pin remains R1 and cannot satisfy this protocol.

Three bounded target fixtures reproduced premature completion before the fix, covering worker, pre-tick and post-tick execution that ignores interruption. They now verify pending status, repeated Stop, Close while executing, retained registration and final output after the target is released. Service, envelope and MCP tests cover cancellation transport failures and continued correlation.

TotalDebug's full build passed 433 tests plus the unchanged 13 storage tests. Companion's full build passed 441 tests. Together with unchanged SCNet's 73 tests, the latest verified suite total is 960. The final wire-fixture-only adjustment in TotalDebug also passed its focused suite. JIndex is unchanged. Logs are `.codex/cancellation-*.log`; the red worker/tick failures are in `cancellation-red.log`. No candidate was published or deployed.

## Installer, CI and cleanup slices

The managed installer fix is TotalDebug `ce390ac`. It hashes existing managed JARs, replaces mismatches only after a verified staged download, and preserves the old file if replacement fails. Valid installed bytes need no network. Defaults are a 15-second connect timeout, 30-second response-header and idle-body deadlines, five minutes for the body transfer, and a 256 MiB ceiling. Tests cover announced and unannounced oversized responses, corruption, retries, stalled and trickling bodies, non-success HTTP responses, failed replacement and staging cleanup. A loopback HTTP fixture verifies the real JDK client unblocks before headers and during a stalled body. Ten new tests pass; the full mod suite passed 443 tests before later cleanup removed two obsolete configuration tests.

Windows branch/PR workflows are committed as TotalDebug `d27d3a6`, Companion `1bff2af`, SCNet `116e8dc` and JIndex `37aa8e7`. SCNet retains Linux coverage too. Application jobs use empty Maven Local directories; root CI also disables automatic sibling Companion builds. YAML was parsed and the commands reviewed locally. These workflows have not run on GitHub and cannot pass public application resolution until the missing dependencies are published. No release workflow was changed. Required branch protection and coordinated candidate installation remain open in R3.

Cleanup commits are TotalDebug `39733ec`, Companion `e28f80f`, SCNet `ed76735` and JIndex `68e9ac9`:

- Removed the unsupported packet-block configuration and its validators/tests, unused command/startup translations, and dormant protocol constants on both application sides. Active message IDs remain unchanged.
- Removed Companion's unnegotiated chunk-grid, packet-logger and legacy remote-search implementations, their registration/guard branches and hidden UI. Current index-based search and Find Usages remain. Removed JDT initialization debug prints.
- Removed SCNet's no-op polling controls and unused buffer helpers. Frame, string and standalone serialization defaults are now 16 MiB, matching the applications' existing explicit bounds. Custom processors must implement their size contract. Total queue growth is still a separate open item.
- Removed JIndex's duplicate `destroy()` entry point; `close()` remains idempotent and its use-after-close test remains. Updated both library READMEs, and compiled and ran the SCNet example successfully.
- Required `jdk.compiler` in the Companion launcher alongside the existing Java modules. Ignored local agent probe/attachment directories without deleting them.

Both libraries were published to Maven Local before rebuilding consumers. Full wrapper builds passed: 441 TotalDebug tests, 13 storage tests, 441 Companion tests, 73 SCNet tests and 43 JIndex Java tests. JIndex also passed 46 native tests, formatting and Clippy, with one existing native test ignored. The latest total is 1,011 Java tests and 46 Rust tests passed. Packaged SCNet and JIndex class/native bytes match the rebuilt libraries. The removed UI and message classes are absent from the shaded Companion JAR. [Artifact hashes](audit-evidence/2026-09-05/cleanup-artifact-hashes.json) record the tested revisions and bytes. Full logs are `.codex/cleanup-*-build.log`.

The user deferred the upstream license choice for now. No license was invented or added. Packagecloud access, immutable release coordinates, final Companion download pin, independent native validation, C5/C8, remaining documentation/publication work and live Minecraft acceptance remain open. Existing unrelated root documentation edits are preserved and uncommitted.

## Evaluation value ownership

C8 is fixed in Companion `251d90a`. Engine history and the controller's manual-operation history each retain at most 128 operations. Their object results now have explicit owners. Eviction releases that history's references, failed or cancelled evaluations discard unpublished values, and aliases share a single collection pin. The latest breakpoint action and open inspectors retain their own results. Replacing/removing an expression releases its results across cached frames; closing an inspector also releases results that arrive afterward. Resume and detach invalidate all outstanding references and leases.

The original 140-evaluation probe now reports `history=128 targetPins=128`, instead of 140 pins. A real JDWP test keeps an evicted object array and its 1 MiB child inspectable through an inspector lease, then verifies release on close. Other cases cover aliases, resume, detach, reused reference IDs, failed publication, cancellation cleanup, replacement and late UI completion. These checks establish ownership of live object roots; they do not impose a byte limit on graphs or freeze objects modified by target code.

Companion's full wrapper build with `--warning-mode fail` passed 454 tests, with no failures, errors or skips. The other repositories are unchanged by this internal Companion slice. Together with their last full builds, the latest verified total is 1,024 Java tests and 46 Rust tests, with one existing native test ignored. Packaged SCNet/JIndex classes and native bytes still match the library artifacts. [Artifact hashes](audit-evidence/2026-09-05/retention-artifact-hashes.json) record these revisions and bytes. Full logs are `.codex/retention-*.log`; the original failed retention case is in `retention-red.log`.

C5 remains reproducible in the same probe: declared-type overload selection, conditional numeric promotion, boxed arithmetic and string conversion still differ from Java. The evaluator remains in place as agreed. Public dependency resolution, final artifact pairing, independent native validation, remaining cleanup and live acceptance are still required. Nothing was pushed, remotely published or deployed.
