# TotalDebug Companion

A desktop source browser, Java scratchpad and debugger for [TotalDebug](https://github.com/Minecraft-TA/TotalDebug). Inspect the classes in your Minecraft instance, follow their references and experiment with the running game.

![Companion source editor with breakpoints, debugger frames and expanded evaluation results](images/main.png)

Minecraft source with an illustrated debugger pause and a nested Java evaluation result.

## Explore the runtime

Open a block, entity or item from Minecraft with F6, then navigate its decompiled source. Companion includes class and member search, Find Usages, type hierarchies, editor completion and archive resource previews.

The workspace remains open when Minecraft exits. Source browsing and indexed navigation work offline while the runtime archives and Java installation remain available. Instances without a saved runtime expose their mod archives immediately and build a local index automatically. Live tools reconnect when TotalDebug starts again; local indexes do not enable execution.

## Evaluate and debug

Evaluate expressions or Java statement bodies, inspect their results, and save reusable code as scripts. Run scripts on the client or server according to the server's execution policy.

In the script editor, Ctrl+Space opens completion; Enter or Tab accepts the selected suggestion. Selection survives result updates. Ctrl+P shows call parameters and emphasizes the current argument; Escape closes the popup. Field and method icons show visibility, static and final modifiers. Private members remain available through the script linker.

Ctrl+Shift+Enter completes the statement containing the caret, without opening autocomplete. It adds missing call delimiters and semicolons, opens ordinary conditional/loop blocks, and moves to the next editing position. Missing expressions remain input positions. An open suggestion popup closes without accepting a suggestion. The command is one undoable edit and works without a connected runtime.

Organize scripts in ordinary folders. Right-click a folder to create a script or folder inside it. File and folder menus offer Rename, Move to and Delete; scripts also offer Duplicate. F2 renames the selection. Drag files or folders onto a folder to move them; Move to is also available in the menu. Moves preserve open scripts, undo and running state. Deletion uses the recycle bin where available and requires stopping affected running scripts first. Duplicate copies the current draft, and external file changes are reported instead of silently overwritten.

Postfix templates transform the expression before the dot:

| Suffix | Available for | Expansion |
| --- | --- | --- |
| `.for` | Arrays and `Iterable` values | Enhanced loop with an inferred element type |
| `.fori` / `.forr` | Arrays, lists and integral bounds | Forward / reverse indexed loop |
| `.if` / `.else` | Boolean expressions | Positive / negated condition |
| `.nn` / `.null` | Reference values | Non-null / null check |
| `.not` | Boolean expressions | Negated expression |
| `.logln` / `.sout` | Non-void values | Script logging call |
| `.var` | Non-void values | Local variable with inferred type and imports |

Accept with Enter or Tab, edit the selected variable name, then Tab into the body. Loop index references are linked, generated names avoid existing identifiers, and an expansion is one undoable edit. Statement templates are offered at statement boundaries inside blocks; `.not` also works within expressions. `.forr` visits array/list indices from the last to zero, or counts a numeric bound down to one. A computed forward-loop bound is evaluated once.


After at least two characters of a member name, completion also offers matching instance members from indexed subtypes, labelled with the required cast. Accepting one inserts the cast and imports in the same undo step. These are possible types, not observations of the live object. Searches skip `Object`, inspect at most 256 subtypes with the receiver's package first, and resolve at most six matching cast targets. No game code runs during completion.

Compiler errors appear as Problems rows with severity and script locations. Click a row or press Enter/F4 to select its source; the context menu also offers copying. Editing marks the previous compilation's problems outdated and disables navigation until the submitted text is restored or the script is compiled again. Live editor markers refresh separately against the current source. Analysis coalesces edits without an idle deadline. Errors in the construct being written stay hidden while it is unfinished, including when pausing or browsing completion. Completing the edited part, leaving it, or running the script releases its diagnostics; completed errors on the same line remain visible. Unaffected markers keep their positions, incomplete member expressions retain receiver colors where recovery permits, and source navigation uses only current analysis.

The debugger provides breakpoints, stepping, stack frames, variables, watches and breakpoint actions. Paused evaluation uses the selected frame. Code that invokes methods or changes fields can affect Minecraft, and cancellation does not undo those effects.

See [usage and limitations](https://github.com/Minecraft-TA/TotalDebug/blob/1.21.1/docs/USAGE.md) for debugger attachment, supported evaluation contexts and cancellation behavior.

## Build and run

Companion requires **Windows x64 and a full JDK 21**. The application JAR contains its dependencies, but no Java runtime.

From the repository root, run these commands. The shared libraries build automatically. See [build instructions](../docs/BUILD_RELEASE.md) for checks and paired releases.

```powershell
.\gradlew.bat :companion:shadowJar
java -jar companion/build/libs/TotalDebugCompanion.jar
```

TotalDebug can launch the same JAR from Minecraft. Standalone startup reopens the last profile when one is available.

For UI changes, the test harness renders named states without a game session:

```powershell
.\gradlew.bat :companion:uiHarness '--args=--theme=islands-dark --scenario=search-results'
.\gradlew.bat :companion:uiHarness '--args=--list-scenarios'
.\gradlew.bat :companion:uiContactSheet
```

Contact sheets and individual captures are written under `companion/build/ui-screenshots`.

## Integrations and storage

The [MCP API](MCP.md) exposes source queries, Java execution and debugger operations to trusted local clients. The [storage guide](https://github.com/Minecraft-TA/TotalDebug/blob/1.21.1/docs/STORAGE.md) describes scripts, settings, persisted debugger state and generated caches.

## Ownership

`ProjectControls` exposes application-level selection and naming to the project menu and MCP. `CompanionApplication` implements it using its existing project worker; controls never replace scopes or manage runtime resources themselves. Registry list reads use an immutable published snapshot so Swing does not wait for persistence. Name changes refresh the selector separately from project/editor refresh.

`CompanionApp` is the process bootstrap: launch arguments, process lock, logging, look and feel, and application construction. `CompanionApplication` owns the session, debugger, compiler, index loader and project worker. It can run without a UI; `CompanionUi` is the boundary for window lifecycle and navigation. One `ProjectScope` owns the selected profile, instance state, navigation history, pending navigation and nullable `RuntimeBinding`. A scope admits work while ACTIVE; SWITCHING rejects new work but can be cancelled after an editor veto or failed state flush; RETIRED is terminal. Check-and-submit uses the same lifecycle lock as runtime installation. Swing hops and debugger waits run outside that lock.

The scope owns a local file catalog before indexing and publishes one `RuntimeBinding` for the installed local or runtime snapshot. The binding groups its identity, source catalog, classpath, decompiler and reference search, and owns the native index after installation succeeds. Only runtime snapshots bind the script compiler. Local source guards are prepared on the index worker before publication; detecting changed bytes rejects the stale index and queues an application-owned rescan. `CompanionClassIndex` is only JDT's process-wide lookup hook; setting or clearing it never closes an index.

The index loader retains ownership while a candidate is prepared. The application detaches the previous runtime, attaches the new compiler/insight bindings, and completes publication under the existing lifecycle lock. Debugger and UI follow-up runs afterward and cannot return an installed index to the loader's failure cleanup. Closing a runtime detaches its consumers before releasing the index, and is idempotent. A rejected candidate closes its own prepared consumers while leaving index disposal to the loader.

The script compiler and code-insight worker remain application-lived. Open local editors retain the code-insight service, so a runtime changes its binding rather than replacing that service instance. MCP tool declarations identify project-bound requests. Mutations use the captured scope's atomic admission gate; late results check that scope, and navigation also checks runtime identity. Instance state is flushed before retirement and detach. JDT workspace initialization receives its metadata path explicitly through `JDTHacks.init`. Its process globals initialize once; tests share an explicit cache per JVM. Stateless JDT parsing is in `JavaAst`. Each `EditorTabs` owns an `ASTCache`; parsers, highlighting, symbol lookup, code insight and breakpoint completion receive that cache explicitly. Closing project editors clears their cache and detaches semantic listeners. Identical paths in other editor contexts retain their own models.

Java editors receive an `EditorContext` containing their project, runtime-facing services, navigation and window callbacks. Search and settings windows receive their own specific collaborators. `ScriptExecutionService` shares authenticated execution and cancellation between UI and MCP; execution-result subscriptions belong to `CompanionSession` and are removed by their UI/job owners. Window disposal and project switching share the same auxiliary-window cleanup. Equivalent nonblocking Swing dispatch uses `UIUtils.onEdt`; guarded navigation and synchronous lifecycle handoffs retain their distinct boundaries.
