# TotalDebug Companion

A desktop source browser, Java scratchpad and debugger for [TotalDebug](https://github.com/Minecraft-TA/TotalDebug). Inspect the classes in your Minecraft instance, follow their references and experiment with the running game.

![Companion collage showing decompiled source, paused debugger frames and variables, and expanded expression results](images/main.png)

The collage uses the current UI to illustrate a breakpoint in FurnaceBlock, its paused stack and local variables, and an evaluation result expanded into nested maps and lists.

## Explore the runtime

Open a block, entity or item from Minecraft with F6, then navigate its decompiled source. Companion includes class and member search, Find Usages, type hierarchies, editor completion and archive resource previews.

The workspace remains open when Minecraft exits. Source browsing and indexed navigation work offline while the runtime archives and Java installation remain available. Instances without a saved runtime expose their mod archives and build a local index automatically. Execution requires a connected game.

Choose a Prism instance from the project selector and use Play to launch it. Minecraft and Companion connect in either startup order and after Companion restarts. The Game status popup also offers Reconnect. The bottom bar shows connection and indexing state; the notification center keeps errors and completed actions with their relevant controls.

## Evaluate and debug

Evaluate expressions or Java statement bodies, inspect their results, and save reusable code as scripts. Run scripts on the client or server according to the server's execution policy.

In the script editor, Ctrl+Space opens completion; Enter or Tab accepts the selected suggestion. Selection survives result updates. Ctrl+P shows call parameters and emphasizes the current argument; Escape closes the popup. Field and method icons show visibility, static and final modifiers. Private members remain available through the script linker.

Ctrl+Shift+Enter completes the statement containing the caret, without opening autocomplete. It adds missing call delimiters and semicolons, opens ordinary conditional/loop blocks, and moves to the next editing position. Missing or empty lambda bodies become multiline blocks, with the caret inside and the enclosing statement's semicolon completed. Missing expressions remain input positions. An open suggestion popup closes without accepting a suggestion. The command is one undoable edit and works without a connected runtime.

Organize scripts in ordinary folders. Right-click a folder to create a script or folder inside it. File and folder menus offer Rename, Move to and Delete; scripts also offer Duplicate. F2 renames the selection. Drag files or folders onto a folder to move them. Moves preserve open editors, drafts and undo history. Stop affected scripts before renaming, moving or deleting them. Deletion uses the recycle bin where available. Duplicate copies the current draft, and external file changes are reported instead of silently overwritten. Single-child folder and package chains share one row in the tree.

At the start of a statement, `if`, `for` and `fori` insert condition, foreach and indexed-loop templates. Enter accepts the template; Tab moves through its fields and into the body.

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


After at least two characters of a member name, completion also offers matching members from indexed subtypes, labelled with the required cast. Accepting one inserts the cast and imports in the same undo step. These suggestions describe possible types; completion does not run game code or inspect the live object.

Live diagnostics appear beside the affected line and as underlines. Diagnostics for the construct being written stay hidden until it is completed, left or run; errors elsewhere remain visible. Compilation errors also appear in the Problems tab. Click a row or press Enter/F4 to navigate to its source. Editing marks old compilation results outdated so they cannot navigate to the wrong text.

The debugger provides breakpoints, stepping, stack frames, variables, watches and breakpoint actions. Breakpoints can use conditions with completion or run a selected script. Paused evaluation uses the selected frame. Code that invokes methods or changes fields can affect Minecraft, and cancellation does not undo those effects.

See [usage and limitations](../docs/USAGE.md) for debugger attachment, supported evaluation contexts and cancellation behavior. Install and update Companion with the matching mod using the [paired installation instructions](../README.md#install).

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

The [MCP API](MCP.md) exposes source queries, Java execution and debugger operations to trusted local clients. The [storage guide](../docs/STORAGE.md) describes scripts, settings, persisted debugger state and generated caches.

## Ownership

`ProjectControls` exposes application-level selection and naming to the project menu and MCP. `CompanionApplication` implements it using its existing project worker; controls never replace scopes or manage runtime resources themselves. Registry list reads use an immutable published snapshot so Swing does not wait for persistence. Name changes refresh the selector separately from project/editor refresh.

`CompanionApp` is the process bootstrap: launch arguments, process lock, logging, look and feel, and application construction. `CompanionApplication` owns the session, debugger, compiler, index loader and project worker. It can run without a UI; `CompanionUi` is the boundary for window lifecycle and navigation. One `ProjectScope` owns the selected profile, instance state, navigation history, pending navigation and nullable `RuntimeBinding`. A scope admits work while ACTIVE; SWITCHING rejects new work but can be cancelled after an editor veto or failed state flush; RETIRED is terminal. Check-and-submit uses the same lifecycle lock as runtime installation. Swing hops and debugger waits run outside that lock.

The scope owns a local file catalog before indexing and publishes one `RuntimeBinding` for the installed local or runtime snapshot. The binding groups its identity, source catalog, classpath, decompiler and reference search, and owns the native index after installation succeeds. Only runtime snapshots bind the script compiler. Local source guards are prepared on the index worker before publication; detecting changed bytes rejects the stale index and queues an application-owned rescan. `CompanionClassIndex` is only JDT's process-wide lookup hook; setting or clearing it never closes an index.

The index loader retains ownership while a candidate is prepared. The application detaches the previous runtime, attaches the new compiler/insight bindings, and completes publication under the existing lifecycle lock. Debugger and UI follow-up runs afterward and cannot return an installed index to the loader's failure cleanup. Closing a runtime detaches its consumers before releasing the index, and is idempotent. A rejected candidate closes its own prepared consumers while leaving index disposal to the loader.

The script compiler and code-insight worker remain application-lived. Open local editors retain the code-insight service, so a runtime changes its binding rather than replacing that service instance. MCP tool declarations identify project-bound requests. Mutations use the captured scope's atomic admission gate; late results check that scope, and navigation also checks runtime identity. Instance state is flushed before retirement and detach. JDT workspace initialization receives its metadata path explicitly through `JDTHacks.init`. Its process globals initialize once; tests share an explicit cache per JVM. Stateless JDT parsing is in `JavaAst`. Each `EditorTabs` owns an `ASTCache`; parsers, highlighting, symbol lookup, code insight and breakpoint completion receive that cache explicitly. Closing project editors clears their cache and detaches semantic listeners. Identical paths in other editor contexts retain their own models.

Java editors receive an `EditorContext` containing their project, runtime-facing services, navigation and window callbacks. Search and settings windows receive their own specific collaborators. `ScriptExecutionService` shares authenticated execution and cancellation between UI and MCP; execution-result subscriptions belong to `CompanionSession` and are removed by their UI/job owners. Window disposal and project switching share the same auxiliary-window cleanup. Equivalent nonblocking Swing dispatch uses `UIUtils.onEdt`; guarded navigation and synchronous lifecycle handoffs retain their distinct boundaries.
