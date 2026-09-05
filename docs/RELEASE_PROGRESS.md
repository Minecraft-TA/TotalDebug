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

## Interpreted Java conversions

Companion `95a9ede` fixes C5's reproduced cases while retaining the interpreter. Argument values keep their declared reference types through overload and varargs selection. Array covariance and the Object/Cloneable/Serializable supertypes are recognized. Arithmetic, boolean operators and primitive casts unbox wrapper values; null unboxing fails before a later arithmetic operand runs. String concatenation uses target string conversion instead of JDI object-display text. Conditional promotion uses metadata for both branches and evaluates only the selected branch.

The new compiled-Java oracle suite contains 39 real JDWP cases. It reproduced 22 conversion failures and, in the next slice, 12 conditional failures. All now pass. The full Companion build passes 493 tests. The original audit probe now reports the Object overload for both Object casts, `"0"` for boxed string conversion, `long 1` for the mixed conditional and `int 1` for boxed addition. Lexical scope and 128-entry pin retention remain correct. Evidence is [the semantic recheck](audit-evidence/2026-09-05/companion-semantics-recheck-output.txt); logs are `.codex/interpreter-*.log`.

This is targeted interpreter repair, not a complete Java compile-time binder. Generic/poly overload binding, constant-field folding and unrelated reference-conditional least-upper-bound types remain outside the verified parity matrix. A conditional whose type cannot be established reports the unmet metadata requirement and suggests Code mode. Literal-expression narrowing, declared fields/locals and method return types are covered. The existing compiler/context limitations remain; the release still needs a final supported-feature/limitation review.

All original reproduced findings are now resolved. JIndex integrity/oracle work, total transport queue bounds, release metadata and dependency setup, exact candidate pairing and live Minecraft acceptance remain required. Local commits do not authorize remote publication or deployment.

## Index snapshot integrity and independent member fixtures

JIndex `7621c9d` validates cross-section links before rebuilding derived lookup tables. Checks cover package ownership/reachability, constant-pool entry offsets, class/member/signature links, semantic section ranges, member search identity/order, reference sites and literal posting order. Serialized signature nesting is limited to 256. Tests reject corrupt internal ranges even inside a ZIP with a valid CRC, invalid class/package links, excessive nesting, duplicate members and invalid member ordering. The snapshot format remains version 5.

An independent test-only ASM visitor compares member targets, source methods, relation kinds and occurrence counts before and after saving/reloading five generated/compiled fixtures. It covers inherited fields and methods, interface calls, repeated accesses, constructor/method references and field handles. ASM is not a production dependency. This fixture oracle does not yet establish independent parity for all class metadata, literals or the full runtime corpus.

The complete retained capture contains 538 available archives. Their [paths and hashes](audit-evidence/2026-09-05/snapshot-corpus-manifest.json) fix the inputs used with Java 21.0.12. The [corpus run](audit-evidence/2026-09-05/snapshot-validation-corpus.json) builds and reloads 178,117 selected classes and 14,880,319 reference records. The first validator took about four seconds per load. Timing isolated the temporary duplicate-member hash set as the dominant cost; flags indexed by validated member positions reduced warm median loading to 1.30 seconds with the same inputs and checks. The earlier, slightly different capture loaded in about 0.79 seconds, so this is evidence of bounded validation cost, not a claim of unchanged startup time.

The full JIndex wrapper build passes 44 Java and 50 Rust tests, formatting and Clippy, with one existing corpus-dependent Rust test ignored. Maven Local publication preceded Companion's full 493-test build. All 38 embedded JIndex class/native entries match the rebuilt library. [Artifact hashes](audit-evidence/2026-09-05/snapshot-validation-artifacts.json) record the pair. Logs are `.codex/snapshot-*.log`; timing instrumentation was removed before committing.

R4 remains partially open. The stream decoder can still reserve memory from serialized vector counts before reading all elements, so this is not a general memory bound for hostile snapshots. Broader malformed class/signature/query coverage and independent runtime-corpus extraction remain to be completed. The transport queue slice is in progress; public resolution, release pairing and live acceptance remain open.

## Outbound queue admission

SCNet `f6205e5` caps accepted messages awaiting complete transmission at 1024 by default, configurable before connection. The budget includes the message being serialized and any partially written frame. Overflow immediately rejects the producer and causes the transport's normal connection-error/close lifecycle. Producers do not wait for capacity, and pending messages are not replayed after reset. This bounds message ownership count, not arbitrary object-graph bytes.

The original stalled-processor test accepted messages beyond the proposed bound. Five new tests now cover saturation, 8 concurrent producers, reset, 100 successive completed frames, an 8 MiB frame blocked by a slow socket reader, exceptional drain completion and invalid configuration. The full SCNet build passes 78 tests. Per-frame direct allocation remains; this slice does not claim a byte-budget or buffer-pool benchmark.

TotalDebug `900b934` handles rejected script results without throwing through execution callbacks and converts rejected foreground requests into its existing IOException contract. Companion `465caf4` returns false when a session send is rejected during transport shutdown. Its real authenticated-session regression covers this transition. Full consumer builds after Maven Local publication pass 442 mod tests, 13 storage tests and 494 Companion tests. Together with JIndex's 44 Java tests, the current total is 1,071 Java tests and 50 Rust tests passed, with one existing native test ignored. [Artifact hashes](audit-evidence/2026-09-05/outbound-artifact-hashes.json) verify the embedded libraries against their producers. Logs are `.codex/outbound-*.log`.

## Dependency and publication configuration

Application builds now require `-PtotaldebugUseMavenLocal=true` to use local workspace libraries. That mode exclusively selects those libraries from Maven Local, while public mode excludes it. Both application workflows explicitly disable the option. The sibling Companion build receives the root setting. Companion `dcd2167` also separates `evaluation_version` from `storage_version` and limits Packagecloud lookups to the owned modules.

Storage and evaluation now have the missing Packagecloud publishing repository configuration and source metadata. SCNet `1dccccd` and JIndex `2ffd0cf` describe their original upstream source repositories in their POMs. Credentials continue to come from `PACKAGECLOUD_TOKEN`; license metadata remains deferred. [Coordinated build and publication instructions](BUILD_RELEASE.md) record the commands and the remaining candidate steps.

Local root compilation and the Companion shaded build pass with the explicit development option. All four generated POMs contain the expected coordinates and source URLs. An offline public-mode check cannot resolve SCNet despite its presence in Maven Local. An explicit local-mode check with an empty local repository searches only that repository for the owned modules and fails as expected. Both workflow YAML files parse. [Configuration evidence](audit-evidence/2026-09-05/publication-configuration.json) records these checks; logs are `.codex/publication-*.log`. These negative isolation checks are not public dependency acceptance. No upload, GitHub run, release tag or Minecraft deployment was performed.
