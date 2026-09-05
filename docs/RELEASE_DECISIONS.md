# Decisions before the next release slice

Updated 2026-09-05 after discussion with the user. The release will retain the current interpreter and compiler paths. Replacing the evaluator or making all execution compiler-backed is outside this release.

The project has a usable foundation. SCNet's reproduced lifecycle defects were repairable within its existing transport responsibilities. Shared compilation, runtime discovery and native indexing already have distinct owners. There is no evidence here that a general application rewrite would improve release readiness.

The unresolved correctness problems concentrate in execution semantics and ownership. The latest [progress record](RELEASE_PROGRESS.md) has 951 passing suite tests, but the separate audit probes still reproduce C5 and C8, and now C10. Passing suites alone do not close those findings.

## Java evaluation strategy

The interpreter currently selects methods from evaluated runtime values. It cannot distinguish `(Object) null` from a null intended for a more specific overload. Its syntax preflight also permits expressions with incorrect numeric promotion, unboxing and string conversion. The completed-task recheck reproduced all of those cases.

The compiler path already validates entire fragments before invocation and now handles the tested lexical scopes correctly. It requires prepared classpath bytes, local-variable metadata, a preloaded helper and representable frame types. It currently refuses inaccessible/unnamed types, ambiguous loader definitions and several lexical contexts. Flow-scoped patterns are now explicitly refused as well. Making the compiler authoritative would expose those limitations in some frames where the interpreter currently runs.

| Direction | Benefit | Cost or scope decision |
| --- | --- | --- |
| Make the compiler authoritative for executable Java; keep ordinary value inspection through JDI | One Java language authority for overloads, conversions and diagnostics. Reuses the existing compiler and access transformer. | Some paused frames must report an explicit unsupported-context error until their binding support is added. Compilation adds work to explicit evaluation. |
| Retain broad interpreted evaluation and add proper compile-time binding before execution | Preserve more of the existing paused-frame reach while fixing declared-type behavior. | Requires a larger semantic planning/type-binding implementation and parity matrix. It is more than patching the five current examples. |

Decision: keep the existing evaluator for this release. The user does not want a backend replacement before release. The options above record the discussion, not planned implementation. Address concrete reproduced defects within the current design, preserve working frame support, and record any remaining semantic limitations accurately. A broader type-binding or compiler-only redesign is deferred.

## Execution identity and retained results

C6 still reports a terminal script status after a Stop timeout even when target Java continues. C10 proves a stale result can settle a replacement run after Companion reconnects. C8 retains discarded result objects in Minecraft until resume/detach, with no explicit ownership from history or an open inspector.

The proposed next ownership slice should provide:

- Unique execution identity carried through Companion, Minecraft and server result routing, with a session identity where needed.
- Separate states for cancellation requested, an observer ending its wait or disconnecting, and actual target completion. Keep the target operation recorded until it ends or the target itself is lost.
- Explicit ownership of retained result references. History eviction releases its ownership; an open inspector keeps its own ownership until it closes or the pause expires. Aliases share ownership counts.
- UI/MCP projections of those states and lifetimes, with tests for late results, reconnection, interrupt-resistant scripts, eviction, aliases and open inspectors.

Keep running-game scheduling in TotalDebug and paused JDI execution in Companion. Share small contracts where useful; do not move both execution mechanisms into a general job framework. The existing evaluation plan already assigns these responsibilities correctly.

Source review also found terminal-error fallbacks when server result encoding or cancellation transport fails. Those paths need regression coverage in the ownership slice: a delivery failure must not claim that target execution ended. This is a source finding, not another reproduced target failure.

## Cleanup and final testing

After agreeing the evaluation and ownership direction, return to the audit's cleanup inventory. Remove obsolete production features and no-op APIs, reconcile documentation and test claims, and fix repository metadata and required Windows CI. Preserve working feature paths unless the supported release scope explicitly excludes them. Larger class splits should follow an actual ownership problem, not a file-size threshold.

Keep Packagecloud and the upstream SCNet/JIndex repositories for this release. Account access remains pending. Public dependency resolution, paired installer changes, final artifact pins and the live dev-instance/ATM10 acceptance matrix remain open. Nothing in these local fixes constitutes stable release approval.
