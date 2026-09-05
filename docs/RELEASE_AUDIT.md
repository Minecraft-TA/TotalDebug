# Stable release audit

Audited 2026-09-05. Status: not ready to release. This report covers the four repositories as one product, verifies the earlier audit, and defines the work needed for a clean release. It is an audit and implementation backlog, not release approval.

Implementation update: all ten original reproduced findings are resolved in local stabilization slices or the completed task baseline. C5's original probes now pass within the retained evaluator; this does not establish full Java binding parity. See [release stabilization progress](RELEASE_PROGRESS.md) for revisions, regression evidence and current build results, and [architecture decisions](RELEASE_DECISIONS.md) for the agreed scope. The original findings below preserve their audit evidence; the remaining release gates are open.

The later corpus check found and fixed C11 below. Current tested behavior and limits are recorded in [release scope](RELEASE_SCOPE.md). The immediate remaining work is paired distribution, public build verification and live acceptance. Broader native hardening remains an explicit scope decision. More feature development is not needed to make this release useful.

## Scope and baseline

Release scope is Minecraft 1.21.1, NeoForge 21.1.x, Windows, and Java 21. The native artifact tested here is the Windows x64 build. Ship the existing source browser/editor, decompiler, usages/hierarchy, client/server scripts, debugger, Evaluate Everywhere, breakpoint actions, MCP, and shared storage/evaluation modules once their gates pass.

Project switching, Linux/macOS support, runtime class patching, item rendering, packet logging/blocking, chunk grid, and other unported legacy features remain outside this release. The broader [vision](../VISION.md) is not the release specification. Preserve deferred experiments in Git history or their branches, rather than dormant current production implementations.

| Repository | Branch | Inspected committed base | Additional audited state |
| --- | --- | --- | --- |
| TotalDebug | `codex/neoforge-1.21.1-clean` | `24651c94f1dd81e1726b54e213768892dd940b16` | Current shared-evaluator and packaging changes |
| TotalDebugCompanion | `codex/java21-modernization` | `3dd490cb659afc91cb753ec0df55440b7a849c42` | Current evaluator, debugger, MCP, UI and state changes |
| SCNet | `codex/transport-hardening` | `802221b70ef5a35b63491b15f52ceca8b1795ae3` | Clean working tree |
| JIndex | `codex/reference-index-1.1` | `80f8b43742bd38583d0c9a28bef6e4813edf761b` | Clean working tree |

The user explicitly excluded the in-progress **Profile Companion startup** task. TotalDebug and Companion were copied into `.codex/release-audit-workspace/` for verification, with their `src/main/java/**/runtime/` and `src/test/java/**/runtime/` files taken from these committed bases. The performance slice was neither reviewed nor signed off. These are controlled audit assemblies, not final release candidates. They include the evaluator work and exclude the partial runtime/index changes.

Read-only task context came from **Plan Minecraft mod port**, including its corrections to the pasted system audit, and the scope/status of **Profile Companion startup**. The earlier pasted audit was dated before several fixes and before shared evaluation was added.

The intended upstreams are `tth05/SCNet` and `tth05/JIndex`. The user has a route to upstream write access. Fresh upstream fetches found no upstream-only commits relative to the audited local branches. No branches were pushed, remotes changed, or releases published during this audit.

## Verification performed

All builds used the checked-in wrappers and `C:\Users\Admin\.jdks\temurin-21.0.12`. Coordinated dependencies were built and published to Maven Local before consumers were verified.

| Verification | Result | Limit |
| --- | --- | --- |
| SCNet `clean build publishToMavenLocal -PscnetVersion=2.0.0 --warning-mode fail` | 59 Java tests passed | Additional audit probes found bugs absent from the suite |
| JIndex `clean build publishToMavenLocal --warning-mode fail` | 43 Java tests and 46 Rust tests passed; formatting and Clippy passed | One full-corpus Rust test is ignored by default; native build reused unchanged release output |
| Shared modules build and local publication | Passed | Evaluation tests currently live in the TotalDebug test source set; `:evaluation:test` itself has no tests |
| Isolated TotalDebug `clean build -PtotaldebugUsePublishedCompanion=true --warning-mode fail --no-build-cache` | 415 mod tests plus 12 storage tests passed | Runtime files/tests use the committed base; no live Minecraft run |
| Isolated Companion `clean build --warning-mode fail --no-build-cache` | 422 tests ran, 421 passed, one failed | Missing dark icon variants fail the ordinary build |
| Public dependency resolution with Maven Local absent | Failed for SCNet, JIndex, storage and evaluation | Confirms the source build depends on unpublished local artifacts |
| SCNet deterministic lifecycle/dispatch probes | Two defects reproduced | Uses isolated local sockets, no game connection |
| TotalDebug script lock-order probe | JVM deadlock detection confirmed the lock cycle | Uses production classes with controlled test scheduling |
| Companion evaluator probes against the isolated assembly | Wrong overloads, Java semantic differences, scope failure and retained target pins reproduced | Real JDWP child JVMs, not the user's game |
| Nested-class decompile to MCP member-source lookup | Passed | Does not establish synthetic lambda source mapping |

Commands, outputs, reproducers and artifact hashes are retained in [audit evidence](audit-evidence/2026-09-05/README.md). Full build logs and isolated source assemblies are under `.codex/`. Tests ran locally on Windows; this does not establish clean hosted CI or final modpack acceptance.

During the initial audit, no production source was changed. No deployment, game restart, user-data cleanup, external message, push, or remote publication was performed.

## Standards and lifecycle findings

These are concrete implementation findings. General class-size or architecture preferences are listed separately under cleanup.

### C1. Script Stop and completion can deadlock

Priority P1. `ClientScriptService.stopScript`, disconnect and close hold the service monitor while calling the runner. `ScriptRunner.ScriptRun.scheduleAndReportCompilation` and `finish` hold the run lock while invoking the result sink, which enters the service's synchronized `acceptResult`. The locks can be acquired in opposite order.

The deterministic probe stopped a queued script while compilation reported its result. `ThreadMXBean.findDeadlockedThreads()` reported the compiler blocked in `ClientScriptService.acceptResult:235` and Stop blocked in `ScriptRun.stop:437`.

Evidence: [ClientScriptService](../src/main/java/com/github/minecraft_ta/totaldebug/client/script/ClientScriptService.java), [ScriptRunner](../src/main/java/com/github/minecraft_ta/totaldebug/script/ScriptRunner.java), [captured lock cycle](audit-evidence/2026-09-05/script-lifecycle-probe-output.txt).

Required outcome: release run locks before invoking result callbacks, and avoid entering runner operations while holding service state locks. Preserve message ordering and run identity. Add deterministic tests for stop, compilation completion, result delivery, disconnect and close racing with each other.

### C2. SCNet reconnect can deadlock with a callback

Priority P1. `Client.connect:70` holds the client monitor while `AbstractClient.closeAndAwaitEventLoop:385` waits for the old loop. An active connection or message callback calling synchronized `Client.close:120` needs that monitor, so neither can finish. TotalDebug's handshake failure path calls `client.close` from a message callback, making this a relevant consumer path.

Evidence: [Client](../../SCNet/src/main/java/com/github/tth05/scnet/Client.java), [AbstractClient](../../SCNet/src/main/java/com/github/tth05/scnet/AbstractClient.java), [positive transport probe](audit-evidence/2026-09-05/transport-probe-output.txt).

Required outcome: never wait for transport teardown while holding a lock needed by callbacks. Test reconnect against connected callbacks, message callbacks, disconnect callbacks, and callback-triggered close. The existing disconnected-callback test covers only part of this lifecycle.

### C3. SCNet listener mutation breaks dispatch

Priority P2. `DefaultMessageBus.post:74` invokes listeners while iterating the live list under its registry lock. A listener that registers another listener throws `ConcurrentModificationException`. The exception escapes dispatch and becomes a transport processing failure. One-shot listeners are removed after invocation, which also requires scrutiny for nested posting.

Evidence: [message bus](../../SCNet/src/main/java/com/github/tth05/scnet/message/impl/DefaultMessageBus.java), [positive transport probe](audit-evidence/2026-09-05/transport-probe-output.txt).

Required outcome: define reentrant dispatch behavior; capture/claim callbacks before invoking application code; remove one-shot ownership before invocation. Test callback registration, removal and nested posting. Keep failure reporting explicit.

### C4. SCNet server publication has an unprotected ordering window

Priority P2, source finding requiring a deterministic regression. `Server:130` assigns its client only after `ServerClient` construction, while `ServerClient:33` starts the event loop in its constructor. An early close can call `onClientClosed:146` before publication. The identity check then does nothing and the subsequent assignment can retain a closed client, causing future accepts to be rejected. Acceptance, shutdown and processor/bus configuration also lack one shared lifecycle protocol.

A 5,000-attempt scheduling probe did **not** reproduce this window. Do not present it as another observed production hang.

Evidence: [Server](../../SCNet/src/main/java/com/github/tth05/scnet/Server.java), [ServerClient](../../SCNet/src/main/java/com/github/tth05/scnet/ServerClient.java), [negative scheduling result](audit-evidence/2026-09-05/server-publication-probe-output.txt).

Required outcome: publish the endpoint before starting processing and test the exact ordering with an injectable executor. Coordinate close/configuration with acceptance, preserving the supported single-client contract.

## Specification and workflow findings

### C5. Interpreted expressions do not consistently mean Java

Priority P1. The interpreter's preflight checks syntax, not declared-type binding. Argument evaluation discards declared types before overload resolution. This can invoke a different mutating method from the one Java would select.

Reproduced against real paused JVM fixtures:

| Expression | Observed | Java requirement |
| --- | --- | --- |
| `target.nullOverload((Object) null)` | String overload | Object overload |
| `target.nullOverload((Object) "x")` | String overload | Object overload |
| `"" + warmedBoxingType` | JDI text such as `instance of java.lang.Integer(id=105)` | The Integer's Java string value, `0` in this fixture |
| `true ? 1 : 2L` | `int` result | `long` result |
| `warmedBoxingType + 1` | `Numeric expression required` | Valid unboxing and arithmetic |

Evidence: [JavaExpressionEvaluator](../../TotalDebugCompanion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/debugger/expression/JavaExpressionEvaluator.java), particularly lines 176, 296 and 588; [DebuggerOverloadResolver](../../TotalDebugCompanion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/debugger/expression/DebuggerOverloadResolver.java), line 139; [probe output](audit-evidence/2026-09-05/companion-evaluation-audit-output.txt).

Required outcome: define the interpreter subset by semantics it can preserve, including declared types, unboxing, numeric promotion, string conversion and overload phases. Route other supported fragments to compilation before executing any target code, or reject them with a precise limitation. Never retry through compilation after partial execution. Compare both paths against actual javac execution, including side effects and exceptions.

### C6. Running-game cancellation loses ownership of live execution

Priority P1. The debugger's new tracked cancellation is an improvement, but the ordinary scripting path still removes a run while its code can continue executing.

`ScriptRunner.stop:445-469` calls terminal `finish` when a game-thread script cannot be stopped or an interrupt-resistant thread exceeds the grace period. `finish:485-493` removes it from the run map. `ClientScriptService.acceptResult` removes the active run. Companion's `CodeModeJobService:288-299` labels a cancellation-requested `RUN_EXCEPTION` as `CANCELLED` and drops correlation; `SnippetExecutionService:93-98` completes the request, and Evaluate Everywhere reenables submission.

The error text says the code may still be running, but the machine-readable lifecycle treats it as finished. The existing runner test deliberately exercises this terminal error, so this is an existing behavior to change, not a claim that the text is absent.

Evidence: [ScriptRunner](../src/main/java/com/github/minecraft_ta/totaldebug/script/ScriptRunner.java), [CodeModeJobService](../../TotalDebugCompanion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/mcp/CodeModeJobService.java), [SnippetExecutionService](../../TotalDebugCompanion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/script/SnippetExecutionService.java).

Required outcome: separate cancellation requested, caller stopped waiting, and target execution ended. Retain run identity until actual completion or target loss; make continued execution visible to UI/MCP and prevent accidental replacement from being presented as recovery. Arbitrary Java cannot be promised safe forced cancellation. Test interrupt-resistant worker scripts and already-running tick scripts with bounded fixtures.

### C7. Compiled binding ignores lexical declaration scope

Priority P2. `CompiledFrameEvaluator:201-214` collects declared names across the whole AST, and line 247 uses that global set to suppress field rewriting. In a fixture with owner field `calls`, `{ int calls = 2; } return calls;` fails compilation; `return calls;` succeeds. The local in the ended block must not hide the field in the later return.

Evidence: [CompiledFrameEvaluator](../../TotalDebugCompanion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/debugger/expression/CompiledFrameEvaluator.java), [probe output](audit-evidence/2026-09-05/companion-evaluation-audit-output.txt).

Required outcome: bind names using lexical scopes, including blocks, lambda parameters, catch variables, loop declarations and frame locals. Add success and exception/writeback cases for shadowed names.

### C8. Evicted evaluator results remain pinned in Minecraft

Priority P2. `RichJavaExpressionEngine:89-96` pins every object result, including strings. Evaluation history eviction does not release the corresponding target pins; cleanup waits for resume or detach. A same-pause sequence of 140 string evaluations left 128 history entries and 140 pins.

Evidence: [RichJavaExpressionEngine](../../TotalDebugCompanion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/debugger/expression/RichJavaExpressionEngine.java), [DebuggerEvaluationRunner](../../TotalDebugCompanion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/debugger/expression/DebuggerEvaluationRunner.java), [probe output](audit-evidence/2026-09-05/companion-evaluation-audit-output.txt).

Required outcome: attach pin ownership to retained results and release objects when the last owner is evicted. Keep objects still visible in an inspector valid. Test aliases, strings, large object graphs, eviction, cancellation, resume and detach. Bounded operation metadata is insufficient if the target retains every discarded object.

### C9. Companion's ordinary build currently fails

The new `expand_editor.svg` and `collapse_editor.svg` resources have no `_dark.svg` counterparts. `IconThemeSwitchTest.everyIconShipsADarkVariant:111` fails with those exact names. This exists in the original tree, not only the isolated copy.

Required outcome: finish the icon assets and verify both themes, then restore the full suite to green. Do not remove the test to make the build pass.

### C10. A stale completion can settle a different run after Companion reconnects

Priority P1, reproduced during the implementation follow-up. The Companion session-close handler calls `ClientScriptService.close`, which clears `activeRuns` and requests cancellation. The result correlation key contains only the integer script ID and execution side. If the next Companion session reuses that ID while Minecraft remains running, a completion from the old execution passes the new run's identity check. It removes the new registration, so the actual new completion is subsequently discarded.

The production service and forwarded-result codec were exercised without a game connection. After replacing the Companion session and submitting ID 41 again, delivering the old and then new result produced only `[old session]`. This is distinct from the fixed C1 lock cycle.

Evidence: [probe](audit-evidence/2026-09-05/ScriptSessionIdentityProbe.java), [captured result](audit-evidence/2026-09-05/script-session-identity-output.txt), [service](../src/main/java/com/github/minecraft_ta/totaldebug/client/script/ClientScriptService.java).

Required outcome: give each execution an identity that survives client/server routing and cannot alias across Companion sessions. Reject stale completions by that identity. Retain knowledge of still-running target work across UI/session loss, while accurately separating a lost observer from actual execution termination. Coordinate this with C6, rather than changing only one local map key.

### C11. Find Usages includes a shadowed interface declaration

Fixed in JIndex `a95ac5d`. The independent ASM scan of the captured runtime corpus exposed incorrect inherited-interface resolution. A class implementing a child interface that overrides a parent's default method could report the call under the parent declaration too. A small compiled fixture reproduced this independently of Minecraft.

Resolution now removes interface declarations shadowed by more specific interfaces, excludes inherited private/static methods and selects a unique concrete default where present. Snapshot format 6 rejects older saved reference tables so Companion rebuilds them. The independent oracle compares all 1,393 declared members of Block, Blocks, String and List across 178,117 selected classes, before and after save/reload. Its 168,624 sites and 249,637 occurrences match. This is targeted corpus parity, not every possible target or extraction category. See [corpus evidence](audit-evidence/2026-09-05/asm-member-corpus.json) and the progress record.

### C12. A retired source tree reads the replacement runtime's cache

Fixed locally in Companion `c2b3d1c`; live restart verification remains pending. The deployed startup log contained a cache-identity exception from the decompiled-source tree. A focused regression reproduces the same failure by closing the old decompilation service, opening a new runtime in the same cache directory, and then letting the old reader refresh.

Cached-class listing now shares the service's close lock and returns no entries after closure. Current runtime readers retain their existing identity and integrity checks. The fix avoids stale readers without weakening cache validation or adding a compatibility path. Source lookup on the deployed build already worked; this finding concerns a late refresh during runtime replacement.

### C13. Evaluated array children advertise invalid Java expressions

Fixed locally in Companion `672281c`; deployment replay is pending. Live stdio inspection returned an array child address ending in `.0`, which also feeds the UI's Copy Expression action. Evaluated results created debugger variable proxies without marking arrays as indexed, so the upstream adapter used field syntax.

Result proxies now carry the indexed flag. Breakpoint actions omit the parent address when no expression addresses their result. A real-JDWP regression fails on `values.1` before the fix, then verifies both the corrected `values[1]` address and its evaluated value. The 16 affected integration/resolver tests and shaded packaging pass. See the progress record for the new artifact hash and deployment status.

## Distribution and verification blockers

### R1. The downloaded Companion cannot connect to the current mod

[companion-release.properties](../src/main/resources/META-INF/totaldebug/companion-release.properties) still pins Companion 2.0.0 and SHA-256 `c7f6bf3f63e918aae939f83ddbae68cf2fad904162a387db779f484ea893ea8a`. The published release corresponds to commit `428f391`; its protocol is 2. Both current applications require protocol 10 after the cancellation-status change, up from 9 at the original audit. The [published release page](https://github.com/Minecraft-TA/TotalDebugCompanion/releases/tag/v2.0.0) and checked-in tag source were verified.

An existing installation is a second failure path. [CompanionAppInstaller:86](../src/main/java/com/github/minecraft_ta/totaldebug/client/companion/CompanionAppInstaller.java) accepts any existing regular `TotalDebugCompanion.jar` without checking its expected hash/version. Merely updating the download pin does not update that file. A corrupt or mismatched installed JAR can remain indefinitely.

Required outcome: produce an immutable paired candidate, publish its exact Companion bytes, pin those bytes in the mod, and verify or atomically replace a mismatching installed managed JAR. Keep explicitly configured development JARs a separate intentional path. Also test an already-running incompatible Companion and show an actionable restart requirement. The new preloaded evaluator bridge makes matching applications important even when their SCNet protocol number happens to agree.

Installer cleanup should add finite connect/download progress deadlines, a download byte ceiling, and retryable failure handling. The current request and stream copy have no such bounds. Test stalled responses, bad hashes, truncated/corrupt files, denied writes and offline launch with a valid installed artifact.

Implementation update: TotalDebug `ce390ac` fixes managed-file hash validation, atomic replacement, transfer deadlines and size bounds, with retry/offline and real HTTP stall coverage. The final matching public artifact and pin are still missing. See the progress record for exact limits and tests.

### R2. Public builds still depend on this machine

The current dependency graph requires SCNet 2.0.0, JIndex 1.1.0-SNAPSHOT, totaldebug-storage 2.0.0-SNAPSHOT, and totaldebug-evaluation 2.0.0-SNAPSHOT. Public resolution without Maven Local failed for all four, including fresh Packagecloud checks. [Captured Gradle failure](audit-evidence/2026-09-05/public-dependencies-output.txt).

Storage and evaluation have Maven publications but no remote publication repository. Companion consumes both. All release coordinates, including the shared modules and native artifact, need one recorded version set. Final candidates must not resolve mutable SNAPSHOT dependencies.

Keep Packagecloud for this release. This is the simplest option given the existing repository declarations and workflows, and the user's friend can provide account access. Use the original upstream repositories for SCNet/JIndex. Add shared-module publication from TotalDebug, configure publisher credentials in CI, and verify unauthenticated consumer downloads. No registry migration is needed for this release.

Maven Central is a reasonable later choice but adds namespace, metadata and signing work. GitHub Packages is not a simplification for public Java consumers because its Gradle registry requires authentication. Sources checked: [Central namespaces](https://central.sonatype.org/register/namespace/), [Central publication requirements](https://central.sonatype.org/publish/requirements/), [GitHub Gradle registry](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-gradle-registry).

Make local dependency resolution and automatic sibling builds explicit development options. A public release build must not silently depend on neighboring checkouts or Maven Local. Verify the same sources with an empty Maven Local repository and no sibling checkouts after candidate dependencies are published.

### R3. CI does not gate the supported product

- TotalDebug's [build workflow](../.github/workflows/build.yml) runs on Ubuntu. Its installer test exercises the Windows-only normal installation path without an OS guard. It would fail there after dependency resolution is fixed. This was verified in source, not by running Ubuntu here.
- Companion has only a tag-triggered release workflow. Add Windows push/PR checks before release tags.
- JIndex has only tag publication. Add Windows push/PR Java, native, formatting and Clippy checks.
- SCNet already has push/PR and tag workflows. Add Windows coverage to the existing library checks because that is the actual application platform.

Use Windows as the required release lane. Linux-only library tests can remain useful, but Linux product support is not required. Split slow/integration/UI checks only if needed for reliable CI; tagging every test is not itself an acceptance criterion. Child-JVM and Swing checks must still run somewhere required.

The coordinated lane must test consumer builds against the exact candidate dependencies, verify the packaged mod's embedded modules, smoke-load the shaded Companion, compare duplicated wire contracts, and verify installation of the paired artifacts. Publish/tag jobs alone are not a substitute for branch verification.

Implementation update: all four repositories now have Windows push/PR build workflows; SCNet also retains Linux. Application workflows use empty Maven Local directories. These changes are committed locally and their YAML is validated, but no GitHub run or branch-protection change has occurred. Public dependency availability and a coordinated candidate lane remain open.

### R4. Native and evaluator acceptance is incomplete

JIndex now has useful native lifetime, parsing, signature, CRC, snapshot and query tests. Its optimized resolver is compared with the previous Rust resolver. That is not an independent reference extractor. The [plan](../../JIndex/docs/index-1.1-plan.md) still asks for independent ASM parity and malformed-input testing; the [benchmark record](../../JIndex/docs/index-1.1-benchmark.md) explicitly distinguishes the existing oracle.

Before release, compare normalized targets, sites, kinds and counts against an independent test-only scanner on fixtures and a fixed runtime corpus. Add bounded malformed class/signature/snapshot/query tests, including valid ZIP CRCs with invalid internal offsets/counts. Deserialized cross-section ranges and ordering are not comprehensively validated today. This is a missing integrity gate, not a reproduced corrupt-index crash or proof that every native query is unsafe.

For shared evaluation, complete the [evaluation plan](EVALUATION_PLAN.md)'s supported/rejected context matrix: declared-type semantics, missing local metadata, private/nested/unnamed types, named modules, loader identity, helper compatibility, paused compilation, simultaneous stops, local writeback after errors and bounded retained references. Explicit unsupported contexts can remain unsupported. Tests must prove the exact refusal before side effects. Set Value currently uses Microsoft formatter semantics; document its actual input contract or finish the planned evaluator integration.

Implementation update: snapshot cross-section validation, bounded raw/serialized signature nesting, independent ASM fixtures and the targeted full-corpus member comparison now pass. The original evaluator defects and result ownership issues are fixed within the retained design. [Release scope](RELEASE_SCOPE.md) records its actual limits and Set Value contract. General hostile snapshot allocation and exhaustive native extraction parity remain incomplete; they are not covered by these passing checks. Final live evaluation acceptance remains open.

### R5. Final live acceptance is still open

The old claim that storage has never been deployed is false. [STORAGE.md](STORAGE.md) records deployment to ATM10 Sky and packaged checks. That does not sign off the newer evaluator or the pending performance slice.

[PORTING.md](../PORTING.md) still leaves S21 Find Usages, S22 offline restart and S24 hierarchy navigation live gates open. The final candidate needs one dated, repeatable acceptance run across the dev instance and ATM10, using the hashes actually intended for publication.

Live acceptance update, 2026-09-05: the user verified F6, member usages/result-open and Ctrl+T/U navigation. Integrated-server execution, bounded cancellation and supported breakpoint evaluation passed on the recorded development artifacts. Offline Companion restart now restores search, usages and member source with Minecraft closed; execution reports the expected refusal. [The progress record](RELEASE_PROGRESS.md) links exact artifacts and results. The complete navigation matrix, dedicated-server/non-operator checks, broader debugger/soak coverage and final public candidate installation remain open.

## Repository cleanup for a full clean release

These items belong in the release work, even where they are not independently crash bugs. A clean release means current production paths, accurate user-facing behavior, reproducible artifacts and no accidental development debris. It does not require rewriting every large class.

### TotalDebug and shared modules

- Remove unused packet-block configuration and its setters/validation/tests. No packet blocker ships in this scope; the current setting implies behavior that does not exist.
- Remove dormant protocol message/capability constants on both application sides together. Keep the active contracts explicit and tested, with one intentional paired protocol change if required.
- Remove unused legacy language keys for `/searchreference`, event-listener commands and old startup/decompilation paths. Update tests that currently preserve dead keys. Keep the real current command/keybind strings.
- Keep the shared compiler in `evaluation` and the Minecraft script adapter in `script`. Preserve the new packaged-module test that reproduced the NeoForge split-package failure.
- Give shared evaluation a useful standalone verification task/source set. Publishing `:evaluation` alone currently runs no evaluator tests; the root suite owns them.
- Finish installation bounds, compatibility checks and error recovery in R1. Reuse existing hash/atomic-file helpers where appropriate rather than keeping parallel implementations.
- Validate runtime requirements at the appropriate feature boundary, including an actual `jdk.compiler` for compilation. Current launch validation checks `java.compiler` but not that compiler implementation; the compiler later fails explicitly. Document full JDK 21 requirements rather than implying every Java runtime can execute scripts.
- Refresh README and PORTING versions, embedded-library descriptions, source limitations and release instructions. README's NeoForge 21.1.248 disagrees with `gradle.properties` 21.1.201. PORTING still describes one-expression evaluation and older JIndex ownership/version. Distinguish historical ledger rows from current requirements.
- Replace machine-specific `C:/Users/Admin/...` documentation links with repository-relative links. Mark planning/audit snapshots as historical where storage/evaluation implementation superseded them.
- Ignore `.codex/` and `.codex-remote-attachments/` before staging release changes. Deliberately retain authored docs, source assets and regression tests. Do not delete user instance data or broad temporary directories as cleanup.

### TotalDebugCompanion

- Remove unsupported chunk-grid, packet-logger and legacy search-results messages, registration/guard branches and associated UI together. They are still present although current supported capabilities do not negotiate them. Retain deferred work in branches/history.
- Finish the two icon theme variants and reconcile UI rendering evidence with the final editor behavior.
- Repair evaluator semantics, binding and pin lifetime, and the running-script cancellation projection described above. Promote the audit reproducers into focused production regression tests.
- Use the existing diagnostic log infrastructure consistently for operational failures and remove startup debug chatter, including `JDTHacks` initialization prints. Avoid introducing a logging framework merely to satisfy a checklist.
- Document installation, Windows/JDK/JDWP requirements, offline versus live data, compiler limitations, Set Value semantics, script cancellation, and MCP's trusted-local behavior. Refresh stale screenshots and historical test counts.
- Reconcile the evaluation-plan top-level UI description with the actual auto-detecting/expanding editor and the final test results. Do not mark a stage complete because only its happy path works.
- Record independent shared-library version properties or one explicitly named coordinated dependency version; the evaluation dependency currently reuses `storage_version`.
- Ignore development probe/attachment directories. Verify all required new source files and icons are tracked with the implementation that references them.

### SCNet

- Fix callback/reconnect and dispatch lifecycle first, then acceptance/publication ordering.
- Rewrite README with working Java 21 examples, explicit incoming factories, current Packagecloud coordinates and lifecycle/bounds behavior. The present JitPack `master-SNAPSHOT` and inconsistent example class/constructor names are obsolete.
- Remove obsolete polling settings/getters retained as no-op state and unused legacy `ByteBufferUtils` methods. Keep helpers with real current callers. There is no requirement to preserve superseded development APIs.
- Collapse the duplicate non-null client rejection branches and coordinate server configuration with its connection state.
- Replace compatibility-driven near-2GB defaults with intentional documented limits. TotalDebug already sets explicit frame/string limits, so do not describe the library defaults as an exposed application vulnerability.
- Test sustained sends and slow readers for total queue/direct-memory growth. The outbound queue is unbounded and a direct buffer is allocated per frame. Decide on backpressure/reuse using that evidence. Buffer pooling is not automatically the right fix, and serialization failure is not a reason to replay a possibly side-effecting message.

### JIndex

- Complete R4, retaining the independent scanner in tests only. Record fixture/corpus hashes and normalize comparisons so a passing result can be reproduced.
- Add Windows push/PR checks and keep format/lint/native tests required. Record why a full-corpus test is separate and how release CI runs it.
- Refresh the README feature list, performance figures and incomplete-bindings warning for the current reference/symbol/literal/hierarchy APIs. Document the actual native platform and toolchain.
- Reconcile proposed-format/current-baseline sections of the plan with current code and explicitly close or defer each unmet acceptance clause.
- Remove the unused `destroy()` alias and the test preserving it if the release exposes only `close()`. Keep documented empty-source selection semantics; an unfiltered overload already exists and this is no longer an unexplained footgun.
- Coordinate the final Cargo and Gradle versions. Cargo is already 1.1.0-SNAPSHOT; the old audit's 0.0.39 claim is obsolete.

### Across all repositories

- Record the candidate versions, Git SHAs and artifact hashes in one manifest. Release tags must identify the tested source, not an earlier local build with the same version name.
- Add descriptive Maven metadata and owner-approved project license/notice files. SCNet and JIndex currently lack root license/notice files; their POMs omit license/SCM/developer metadata. This is a release metadata inventory, not a legal conclusion about rights.
- Inventory third-party notices in distributed JARs. Companion's vendored JDT LS license is under the Java source tree; verify that attribution survives packaging. Shadow's duplicate-resource exclusion needs an explicit notice inventory, not an assumption that every dependency's notice survives merging.
- Keep experimental branches out of the candidate. Commit accepted evaluator, packaging and later performance slices as coherent units, with their tests. Review tracked and untracked changes explicitly.
- Add a supported-feature and known-limitations release note. State pre-transform source/reference limitations, missing debug metadata behavior, trusted local MCP and unsandboxed Java execution as product contracts.

Larger optional refactors include splitting Companion's global service owner, removing static window construction and reducing model-to-UI coupling. Perform a focused extraction when a correctness fix needs one; do not make a general application rewrite a prerequisite for this release.

## What changed from the old audit

| Earlier claim or proposed action | Current disposition |
| --- | --- |
| Storage/deployment work is untracked and has never been deployed | Obsolete. Deployment script is tracked; storage commits and deployment evidence exist. Final candidate acceptance remains open. |
| Script transformer has no direct tests and uses ASM's default hierarchy lookup | Fixed in `24651c9`, with regression tests now moved alongside shared evaluation integration. Preserve the tests and verify the final module packaging. |
| Nested same-type constructors are mispaired | Earlier audit did not prove this; the subsequent broad test matrix did not reproduce it. Initialization order and private-member-reference defects found instead were fixed. |
| JIndex destroy export has no null/panic protection; `'static` JNI borrows; no Clippy | Fixed. Invocation-scoped borrows, guarded JNI exports, lifecycle tests and native lint checks are present. |
| Symbol search lacks truncation information | Fixed in JIndex and consumed by Companion. |
| JIndex has no oracle at all | Partially obsolete. Previous-resolver differential exists; independent extraction parity and malformed-input gates remain. |
| Five-second evaluator timeout automatically detaches | Fixed in current evaluator work. Do not restore detach or Continue as timeout recovery. Ordinary script cancellation still needs C6. |
| Move awaited evaluation onto the same single-thread session executor | Reject. It can deadlock. Keep admission/completion serialized and target execution independently owned. |
| Remove every `catch (Throwable)` | Reject as a mechanical rule. Cleanup and target-exception boundaries can require it; inspect whether failures are propagated truthfully. |
| No-token MCP necessarily requires bearer authentication before release | Overstated. Current documented contract is a trusted local endpoint. Verify loopback/Host/Origin behavior and make that trust boundary visible; adding tokens is a separate policy change. |
| Advertising a JVM with no JDWP listener proves unsafe auto-attach | Overstated. Companion's resolver reads actual agent properties and fails with the required JDWP launch argument. Improve affordance/documentation and test this path, rather than adding arbitrary probing. |
| Windows-only support blocks release | False for the agreed scope. CI and documentation must match Windows support. |
| Upstream repositories are inaccessible | Resolved by the user's update. Use upstream repositories; account access for existing Packagecloud publication remains to arrange. |
| Current paired artifacts and dependencies are publicly available | Still false. Stale protocol-2 download and missing public dependencies were reverified. |
| Buffer pooling, class splitting and all deferred features are required first | Not justified. Fix concrete correctness/resource problems and remove obsolete production paths; keep larger scope separate. |

## Ordered work to finish the release

| Work package | Required work | Done when |
| --- | --- | --- |
| W0. Record the candidate | Keep this audit baseline, exclude partial performance work, decide final accepted feature slice, record upstream/version destinations | Every included change has an owner and fixed revision; no partial task is treated as reviewed |
| W1. Repair lifecycle correctness | C1-C4 and C6, including cross-repository result/cancellation contracts | Deterministic regression tests reproduce before the fix and pass afterward; reconnect/stop/close do not hang or lose execution ownership |
| W2. Finish evaluation correctness | C5, C7, C8 and the supported/rejected context matrix | Java semantic oracle cases, lexical binding, writeback and resource lifetime pass through debugger, actions and relevant UI/MCP paths |
| W3. Clean current production state | Per-repository cleanup, icon failure, docs, metadata/notices, generated-file exclusions, standalone module verification | Normal suites are green, documented APIs/features match shipped code, and staged changes contain only intentional files |
| W4. Build release infrastructure | Upstream CI, Packagecloud access/publication for all libraries, explicit local-build mode, stable candidate coordinates | Fresh Windows CI without Maven Local or sibling checkouts resolves and builds the intended candidates |
| W5. Pair and accept candidates | R1 installer changes, compatible Companion pin, completed performance task reviewed at its final revision, live matrix below | One exact candidate pair passes install, runtime and lifecycle acceptance with recorded hashes |
| W6. Publish stable | Review candidate diff, release notes, versions and acceptance evidence; publish dependencies, Companion, then mod in dependency order | Public downloads and clean consumer resolution match the tested hashes; stable publication is explicitly authorized |

W1-W3 can proceed while account access and CI setup happen. Build publication machinery early, but do not tag stable libraries before their correctness work is complete. Use candidate publications where needed to test the public dependency chain.

### Final acceptance matrix

| Area | Required scenarios |
| --- | --- |
| Install/update | Fresh instance without development override; existing correct/mismatched/corrupt Companion; incompatible running Companion; offline reuse; failed/stalled download and retry; instance path with spaces; exact artifact hashes |
| Runtime/platform | Dev client, ATM10 client, integrated server, supported dedicated-server scripting; supported full JDK 21; absent JDWP and unavailable compiler report exact requirements |
| Cache/state | Cold/warm launch, game restart with warm cache, missing/truncated generated data, source replacement, offline restart; authored script/state preservation and failed-save behavior |
| Navigation | F6 targets and JEI fallback; class/field/method usages and result-open; Ctrl+T/U and hierarchy counts; offline decompilation after Companion restart; synthetic/unavailable source handled truthfully |
| Scripts | Client/server routing, disabled policy/non-operator refusal, unsupported server, compiler errors, structured output limits, concurrent IDs, stop-before-start, live cancellation, disconnect/reconnect and completion races |
| Debugger | Attach/detach/reattach, hidden frames, multiple stops, stepping and pause state, interpreter/compiler parity, local writes and exceptions, actions/conditions/watches/previews, pending wait/cancel, object retention and stale references |
| MCP | HTTP and stdio entry paths, loopback/Host/Origin checks, offline refusal, job/evaluation wait/cancel states, disconnect correlation, typed scalars, truncation and source-unavailable contracts |
| Soak/cleanup | Repeated Companion restart and attach, repeated evaluations in one pause, slow-reader transport load, bounded logs/launch cache, released JNI/JDI/file handles; final performance-task regression evidence |

Record date, environment, candidate SHAs/hashes, expected result, actual result and evidence for each gate. Do not replace this matrix with a single "works on ATM10" statement or historical passing test count.
