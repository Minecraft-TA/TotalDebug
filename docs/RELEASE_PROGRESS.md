# Release stabilization progress

Updated 2026-09-05. The first implementation batch fixes audit findings C1 and C2 locally. The project is still not ready for stable publication; the remaining work is tracked in [the release audit](RELEASE_AUDIT.md).

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

Continue W1 with SCNet dispatch mutation and server endpoint publication, C3 and C4, and truthful script cancellation, C6. Evaluation semantics, lexical binding and target pin ownership remain C5, C7 and C8. The cleanup, CI/publication, paired installer and live acceptance work in W3-W6 remains required.

Keep Packagecloud for this release and use the upstream SCNet/JIndex repositories, as decided in the audit. Account access and public candidate publication are still pending.

The user authorized committing and continuing through the remaining correctness slices. The first fixes are committed as TotalDebug `421c534` and SCNet `59c8efa`. Nothing was pushed, published remotely or deployed to Minecraft. Existing unrelated documentation and workspace files were preserved.
