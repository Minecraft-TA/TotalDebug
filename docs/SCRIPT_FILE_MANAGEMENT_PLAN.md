# Script files and folders

Status: implemented for ordinary scripts and folders. The programmable-items format and module semantics remain separate work.

## Scope

Make ordinary nested folders work properly before adding semantic module, item or block folders. The first slice includes creating scripts in a chosen folder, creating folders, renaming/moving files and folders, duplicating a script, and deleting selected files/folders. Keep these operations inside the project's Scripts root. Runtime and decompiled sources remain read-only.

Folder presentation must be able to gain a type badge later without changing the underlying file operations. This slice does not define module dependencies, compilation source groups, a creation entry-file convention, or a new document format.

## Starting implementation

- [ScriptView](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/model/ScriptView.java) constructs a root-level path from a Java identifier and combines opening with file creation. The path is immutable. Breadcrumbs contain only the filename.
- [NavigationService](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/navigation/NavigationService.java) recognizes only direct children of Scripts as editable scripts and matches their tabs by title. Nested `.tdscript` files currently open as resources.
- [ScriptPanel](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/components/editors/ScriptPanel.java) saves on delayed focus loss, Ctrl+S and close. Its write primitive recreates missing files and parents. Removing the panel also stops its execution. Closing/reopening is therefore unsuitable as a rename implementation.
- [AbstractCodeViewPanel](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/components/editors/AbstractCodeViewPanel.java) captures an analysis/cache identity at construction. Script analysis and completion also capture the generated class name. Updating a single path field would leave inconsistent identities.
- [FileSystemFileItem](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/components/treeView/lazyFileTree/FileSystemFileItem.java) deletes synchronously and ignores I/O errors. Folder deletion inherits a no-op from TreeItem. The generic tree currently owns the Delete key and invokes those methods.
- The lazy tree already supports nested directories and preserves surviving expansion/selection. Only the Scripts root is watched; loaded child directories stay cached. Root refresh is intentionally insufficient to invalidate a changed nested folder.
- [AtomicFiles](../storage/src/main/java/com/github/minecraft_ta/totaldebug/storage/AtomicFiles.java) provides non-overwriting creation, atomic replacement and bounded recursive deletion. Reuse those primitives. There is no existing script repository or script CRUD MCP API to consolidate.
- Saved breakpoint actions already accept relative nested script paths through [ProjectScope](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/project/ProjectScope.java). Moving files must account for those references, including entries stored for other runtime signatures in [InstanceState](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/storage/InstanceState.java).

## Smallest useful architecture

Add one project-scoped owner for script file operations. It resolves paths, validates operations, performs filesystem work and reports concrete outcomes. Rename is a move within the same parent. Duplicate stays an explicit operation, so a future document format can give it different identity semantics.

Keep editor coordination in Companion, and general filesystem primitives in storage. The UI operation path prepares affected editors on the EDT, performs disk work off the EDT, then updates editors, known references and affected tree directories on the EDT. Future MCP commands must enter this coordinated path rather than bypassing open-editor protection through a raw file service. No generic command bus, virtual filesystem, document-provider registry or project-wide content database is needed now.

Separate opening an existing script from creating one. Represent each script's location with a normalized path under Scripts. Match tabs by path, permit duplicate basenames in different folders, show the relative parent in tooltips/breadcrumbs, and keep Java-identifier validation for the script basename. Folder names use ordinary filesystem-name rules rather than Java-name rules.

Editor relocation updates location, title, breadcrumbs and navigation targets while retaining the text document, undo, caret and output. Analysis has an editor-lifetime key and generated class name independent of the file path; local source links read the current location. The hidden generated name is an implementation detail, not a persistent script identifier or a promise to match the current filename. A move therefore does not rebuild analysis or the editor. Folder moves apply a prefix mapping to open descendants. Read-only previews are replaced at their existing tab positions.

Pending saves participate in the operation. Capture/flush affected drafts before moving and pause editing of affected scripts while the operation runs. Save completion includes publication on the EDT, so worker completion alone cannot mark an editor clean. Failures leave drafts recoverable; a failed close offers an explicit discard choice, and Duplicate can preserve a conflicted draft without overwriting the original. A missing or externally replaced backing file must not be silently recreated/overwritten by an old editor. This is a file-lifecycle fix, not a change from autosaving to a Save-button workflow.

Duplicate uses the current open draft where one exists, not a stale disk snapshot. Do not overwrite the destination. Reject rename, move and deletion of active script sources with an actionable Stop instruction, including runs whose editor has closed but whose cancellation is not yet confirmed. A run retains its captured source identity; file operations must not silently retarget it or stop it implicitly. Prepare every affected editor before changing any file.

Use the recycle bin when supported. Permanent deletion requires an explicit action identifying the target set; do not silently fall back after a recycle-bin failure. Suppress saves for successfully deleted targets and close their editors without invoking another save. If a recursive operation partly fails, report the actual failed paths and refresh the real remaining contents. Do not claim the operation was transactional.

Remap known saved breakpoint references, open breakpoint forms and in-memory navigation history after a successful move. Remapping an action preserves registered debugger sources and unrelated live breakpoints, and rebases a form's script selection without discarding other draft fields. For deletion, report affected breakpoint references instead of leaving silent broken actions. Arbitrary Java strings and external paths are outside automatic reference rewriting.

## Tree interaction and refresh

- Offer New Script and New Folder on writable folder menus only. Main-menu creation uses the selected folder, the parent of a selected file, or Scripts when nothing relevant is selected.
- File menus omit Open source because double-click and Enter already open the file. Copy path follows file operations, with Delete last. Confirmation names the selected file or folder, mentions contents only for folders, and defaults to Delete; Cancel and Escape dismiss it. Tree context menus accept the full row width.
- Use the compact name-popup interaction already established: Enter accepts, Escape or focus loss dismisses, and name/collision errors stay beside the field. Add proper action icons. Keep small menus flat.
- Provide Rename, Move to, Duplicate for scripts, and Delete through the same command path as their keyboard actions. Drag-and-drop is the primary move interaction; Move to is also available for keyboard use. Recursive folder duplication remains deferred.
- Refresh the old and new parent directories after a move, and the actual parent after creation/deletion. Rebase affected expansion and selection paths; keep unrelated folders and editor focus intact.
- Extend watching to materialized directories using shared WatchService registrations. Collapsed but cached directories still need invalidation; unloaded directories can read their contents on first expansion. Handle overflow by invalidating affected cached contents. Avoid a thread per folder, an eager recursive catalog, or full-tree refresh on every save.

## Relationship to programmable items

The referenced task is `TotalDebug - Programmable items and…`, task ID `01a07d72-5996-7873-92bb-1f63cd7be9f7`. Its separate worktree contains `CREATION_VISION.md` and section 42 of `CREATION_DESIGN_NOTES.md`. Those are future design context, not features present in this branch.

That prototype's BehaviorDocument stores a stable definition UUID, kind, entry class and source inside `.tdscript`. F6 finds the document by identity and rejects duplicate identities. Its design keeps normal folders: a directory can group one creation's code/assets or several independent creations.

Preserve this distinction. Moving a future creation or its folder preserves document identity. Deliberate duplication must mint new contained definition identities and handle supported internal references when that format is integrated. Do not byte-copy those definitions and claim they are independent creations. Do not add persistent IDs to ordinary snippets or folders now.

An item/block/module badge should come from a known entry or later explicit metadata, not a guessed first file or a magic directory name. The entry-file rule and module compilation/dependency behavior remain part of the script rewrite. Deleting an authoring file is also distinct from uninstalling an already installed world definition.

## Delivery and verification

1. Make nested script opening, creation, saving and tab identity correct. Add New Folder and destination-aware New Script on that foundation.
2. Introduce coordinated rename/move and duplicate, including editor rebinding, pending saves, known references and targeted refresh. Do not ship a root-only rename that immediately needs replacement for folders.
3. Replace tree-owned deletion with the coordinated file/folder operation, recovery behavior and failure reporting. Finish nested external-change observation.

Tests must cover duplicate basenames, nested script creation/open/save, invalid names and destination collisions, case-only Windows rename, root/traversal/junction boundaries, moving into a descendant, open dirty descendants, pending saves, replacement at an old path, duplicate-from-draft, rejected running-script rename/move/deletion, partial failures, breakpoint references across runtime signatures and open forms, preservation of live debugger sources, stable open-editor analysis identities, history navigation, folder expansion/selection and external nested changes. Retain the existing large-tree and no-unnecessary-rescan tests.

Use Companion tests for these flows and storage tests if shared primitives change. Documentation changes require link/diff checks. Do not change protocol or execution semantics as a shortcut for file management.

Validation covers the real action path, local drag payloads and drop configuration, nested editors, pending save/undo/close interleavings, duplicate recovery, preserved preview locations, running state, breakpoint references, Windows case-only rename, internal junction protection, nested watcher notifications and disposal after failed loads. Offscreen captures of name entry and both menu types are in `companion/build/folder-ui`. The native mouse drag gesture still needs interactive verification.
