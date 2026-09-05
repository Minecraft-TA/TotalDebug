# Decisions before the next release slice

Updated 2026-09-05 after discussion with the user. The release will retain the current interpreter and compiler paths. Replacing the evaluator or making all execution compiler-backed is outside this release.

The project has a usable foundation. SCNet's reproduced lifecycle defects were repairable within its existing transport responsibilities. Shared compilation, runtime discovery and native indexing already have distinct owners. There is no evidence here that a general application rewrite would improve release readiness.

All ten original reproduced findings and the later inherited-interface finding C11 are now resolved, including execution semantics and retained values. The latest [progress record](RELEASE_PROGRESS.md) records 1,071 Java tests and 51 Rust tests passing, with one existing native test ignored. The independent native corpus comparison passes for all members declared by four selected target classes. Exhaustive extraction parity, hostile snapshot allocation bounds, release dependency verification and live candidate acceptance remain open. Passing local suites do not establish release readiness.

## Java evaluation strategy

At the audit baseline, the interpreter selected methods from evaluated runtime values and lost the declared type of `(Object) null`. Numeric promotion, unboxing and string conversion also differed from Java. Companion `95a9ede` fixes the reproduced cases within the retained interpreter, with 39 real JDWP comparisons against compiled Java. This does not implement a complete Java compile-time binder. Generic/poly overload binding, constant-field folding and unrelated reference-conditional least-upper-bound types remain outside the verified parity matrix.

The compiler path already validates entire fragments before invocation and now handles the tested lexical scopes correctly. It requires prepared classpath bytes, local-variable metadata, a preloaded helper and representable frame types. It currently refuses inaccessible/unnamed types, ambiguous loader definitions and several lexical contexts. Flow-scoped patterns are now explicitly refused as well. Making the compiler authoritative would expose those limitations in some frames where the interpreter currently runs.

| Direction | Benefit | Cost or scope decision |
| --- | --- | --- |
| Make the compiler authoritative for executable Java; keep ordinary value inspection through JDI | One Java language authority for overloads, conversions and diagnostics. Reuses the existing compiler and access transformer. | Some paused frames must report an explicit unsupported-context error until their binding support is added. Compilation adds work to explicit evaluation. |
| Retain broad interpreted evaluation and add proper compile-time binding before execution | Preserve more of the existing paused-frame reach while fixing declared-type behavior. | Requires a larger semantic planning/type-binding implementation and parity matrix. It is more than patching the five current examples. |

Decision: keep the existing evaluator for this release. The user does not want a backend replacement before release. The options above record the discussion, not planned implementation. Address concrete reproduced defects within the current design, preserve working frame support, and record any remaining semantic limitations accurately. A broader type-binding or compiler-only redesign is deferred.

## Execution identity and retained results

C6 and C10 are fixed. Stop requests remain pending until target execution ends, and execution IDs cannot alias across Companion sessions. Companion `251d90a` fixes C8 through explicit result ownership. History eviction releases its ownership while an open inspector retains its own lease. Resume/detach invalidates the pause's references. The original probe now retains 128 pins for 128 history entries after 140 evaluations.

The implemented ownership rules are:

- Unique execution identity carried through Companion, Minecraft and server result routing, with a session identity where needed.
- Separate states for cancellation requested, an observer ending its wait or disconnecting, and actual target completion. Keep the target operation recorded until it ends or the target itself is lost.
- Explicit ownership of retained result references. History eviction releases its ownership; an open inspector keeps its own ownership until it closes or the pause expires. Aliases share ownership counts.
- UI/MCP projections of those states and lifetimes, with tests for late results, reconnection, interrupt-resistant scripts, eviction, aliases and open inspectors.

Keep running-game scheduling in TotalDebug and paused JDI execution in Companion. Share small contracts where useful; do not move both execution mechanisms into a general job framework. The existing evaluation plan already assigns these responsibilities correctly.

The C6 slice also fixes terminal-error fallbacks when server result encoding or cancellation transport fails. Regression coverage now verifies that these delivery failures preserve the live operation.

## Cleanup and final testing

The obsolete feature/API cleanup, managed installer repair and Windows workflows are committed. Continue reconciling documentation and metadata with shipped behavior and verifying the remaining audit gates. Preserve working feature paths unless the supported release scope explicitly excludes them. Larger class splits should follow an actual ownership problem, not a file-size threshold.

Keep Packagecloud and the upstream SCNet/JIndex repositories for this release. Account access remains pending. Public dependency resolution, paired installer changes, final artifact pins and the live dev-instance/ATM10 acceptance matrix remain open. Nothing in these local fixes constitutes stable release approval.

Follow-up: managed installer verification and bounds, Windows branch workflows, and the dormant-feature/API cleanup are committed and verified locally. The user deferred the upstream license choice for now; leave license files and license metadata unchanged until that choice is supplied. Packagecloud access and actual public/CI/live candidate verification remain pending.
