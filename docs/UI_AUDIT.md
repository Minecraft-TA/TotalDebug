# UI audit and proposed direction

Audit date: 13 September 2026. Planning document, not an implementation change.

## Implementation progress, 15 September 2026

The findings and action counts below describe the original audit. These slices have since been completed:

- Find Usages search highlighting, navigation between loaded matches, and result context actions.
- Compact editor tabs, tab context actions, and alignment with the Files header.
- Immediate breakpoint gutter toggling and hover updates during repeated clicks.
- Breakpoint list actions, keyboard shortcuts, validation, preserved edits, conditional fields, and clearer labels.
- JetBrains Jump to Source icons in Find Usages and debugger source actions.
- Stack-frame source navigation, copying a frame or the displayed stack, and context-menu shortcuts.
- Consistent value, expression, and type copying across debugger and evaluation results, plus output copying.
- Source and reference actions in Search Everywhere, hierarchy results, and file-tree classes; path copying for files and archives.
- Shared tree/list menu handling and explicit default-copy actions, with feature actions and validation kept in their owning views.

Script selection, bulk breakpoint operations, global keyboard focus, and the remaining audit items are still open.

## Original audit

The UI has a clear identity already: a compact desktop source browser with an editor at its center, restrained Islands colors, familiar code icons, and contextual debugging tools. Keep that direction. The larger pass should finish the interaction design and bring secondary tools up to the standard of the editor and search. A new visual theme would leave the largest problems unresolved.

The strongest shared work is in editor chrome, theme handling, structured row labels, source navigation, and anchored previews. The weakest areas are object actions, keyboard access, script management, result extraction, and consistent feedback. Several screens look related but require users to learn different rules for essentially the same operation.

## Evidence and limits

This audit covers the Companion UI implementation, its named rendering scenarios, and the Minecraft entry points and startup messages. It includes the project selector and Prism picker currently being developed in the working tree. Those files were changing during the audit; findings describe the inspected state, not a fixed release.

The rendering harness uses sample data and a 1280 by 720 main window. Captures demonstrate layout and theme behavior at that size. They do not establish live debugger behavior, keyboard usability, screen-reader support, or behavior at larger fonts and Windows scaling. Findings below distinguish visible observations, source-confirmed behavior, and risks that still need interactive verification.

The [scenario inventory](../companion/src/test/java/com/github/minecraft_ta/totalDebugCompanion/UiRenderScenario.java) contains 23 states per theme in the inspected checkout. The [capture manifest](../companion/build/ui-screenshots/manifest.json) and [dark](../companion/build/ui-screenshots/contact-sheet-islands-dark.png) / [light](../companion/build/ui-screenshots/contact-sheet-islands-light.png) contact sheets are generated build outputs, not durable checked-in assets. Regenerate them with the documented `:companion:uiContactSheet` task and JDK 21. The first capture attempt encountered a missing compiled harness class during concurrent checkout activity; the completed review uses isolated capture inputs.

## Where the UI stands

These are qualitative judgments, not numerical usability scores.

| Area | What is working | What needs attention | Priority |
| --- | --- | --- | --- |
| Main workspace and editor tabs | Clear hierarchy, restrained separators, readable source, consistent icon family, tab hover and close treatment | No tab context menu; navigation and search are hard to discover; one-pixel splitter is a very narrow drag target; empty workspace offers little guidance | High |
| File tree | Familiar package structure, module metadata, Enter and double-click navigation, speed search | Only local files get a menu, containing only Delete; folders, classes, archives, and modules lack object actions; `scripts`, `decompiled-files`, and `Runtime` mix storage names with product labels | High |
| Source editor and gutter | Most developed area: semantic colors, line numbers, usages, hierarchy hints, breakpoint and execution markers | Right-click clears selections; existing semantic menu actions do not cover declaration navigation, copying references, or evaluation; readiness and applicability should be reflected before invocation | High |
| Search Everywhere and module filter | Strong composition: categories, query, filters, structured results, keyboard hints, empty and failure states | Search has no obvious main-window entry; results lack menus; footer and metadata are too faint in dark captures; Tab cycles categories while the module filter button is not focusable | High |
| Hierarchy hover and chooser | Small anchored preview, readable symbol rows, shared popup borders and placement | Chooser lacks result actions; failure gives exception type rather than an actionable explanation; one-result hover names the target while multiple implementations show only a count | Medium |
| Find Usages | Useful grouping, counts, bounded result loading, cancellation, Enter navigation | Speed-search highlighting is broken and match switching is reported unreliable; extra scroll-pane frame; oversized two-row header; long signatures push useful information offscreen; no copy, result menu, or branch expansion actions | High |
| Saved script editor | Completion, formatting, client/server execution, results, shared source styling | Save and Format are shortcut-only; execution side is encoded in two icons; output chrome differs from evaluator; saving after focus loss needs clearer feedback; result and output actions are sparse | High |
| New Script | Small and fast for someone who knows the interaction | Color-only validation; no explanation of duplicate versus invalid name; no visible Create or Cancel; dismisses on deactivation; separate two-pixel border treatment | High |
| Evaluate Expression | Shared expandable Java input, context choice, history, Save as Script, separated Result/Output/Problems | Mostly blank results region before a run; history and saving hidden behind one arrow; Run/Stop presentation differs from scripts; result menus differ by execution context | High |
| Debugger frames and inspector | Clear frames/values split; good inline input; strongest existing object menu; familiar stepping controls | Frames cannot copy a stack; watch editing is incomplete; dense status toolbar competes with location; most toolbar buttons opt out of focus; routine debugging occupies a separate window | High |
| Breakpoint editor and manager | Small editor has inline hit-count validation; manager exposes conditions and actions | Different save behavior for the same fields; manager has large unused space; mixed conditional fields; saved-script action asks for a path rather than offering selection; no row menu or bulk selection | High |
| Snapshot and paused evaluation results | Structured values, lazy expansion, explicit truncation/lifetime information | Snapshot tree, paused result panel, and inspector offer different actions for similar-looking rows; users cannot consistently copy a value or its type | High |
| Text and image resources | Text reuses editor styling; image has fit, zoom and checkerboard; resource loading has local Retry | Image toolbar uses a different button style and is not focusable; no image copy/save menu; failure detail is a plain centered label with limited room | Medium |
| Settings | Small, understandable groups; changes apply immediately; both editor and UI font controls | Global appearance and per-project exception settings lack scope labels; no shared field-label association pattern; focus treatment weak; sizing should be checked while increasing font size | Medium |
| Project selector and Prism picker | Clear current/recent split; project paths; searchable instance list; picker has explicit Open and Cancel | Project maintenance is hidden in nested right-click menus; error title always says opening failed even for rename/remove failures; dense project menu and spacious picker should be intentional variants | Medium |
| Status bar and breadcrumbs | Game, MCP, runtime indexing and editor context are separated; concise steady-state layout | Useful paths and hints use disabled-looking text; detail popups use disabled menu items that cannot be selected/copied; long breadcrumbs and status strings compete for width | High |
| Completion, signature help, editor Find, speed search | Reusable mechanisms already reach many lists/editors | No complete named capture coverage; Find shows a blank count at zero matches and has no visible close control; focus, nested popup and toggle behavior need dedicated checks | Medium |
| Minecraft entry and startup feedback | One-press F6 interaction; download/start/connect stages; failure points to chat | Align naming with Companion; make the offline/reconnecting relationship clear in desktop feedback. Keep Minecraft controls appropriate to the game rather than adding desktop context menus there | Low |

## Findings to address first

1. **Restore reliable keyboard operation and visible focus.** [CompanionDefaultsAddon](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/theme/CompanionDefaultsAddon.java) makes focus colors transparent globally. Selection cannot communicate focus for a button or an unselected field. Several status, image, debugger and filter controls also call `setFocusable(false)`. [FlatIconButton](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/components/FlatIconButton.java) changes its toggle state in `mouseClicked`, so keyboard activation does not take the same toggle path. Use action/button-model behavior and a restrained visible focus indicator. Verify every icon-only control has a usable accessible name and an activation route.

2. **Fix right-click targeting before expanding menus.** [CodeViewPanel.configureContextMenuCaret](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/components/editors/CodeViewPanel.java) always calls `setCaretPosition` at the click. Preserve a selection when the click is inside it. Elsewhere, select the object under the pointer. [LazyFileJTree](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/components/treeView/lazyFileTree/LazyFileJTree.java) checks right clicks in `mouseClicked` and returns early for directories; [DebuggerInspector](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/views/debugger/DebuggerInspector.java) uses platform popup triggers on press/release. Adopt the latter pattern plus keyboard invocation. Do not open a menu against a nearby row when the click is in empty space.

3. **Make local-file deletion an understandable operation.** [FileTreeView](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/components/treeView/FileTreeView.java) exposes Delete as its only local-file menu action. The tree also invokes deletion from the Delete key. [FileSystemFileItem.delete](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/components/treeView/lazyFileTree/FileSystemFileItem.java) permanently deletes and silently swallows I/O failure. There is no editor-aware close/save coordination in this path. Handle the selected set, unsaved/running scripts, failures, and user recovery deliberately. Prefer recoverable deletion where supported; otherwise identify the exact files before permanent deletion. Keep runtime/archive items read-only.

4. **Separate secondary text from disabled text.** [ThemeColors.mutedText](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/theme/ThemeColors.java) resolves `Label.disabledForeground`. [PrimarySecondaryLabel](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/presentation/PrimarySecondaryLabel.java) still uses that role for ordinary single-line metadata, while stacked project rows use `secondaryText`. The dark captures show this in package names, frame locations, search hints and breadcrumbs. Use the readable secondary role for information users need; reserve disabled styling for unavailable controls. Measure the final colors in both themes before claiming accessibility conformance.

5. **Give related execution tools the same presentation rules.** [ScriptPanel](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/components/editors/ScriptPanel.java), [EvaluateExpressionWindow](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/views/EvaluateExpressionWindow.java), [ScriptResultTree](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/components/values/ScriptResultTree.java), and [DebuggerResultPanel](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/views/debugger/DebuggerResultPanel.java) repeat input/result/status concepts with uneven controls. Standardize context labels, run/cancel feedback, Result/Output/Problems styling and copy behavior. Preserve the existing evaluator and compiled Code responsibilities. A captured snapshot must not imply that it is a live editable debugger object.

6. **Finish the small forms.** [CreateScriptWindow](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/views/CreateScriptWindow.java) should show a name label, distinct duplicate/invalid-name errors, and Create/Cancel. [BreakpointEditorPopup](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/components/editors/BreakpointEditorPopup.java) commits through Done/Enter; [BreakpointsWindow](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/views/debugger/BreakpointsWindow.java) also saves on blur and selection change. Choose explicit Apply/Done for breakpoint text edits, keep enable toggles immediate, and show validation next to the affected field. Only show Java or saved-script controls when that action kind is selected.

7. **Make common actions discoverable.** [MainWindow](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/views/MainWindow.java) offers File with Settings, Script with evaluation/creation, and a debugger status menu. Search relies on double Shift. Add a compact visible Search entry and ordinary Navigate/Debug command access. Keep shortcuts. A user should not need prior IDE knowledge to find search, history, usages, or breakpoint management.

8. **Plan layout around changing content.** [UiMetrics](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/UiMetrics.java) defines only tree, tab and status heights. Other screens independently choose 24/28/32-pixel rows and fixed window/control sizes. These differences can be legitimate density variants, but should be named and sized against the font. [WorkspacePanel](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/components/global/WorkspacePanel.java) and debugger splits use a one-pixel divider. Keep a thin visual rule with a larger interaction target. Test long signatures, paths, failure messages and large fonts. Clipping at larger scales remains a risk, not a reproduced finding from the standard-size captures.

9. **Repair speed search in Find Usages before polishing that view.** The user confirmed that typing in the usages tree fails to highlight matches and that moving between matches works poorly. This is the tree's speed search, not the double-Shift Search Everywhere window. An audit probe using production usage nodes, the actual usage renderer, and SpeedSearch reproduced the highlighting defect: query `apply` returned match range 0..5, but the rendered row changed by zero pixels. `UsageTreeCellRenderer` uses the default tree label without the match-painting support used by `PrimarySecondaryLabel`. Make the row's searchable text and its highlighted text agree.

   The same small probe cycled two expanded leaf matches correctly, `0 -> 1 -> 0`. It does not reproduce or dismiss the reported switching defect: it directly invokes key handlers and does not cover real window focus, grouped results, collapsed branches, grouping changes, or scrolling. The shared tree adapter currently searches visible rows only. Decide whether Find Usages should search every loaded usage and expand its ancestors; my recommendation is yes, without triggering another index query. Keep group headings out of leaf-result navigation unless explicitly requested. Reproduce the switching failure with the actual grouped view before claiming a cause or a fix.

   Audit evidence is in the temporary [UiUsagesAuditProbe](../build/UiUsagesAuditProbe.java). It exits with an assertion failure for the missing highlight and makes no production changes. Run it with JDK 21, `-Djava.awt.headless=true`, and the captured Companion runtime classpath in `build/ui-audit-classpath.txt`. A future regression test must cover rendered highlighting and actual key/focus dispatch, not just direct calls to the match-navigation handler. This investigation stops at evidence gathering because the requested deliverable is an audit and plan.

## Right-click inventory

Five domain areas currently supply custom object-specific right-click behavior: local files, source-editor symbols, breakpoint gutter markers, debugger inspector values/watches, and project entries. Text editors also inherit library editing menus. Left-click dropdowns for grouping, history, filtering and status are not object context menus and are not counted as coverage.

The proposed baseline below contains **62 additional menu placements across 18 contexts**. A placement means one command made available in one context. It does not mean 62 new backend capabilities or 62 independent defects. A command such as Open or Copy value appears in several contexts, and some already exist as buttons or shortcuts. These are planning recommendations with explicit counting rules, not an exhaustive count of every possible menu item.

| Context and owner | Existing behavior | Additional baseline entries | Count |
| --- | --- | --- | ---: |
| Editor tab, `EditorTabs` / `EditorTabHeader` | Close button and middle-click; no tab menu | Close; Close others; Close all; Copy location; Reveal in tree | 5 |
| Local file, `FileTreeView` | Delete only | Open; Rename; Duplicate; Copy path; Show in Explorer | 5 |
| Scripts folder, `FileTreeView` | No folder menu | New script; Copy path; Show in Explorer | 3 |
| Runtime class, resource, module or package, `FileTreeView` | No object menu | Open; Copy reference; Find usages; Search in module | 4 |
| Source editor, `CodeViewPanel` | Editing/history plus Find Usages, implementations and base methods | Go to declaration; Copy reference; Evaluate selection; Toggle breakpoint | 4 |
| Script editor, `ScriptPanel` | Editing/history; Save and Format shortcuts; Run toolbar | Save; Format; Run on client; Run on server | 4 |
| Search result, `SearchEverywherePopup` | Enter/double-click Open | Open; Copy reference; Find usages | 3 |
| Usage row or group, `UsagesViewPanel` | Open; separate Group by dropdown | Open; Copy location; Copy results; Expand branch; Collapse branch | 5 |
| Hierarchy result, `ImplementationChooserPopup` | Enter/double-click Open | Open; Copy reference; Find usages | 3 |
| Stack frame, `DebuggerFramesPane` | Selection previews source; Enter/double-click activates it | Open source; Copy frame; Copy stack trace | 3 |
| Breakpoint row, `BreakpointsWindow` | Toggle hit area; detail-panel Navigate and Remove | Open source; Enable/disable; Remove | 3 |
| Inspector value/watch, `DebuggerInspector` | Source/type navigation, Set Value, Copy Value/Expression, Add/Remove Watch | Edit watch; Copy type | 2 |
| Captured result, `ScriptResultTree` | Expand and speed search; no custom menu | Copy value; Copy type; Expand branch; Collapse branch | 4 |
| Paused evaluation result, `DebuggerResultPanel` | Expand; no custom menu | Copy value; Copy type; Expand branch; Collapse branch | 4 |
| Output/problem text in script and evaluator | Text selection; no app-defined popup | Copy; Select all | 2 |
| Image canvas, `ImageViewPanel` | Zoom/Fit/100% toolbar and Ctrl-wheel | Copy image; Save image as; Fit image; Actual size | 4 |
| Breadcrumb, `BreadcrumbBar` | Click to navigate where a target exists | Copy path/reference; Reveal in tree | 2 |
| Project entry, `ProjectSelector` | Rename; conditional reset name/remove recent | Copy project path; Show in Explorer | 2 |
| **Total** | | | **62** |

The table groups shared output text as one context, and groups runtime object types as one context. Expand and Collapse count separately. Enable/disable counts once because the label changes with state. Copy path/reference counts once because the target determines its label and payload. Rename, Duplicate, save-image export, and watch editing require additional UI/lifecycle work; do not estimate them as menu wiring alone.

Applicability matters more than menu length. A runtime class can offer Find usages; a texture cannot. Search in module belongs on objects with a known module. A package opens/reveals a tree location. A script tab can reveal a file, while a usages tab may only offer a query title for copying. Hide commands that do not apply to the target type. Disable temporarily unavailable commands with an understandable reason. Allow copying captured values offline, label truncated data, and never evaluate code just to populate a menu.

The first implementation batch should cover **27 placements**: tabs 5, search results 3, usages 5, frames 3, breakpoint rows 3, snapshot results 4, and paused results 4. These close common dead ends while keeping the menu system small. File rename/duplicate/delete lifecycle work should be its own focused batch.

### Menu organization and behavior

Use consistent ordering: open/navigation first, object-specific work next, copy/export next, and destructive or removal actions last. Avoid empty separators and deep submenus. A routine row should usually show about four to seven relevant entries; this is a design target, not a requirement to pad sparse menus. Add Copy submenus only when a real target has several useful representations.

Keep primary actions visible: Run belongs in the execution toolbar, search belongs in the main UI, and new-script creation should be available from the scripts header/folder. Right-click duplicates those actions near the object. It must not become the only route to an essential operation. Project Rename and Remove from recent projects need a discoverable management route as well as their nested context menu.

Required behavior for every added menu:

- Mouse invocation targets the clicked object. Clicking an already-selected item preserves a valid multi-selection. Clicking a different item selects it. Empty space never silently targets the nearest item.
- Shift+F10 and the context-menu key invoke the same actions at the selected row, tab, or editor caret. Escape returns focus to the invoking control.
- Copy preserves the intended text selection, returns a documented plain-text representation, and includes truncation markers where appropriate.
- State changes refresh enablement. Paused-object actions cannot retain an invalid frame/pause target after resume or project switching.
- Opening a menu does not run code, mutate data, steal editor selection, or trigger navigation.
- Context menus in search, hierarchy, or project popups must not dismiss their parent before an action can run. This needs live focus testing because those windows have different dismissal rules.
- Menu entries and toolbar/keyboard commands invoke the same action logic. Closing several tabs must respect each editor's existing close checks and report any tab that remains open.

Do not add right-click menus to passive hierarchy hover previews, signature tooltips, or every settings label. Completion lists need completion behavior. Module-filter bulk actions are already visible. Status details should provide selectable/copyable text and a useful recovery action through their normal click, rather than another hidden layer.

## Consistent organization and visual direction

Keep the main window centered on Files, editor tabs, and a compact status bar. Present user-facing tree roots as Scripts, Decompiled sources, and Runtime, while leaving storage paths unchanged. Show the selected project clearly. Offer a useful empty editor area with Open project, Search runtime, and New script, enabled according to the current project/index state.

Use the main editor and Search Everywhere as the visual reference. Retain the icon family and source coloring. Define a small set of shared spacing, row-density, toolbar, form and message patterns in the existing UI package. A large Prism instance picker can legitimately use taller rows; a debugger frame list should remain dense. Share the reasons and metrics for those choices instead of imposing one row height everywhere.

Separate four kinds of feedback: input validation beside the field; operation progress/cancel beside the operation; empty or failed content inside its view with recovery; and persistent connection/index state in the status bar. For example, Search currently tells the user to use Retry in the bottom bar, while ResourceView offers Retry in place. Offer recovery where the failure is visible. Keep long details copyable and available after a brief status message changes.

Align command vocabulary. Use Open source for source navigation, Copy reference for a qualified symbol, Copy path for a filesystem path, Remove watch/breakpoint for configuration entries, and Delete file for a filesystem operation. Standardize sentence case and ellipses for commands that require more input. Replace success exclamations and ambiguous Run Result labels with calm, specific state text. Settings should explicitly identify global versus current-project scope.

Standardize execution context presentation before rearranging the debugger. Client, Server and the selected paused frame must be obvious beside the input. Saved-script scheduling can remain a separate control because it expresses a different choice. Keep Stop/Cancel terminology aligned with the actual cancellation state, including when a request is pending.

The largest optional layout change is a docked debugger below or beside the editor, with a detach option. It could reduce source/debugger window switching, but it brings window placement, focus and persisted layout work. Prototype it after the shared interaction pass; it is not a prerequisite for consistent menus, result trees, or forms.

## Delivery order and acceptance

| Pass | Scope | Completion evidence |
| --- | --- | --- |
| 1. Interaction foundation | Usages speed-search repair and switching reproduction; shared action enablement/presentation, popup invocation, selection rules, keyboard focus, icon toggles, secondary-text role | Usages highlights matching fragments and cycles predictably in grouped results; keyboard and mouse operate the same commands; selected text survives right-click; selected targets remain correct; both themes retain readable focus and metadata |
| 2. Common object actions | The 27-placement batch above; shared value and output copying; clear visible entry points for search and navigation | A user can find a class, inspect usages, open a frame, copy its stack, copy a result, and manage tabs without hidden dead ends |
| 3. Script and breakpoint workflows | File lifecycle, New Script, save feedback, explicit execution context, result chrome, breakpoint form/validation/script selection | Create/rename/duplicate/delete handles open or running scripts and failures; invalid fields remain editable with an explanation; the same result has the same applicable copy actions |
| 4. Secondary screens and layout | Resources, status details, empty states, long-content behavior, spacing/density, project maintenance, settings scope | Narrow windows and larger fonts retain the primary action and useful identity; status detail can be copied; unsupported/offline states offer accurate next steps |
| 5. Optional debugger layout | Prototype docking/detaching and compare common debugging journeys | Source and selected-frame context remain clear; keyboard focus and saved layout behave predictably across pause/resume and reopen |

Keep action ownership close to the existing feature: navigation in NavigationService/targets, debugger state in its controller/actions, script execution in its service, project maintenance in ProjectControls, and editor closing in EditorTabs. Share menu construction and presentation where behavior repeats. Do not introduce a second implementation of those operations or merge evaluator and compiled Code architectures as part of UI cleanup.

The present scenario list is weighted toward the areas that already have more visual care. Add coverage for tab/file/value menus, keyboard menu invocation, New Script validation, saved-script execution/output, successful/failed/truncated results, image/text resource viewing, editor Find, completion/signature help, empty project, offline/index failure, breakpoint validation, and long project names. The Prism capture must use deterministic fixture data; a local instance listing and overlapping project dropdown are not a stable visual baseline.

For each changed flow, exercise the relevant states: empty/loading/ready/failed, selected/focused/hovered/disabled, and running/paused/expired where applicable. Test both themes, a narrow window, long labels, the supported font range, and Windows scaling at 100%, 150% and 200%. Verify runtime theme switching too. The current captures alone do not cover that matrix.

Use the owning `:companion:test` task for behavior checks, especially selection targeting, shared actions, focus, close checks and stale debugger results. Use existing rendering utilities for visual checks. If protocol, storage or execution contracts change, also check their affected consumers as required by the repository instructions. This audit itself only adds documentation; it does not require packaging, deployment or the root check task.

## Representative evidence

- [Main editor, dark](../companion/build/ui-screenshots/islands-dark/main.png): reference for overall density, code styling and restrained workspace chrome.
- [Search results, dark](../companion/build/ui-screenshots/islands-dark/search-results.png): strong composition; secondary text and footer need more contrast.
- [Find Usages, dark](../companion/build/ui-screenshots/islands-dark/usages-results.png): extra framing and long signatures demonstrate the main layout issues.
- [Breakpoint manager, dark](../companion/build/ui-screenshots/islands-dark/breakpoints.png): conditional action fields and use of space need refinement.
- [Debugger, light](../companion/build/ui-screenshots/islands-light/debugger.png): dense frame/value layout to preserve while improving actions and focus.
- [Settings, light](../companion/build/ui-screenshots/islands-light/settings.png): compact form baseline, subject to scope labels and larger-font checks.

Useful implementation references beyond those linked in the findings: [EditorTabs](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/components/global/EditorTabs.java), [SearchEverywherePopup](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/views/SearchEverywherePopup.java), [UsagesViewPanel](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/components/editors/UsagesViewPanel.java), [DebuggerFramesPane](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/views/debugger/DebuggerFramesPane.java), [ImageViewPanel](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/components/editors/ImageViewPanel.java), [ProjectSelector](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/components/global/ProjectSelector.java), [PrismInstancePicker](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/views/PrismInstancePicker.java), [ApplicationStatusBar](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/components/global/ApplicationStatusBar.java), [SettingsWindow](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/views/SettingsWindow.java), and [Minecraft startup messages](../mod/src/main/resources/assets/total_debug/lang/en_us.json).
