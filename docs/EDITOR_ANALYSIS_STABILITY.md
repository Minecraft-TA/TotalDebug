# Editor analysis and completion

## Display contract

Display moves directly from the previous useful state to the next. Inserting whitespace or accepting completion must not briefly clear unrelated warnings or semantic colors. Retained display data is never authority for source navigation or edits.

| Action | Diagnostic behavior |
| --- | --- |
| Type a bare receiver, trailing dot or partial member | Defer provisional errors in the active construct, including after analysis or a pause. |
| Edit a name inside existing `();` | Existing delimiters do not finish the edit. |
| Finish the call/statement, leave the edited construct, or explicitly compile | Show current errors for that construct. |
| Accept completion with argument placeholders | Keep those arguments in editing state. |
| Insert a newline, or punctuation inside a string/comment | Do not finish an incomplete construct. |
| Edit one statement beside an established error | Keep the independent error visible, including on the same line. |
| Edit an initializer beside an unresolved generic type | Keep the missing-type error unless its qualified name is itself being edited. |
| Move the caret onto an existing error without editing | Keep the error visible. |

There is no timer granting permission to underline unfinished typing. Compilation always receives the complete source; suppression only changes presentation.

## Ownership

| Owner | Responsibility |
| --- | --- |
| `JavaEditorAnalysis` | One editor's revision, one in-flight analysis, latest-source admission, failure handling and EDT publication. |
| `JavaAnalysis` | Worker-prepared source/map/AST/environment snapshot, diagnostics, semantic spans, recovery and statement bounds. |
| `ASTCache` | Registration and current-snapshot registry for navigation, breadcrumbs, code vision and debugger consumers. No scheduling. |
| `JavaEditingRegion` | Active construct, caret departure and retention of neighboring diagnostics through syntax recovery. |
| `JavaImportWarnings` | Last reliable unused-import results, keyed by import identity and invalidated by possible new uses. |
| `CustomJavaParser` / `CustomJavaTokenMaker` | Render accepted notices / combine lexical tokens with edit-aware semantic spans. |
| `ScriptCompletionController` | Completion admission, cancellation, popup lifecycle, queued acceptance and snippet-aware atomic edits. |
| `ScriptPanel` | Script UI and execution, formatting command, parameter-help interaction. |

Document edits coalesce at the end of the EDT turn. While one parse runs, newer edits only change the desired revision. An obsolete completion starts analysis of the latest source; it cannot publish. A failed current analysis preserves the display and does not retry indefinitely. Closing an editor revokes its registration and prevents late publication.

Every edit revokes the authoritative AST immediately. Display spans can follow unchanged text while work runs. Consumers must use a matching source/map/environment snapshot and must not mutate its JDT tree. Environment replacement discards environment-dependent display and reconciliation history while retaining the active editing scope. Notifications with unchanged identity do not clear display.

The lexer supplies structural boundaries where JDT discards incomplete Java. It ignores comments/literals, balances arguments and brackets, and distinguishes for-header semicolons. Recovered AST bounds can narrow the editing region. Untouched statement bounds prevent a trailing dot from swallowing an established following statement; their previous diagnostics survive unreliable recovery.

JDT can skip unused-import analysis after mandatory errors. An unavailable pass is not an empty successful pass. `JavaImportWarnings` retains checked warnings through that gap and whole-import-block rewrites, but removes warnings when a possible first use appears. `JavaImports` supplies shared import boundaries to source wrapping and warning retention, including incomplete and multiline imports. `JavaEditorTokens` shares only the lexical predicates needed by the two display policies.

Semantic spans survive known recovery gaps; the lexer remains authoritative for strings, comments and token boundaries. Rider Islands syntax values distinguish types, methods, fields, constants and ordinary identifiers in both themes. The OLED reference differs in background, not these syntax categories.

## Completion lifecycle

A request ties its source, mapped caret, environment and cancellation handle together. Results publish only while all still match and the editor retains focus. Escape works before the first popup appears. Source/caret/focus changes, external popup dismissal and disposal cancel pending work and queued acceptance. A failed current request dismisses the popup; obsolete failures cannot dismiss a newer one.

If Enter or Tab arrives while a visible list refreshes, retain selection identity and apply the matching fresh proposal, or the first fresh result if that identity disappeared. Never apply old source ranges. Completion edits and formatting use one atomic, snippet-aware path; import edits preserve placeholder navigation. Disposing the owner restores snippet key bindings. Completion workers use separate regex matchers.

The outer JDT request alone publishes final results. Constructor lookup must not call its monitor's `done()`. Constructor edits consult the declared type before adding a diamond, since JDT's diamond feasibility check alone also accepts nongeneric types. Parameter help continues to use its existing engine.

JIndex remains an external library: 2.1.0 supplies original parameter metadata; Companion owns Parchment lookup and shared readable-name fallback. Its snapshot format changes rebuild cached indexes through existing cache validation. The editor refactor does not change evaluator or compiled Code responsibilities. Historical javac Problems remain tied to submitted source, separately from live JDT notices.

## Verification

Use Java 21 and the root wrapper:

```powershell
.\gradlew.bat :companion:test :evaluation:test :mod:test --console=plain
.\gradlew.bat :check :localBundle -PtotaldebugUseMavenLocal=false --warning-mode fail --console=plain
```

`JavaEditorAnalysisTest` drives real document events and controlled worker delivery. Coverage includes trailing dots, partial names, same-line and following errors, nested calls, literals, for headers, adjacent deletions, import first/last uses, malformed imports, Enter, compound edits, undo/redo, changing bindings, failures/rejections, environment replacement and close/reopen. Paint assertions compare underline pixels before an edit, while analysis is pending, and after publication.

`ScriptCompletionPopupTest` drives the production controller, actual JDT results and popup key listeners. Only worker delivery and native window/focus are controlled. It covers initial and refreshed results, queued Enter/Tab, Escape, external dismissal, failures/rejections, stale success, source/caret/environment changes, constructors, nested generic imports, formatting, disposal and transient diagnostics. `ScriptDiagnosticRefreshTest` retains real ScriptPanel lifecycle coverage. Other focused tests verify proposal edits, ranking, subtype casts, parameter names/help, token classification, theme changes and snapshot consumers.

Pack-specific replays use the installed ATM10 Sky archives, without executing scripts or showing windows. EventBus private access, `.listeners.elements().as`, `.var` nested imports, missing EventListener, ItemStack, BlockPos, constructor prefixes and the GasData recording supplement checked-in synthetic fixtures. Disposable probes and offscreen images live under ignored `companion/build/diagnostic-audit`; they are not alternative production implementations.

## Limits

A file first opened with broken syntax, or reparsed after an environment replacement, has no reliable previous diagnostics for that environment. It shows what JDT can recover; arbitrary malformed Java cannot guarantee perfect neighboring diagnostics. The active-edit policy does not independently commit every subexpression in an unfinished statement. These limits do not justify clearing unrelated established display during ordinary typing.
