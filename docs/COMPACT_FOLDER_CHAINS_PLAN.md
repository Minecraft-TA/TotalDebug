# Compact folder and package chains

Status: implemented, 21 September 2026. Final validation recorded below.

The user confirmed that this should apply to both script folders and runtime/package browsing. The reference is IntelliJ's compact package presentation. [JetBrains documents Compact Middle Packages](https://www.jetbrains.com/help/idea/project-tool-window.html) as a project-tree presentation option for packages containing only other packages.

## Intended behavior

Fold an uninterrupted chain of directories into one tree row. Continue only when a directory contains exactly one entry and that entry is another eligible directory. A file or a branch stops the chain.

Examples:

```text
scripts                     Runtime
  modules/client/items        Example mod
    Example.tdscript            com.github.minecraft_ta.totaldebug
                                  client
                                  config
```

- Script/resource folders use `/`; Java packages use `.`. Keep the existing folder/package icons.
- Keep Scripts, Runtime/Mods, Libraries, mod nodes, archive/source roots and JDK module roots separate. Group within a source, never across these boundaries.
- A genuinely empty final folder remains a folder without an expand arrow.
- Count all entries when deciding eligibility, including hidden files. Do not hide files as a side effect of compacting.
- Enable compaction by default. No additional toolbar control is needed for the initial slice if intermediate folders remain reachable through their segments.
- Grouping changes presentation, not the stored paths or directory structure.

## Interaction contract

Editable script-folder segments identify their real directories. ZIP/runtime package chains behave as ordinary rows and target their final directory.

- In script folders, clicking a segment selects that folder within the row. Use a subtle background for selected/hovered segments, a normal pointer and no link underline. Archive/runtime labels have no segment interaction.
- Selecting a row by vertical keyboard navigation, or clicking its unused horizontal area, defaults to the final folder. The expansion control expands/collapses the row's final directory.
- Rename, Delete, Copy path, New Script and New Folder use the selected segment. Keep the existing operation implementations and confirmations.
- Dragging starts from the selected segment. Dropping on a segment targets that directory; dropping on the row's unused area targets the final directory. Use the same resolved destination for acceptance, highlighting and execution. The exact script destination gets a background and outline during drag/drop.
- Preserve ordinary tree keyboard behavior at the row boundaries. Within an editable compact row, collapsed Left can select an earlier segment; Right can advance to the final segment before expanding it. Read-only package rows retain ordinary Left/Right and double-click expansion. Expanded rows collapse first on Left. Keyboard context menus and F2/Delete use the active segment.
- Multi-selection resolves real paths and keeps the existing ancestor/descendant deduplication. Compact labels never become operation paths.

The renderer and hit testing must share measured segment bounds, including the icon, insets and font metrics. Do not recreate the divider mistake by testing only appearance: prove that every visible segment invokes the intended operation.

## Current implementation constraints

The existing tree has useful foundations: asynchronous directory loading, revision checks that reject stale results, incremental child reconciliation, directory watchers and path-based restoration after file operations.

The assumptions that need changing are localized:

| Area | Current assumption | Required change |
| --- | --- | --- |
| `LazyFileJTree.findItemPath` | One requested path segment per row | Consume all represented segments; reveal can stop on an intermediate segment |
| `LazyFileJTree.ItemKey` | Concrete item class and display node name identify a row | Compacted and uncompressed forms need the same chain-head identity |
| `refreshDirectory` | The watched directory has its own visible filesystem row | Find the chain containing that directory and recompute grouping from its visible parent |
| `ScriptFileActions.path` and `FileTreeView` menus | A row contains one concrete filesystem item | Resolve the selected segment or drop point through one shared tree target resolver |
| Selection/expansion restoration | Existing `TreePath` objects survive updates | Restore by real directory identity when grouping changes visible paths |
| Renderer | One primary label plus optional secondary text | Render directory segments and expose their measured hit regions |

`ZipFileRootItem` already has an in-memory hierarchy, making package-chain discovery inexpensive. Its loader currently infers file versus directory from the presence of children. Preserve explicit directory identity as a small prerequisite so an empty archive directory cannot be mistaken for a file.

## Contained design

Use one shared chain-compaction step in the existing background child-loading flow. Keep one Swing tree model; a second canonical/display tree pair is unnecessary for this slice.

1. **Providers identify eligible directories.** Filesystem, runtime-directory and archive-directory providers expose their grouping kind and a bounded single-directory-child probe. Container roots opt out. The package/resource distinction stays with these providers, using the existing archive resource-root rules.
2. **A compact row retains its real segments.** Store the underlying directory descriptors from first to last. Display uses the joined labels; loading children uses the last directory. Identity and sorting use the first real directory, so adding or removing compaction does not arbitrarily replace the row.
3. **The tree resolves targets once.** Pointer position and keyboard segment selection produce a real item/path. Menus, clipboard, file actions, drag-and-drop and reveal use that result instead of adding separate wrapper checks throughout the UI.
4. **Refresh compares the represented endpoint as well as the row identity.** If a chain gains a branch or extends further, its children now belong to a different endpoint. Reload those children and restore selection/expansion by real paths. Do not reuse children from the previous endpoint merely because the first name stayed the same.

Filesystem lookahead should enumerate only enough entries to distinguish zero, one or multiple children, rather than building every collapsed subtree. Archive lookahead uses its existing indexed nodes. Reuse the current worker/EDT handoff and stale-result checks.

Retain watchers for every represented filesystem directory, not only the final directory: creating a sibling in the middle must split the chain. Give each descriptor one clear owner; replacing/discarding a chain disposes its watchers exactly once. An unreadable directory remains an ordinary lazy directory whose existing open path reports the error. Stop compaction at symlink/junction boundaries and guard repeated directory identities so lookahead cannot follow a cycle.

## Implementation order

1. Add the chain representation, eligibility/probe logic and identity rules. Cover real filesystem and archive fixtures before changing rendering.
2. Integrate compaction with loading, incremental refresh, endpoint changes and real-path reveal/restoration. Exercise changes at every position in a chain.
3. Add segmented rendering and target resolution, then wire menus, keyboard actions and drag-and-drop. Remove the superseded one-row/one-path assumptions in those consumers.
4. Inspect the actual tree at normal/narrow widths in both themes and display scales. Audit operation targets and asynchronous lifecycle behavior before committing.

## Regression cases

| Cases | Required result |
| --- | --- |
| Single folder; long chain; empty terminal folder | Correct grouping and expand affordance |
| File or second directory at the start, middle or end | Stop/split exactly at that point; retain all entries |
| Script folders, package chains, resource paths, archive and JDK roots | Correct separators, icons and structural boundaries |
| Spaces, Unicode, dots in ordinary folder names, narrow widths, scaling | Labels and target hit regions agree; full paths remain available |
| New script/folder, external sibling creation, sibling deletion | Regroup without blanking the whole tree; reveal the new item |
| Rename/move/delete first, middle or final segment | Operate on that real folder; preserve editor relocation and existing safeguards |
| Drop on a segment versus row whitespace; self/descendant/cross-project drops | Highlight and execution share the exact same validated target |
| Selected/expanded path survives a chain splitting or joining | Restore the same real target; do not select a neighboring folder |
| Delayed load during rename, deletion or project switch | Reject stale results and dispose abandoned watcher ownership |
| Empty archive directory, unreadable directory, symlink/junction cycle | Correct item type; bounded discovery; existing errors remain reachable |
| Repeated refreshes and disposal | No growing watcher count or callbacks into a disposed tree |

Use the owning Companion tests and small temporary directory/archive fixtures. Keep routine Swing fixtures offscreen; actual visual/interaction previews are a separate deliberate check. Existing filesystem mutation tests remain the authority for mutation semantics.

## Assessment

Moderate complexity: the label grouping is small, but live refresh and exact action targets make this more than a renderer tweak. It is still a contained tree feature. It does not require changes to script storage, execution, the protocol, JIndex, or a general virtual-filesystem abstraction.

## Implementation and validation

Implemented with one `DirectoryChain` descriptor and one segmented renderer in the existing lazy tree. Filesystem, runtime directory and archive providers retain their real paths; file operations resolve those descriptors. No storage, execution, protocol or indexing changes were required.

The rename/move integration test exposed a Windows constraint: watching a descendant directory prevents moving its parent. `FileUtils.withPausedDirectoryWatchers` releases affected handles around the filesystem mutation, retains subscriptions and restores live directories afterward. Physical path identity shares registrations across junction/symlink aliases. Failed operations also resume watching and refresh affected rows.

Regression coverage includes:

- Splitting and joining at each chain position, preserving selected files and intermediate folders.
- Never-expanded rows, empty terminal folders, hidden files and named-container navigation.
- Editable-folder pointer/keyboard targets, Windows popup-trigger release and Ctrl-click with sub-threshold mouse movement. Read-only package rows use the ordinary renderer, pointer, row target, Left/Right navigation and double-click expansion in both themes.
- Watch events before discovery publication, repeated refresh/disposal, failed descendant loading and delayed restoration.
- Real folder rename/move while an affected script remains open, including editor relocation and Copy path targets.
- Archive package/resource boundaries, explicit empty directories, duplicate implicit/explicit entries and JDK module roots.
- Watcher failure recovery, nested pauses, new subscriptions during a pause, junction aliases, deleted/recreated directories and unrelated-directory delivery.

Rendered the actual `FileTreeView` in an isolated fixture in both Islands themes, at 100% and 150% display scaling and 520/290-pixel widths. Inspected segment background highlights, ordinary read-only package rows, folder/package icons, empty folders, indentation and horizontal overflow. Preview images are under the ignored `companion/build/folder-preview` directory.

Independent standards and behavior reviews found no remaining actionable issues after the fixes. The final diff adds 666 production lines and removes 87 (579 net).

Validation completed with Java 21 and the checked-in wrapper:

- Full `:companion:test`: 1,045 tests passed, zero failures/errors/skips.
- Final focused run including the interaction refinement: 34 tests passed, zero failures/errors/skips.
- `git diff --check`: passed.
