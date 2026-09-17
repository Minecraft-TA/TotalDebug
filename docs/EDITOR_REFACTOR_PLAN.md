# Editor stability refactor

Baseline: `2c2ab1b9`, preserved from the working state accepted by the user. The release target is `1.21.1`. This branch also contains the earlier UI commits that have not yet landed there. The unrelated automatic-connection plan is excluded.

## Contract to preserve

- Diagnostic and semantic display changes directly from the previous useful state to the next. Harmless edits do not blank underlines or colors.
- Active incomplete code stays free of provisional errors. Independent neighboring errors and unused imports survive; unresolved types elsewhere in the active statement remain actionable.
- Current-source navigation consumes a matching source/AST/map/environment snapshot. Retained display data cannot act as semantic authority.
- Completion uses current source ranges, performs one undoable edit, imports nested types correctly, and never leaves a stale popup consuming Enter. Escape, focus/caret/source/environment changes and disposal invalidate pending work.
- Existing evaluator and compiled Code responsibilities do not change.

## Findings from the initial audit

1. ScriptPanel owns completion scheduling, cancellation, popup state, edit application and signature help alongside script execution. Popup tests reconstruct private request state and bypass production admission.
2. A constructor-search helper calls the outer completion monitor's `done()`, publishing an empty/incomplete list before final results. The outer request must own terminal publication.
3. Escape cannot cancel the first pending completion because popup listeners are not installed yet.
4. Environment replacement clears displayed diagnostics but retains old statement reconciliation history, which can discard fresh neighboring errors.
5. Published JIndex 2.0.0 lacks the parameter-name API used by this branch. Maven Local has masked this reproducibility failure.

## Implementation plan

1. Keep JavaAnalysis as the prepared result, ASTCache as the current-result registry, and JavaEditorAnalysis as the EDT owner. Remove redundant scheduling state where freshness can decide whether another request is needed.
2. Extract one package-private ScriptCompletionController. It owns request admission, cancellation, completion popup lifecycle, queued Enter/Tab intent and atomic edits. Inject the existing editor, popup, executor and completion-applied callback. Keep parameter help in ScriptPanel. Use one request record tying identity to source and caret, rather than visibility as a freshness signal. No generic asynchronous framework.
3. Replace reflective request choreography in popup tests with the production controller and a controlled executor. Preserve a small real ScriptPanel integration test. Cover first/refresh requests, cancellation, failure, rejection, stale success, source/caret/environment changes, queued acceptance, and disposal.
4. Reset environment-dependent presentation history explicitly while preserving active editing scope. Consolidate only duplicated lexical predicates that have two real consumers; do not add another parser or a diagnostic dependency graph.
5. Fix constructor request completion ownership and prove one publication for actual JDT constructor prefixes. Preserve import/source-map recovery and test malformed inputs, compound edits and undo.
6. Resolve the external JIndex version through its own reviewed release, then build TotalDebug with Maven Local disabled. Check evaluation and both consumers. Fix reproducible suite failures instead of accepting a permanently red baseline.
7. Replace the chronological audit narrative with the current behavior, ownership, verification commands and real limitations. Record final evidence separately from superseded test counts.

## Validation and landing

Run targeted owning-module tests during changes, then full Companion/evaluation/mod tests and CI's clean-dependency checks. Replay the recorded ATM10 Sky scenarios against the stored index and inspect offscreen paints. Audit the final diff on standards and behavior axes. Commit refactor/fixes separately from the preserved baseline. Open the PR against `1.21.1`, wait for CI and automatic review, resolve findings and repeat for each new head. Merge only after the final head is clean and audited. Do not install builds into the user's running applications.

## Implemented and verified

- Extracted the completion owner and replaced private request-state test setup with real admission and controlled execution. Completion and formatting share atomic snippet-aware edits; disposal restores bindings.
- Fixed duplicate constructor publication, pending Escape, external popup dismissal, environment-history reuse, nongeneric diamonds and implicit `java.lang` constructor ties. The latter was found by replaying `new String` against the full pack rather than a small fixture.
- Removed shared mutable snippet matchers. Fixed offscreen focus-test isolation without altering production theme behavior.
- Published JIndex 2.1.0 through its separately audited PR and successful release workflow. Its UTF-16 parameter metadata survives legal surrogate code units and snapshot reload. The same ATM10 Sky corpus measures a 5.08% snapshot increase; see [storage measurement](benchmarks/parameter-names-atm10sky.md).
- The published-dependency `:check :localBundle` passed, including normal tests, packaging and build-logic checks. Focused tests passed after the constructor-ranking follow-up.
- Replayed EventBus dots/partial names, chained Enter, `.var` nested imports, GasData warning continuity, missing EventListener, constructors and ItemStack against the released-format pack index. Both themes passed offscreen assertions. A 50-edit burst submitted one parse in each theme.
- Independent standards and behavior reviews passed after correcting the external-dismissal finding. GitHub CI and automatic review remain required before merging the final head.
