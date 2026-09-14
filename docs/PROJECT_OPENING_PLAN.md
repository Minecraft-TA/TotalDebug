# Project opening and offline indexing

Status: implemented on `codex/offline-project-indexing`; final validation and review are recorded with the change. The sections below retain the agreed scope. A real game launch remains a separate verification action; automated tests exercise runtime announcements and source handover.

Opening an instance must expose useful content without starting Minecraft. Restore existing TotalDebug data first. Otherwise browse the installed mod JARs and build a local index automatically. When Minecraft publishes its runtime, replace the local index and retain the runtime snapshot for subsequent offline use.

## Opening rules

Use one read-only resolver for Open, Prism selection and MCP `project_open`. Preserve explicit game/data-directory profiles supplied by Minecraft; do not rediscover or relocate their storage.

Normalize these selections to one game-directory identity:

| Selected location | Resolution |
| --- | --- |
| Game or development `run` directory | Inspect its `total-debug` data, then its `mods` directory |
| Prism instance directory | Resolve its `minecraft` or `.minecraft` child, then apply the same rules |
| An instance's `total-debug` directory | Use that data directory and its parent game directory |
| An instance's `mods` directory | Use the parent game directory; never create `mods/total-debug` |
| Unrelated directory | Reject without changing the current project or creating files |

Recognition requires scripts, state, a runtime inventory, or an existing `mods` directory. A directory name or generated index alone is insufficient. Resolve an existing Prism game child before interpreting `mods` or `total-debug` as a child-directory shortcut. Do not recursively search ancestors, descendants or other drives. This slice supports instance directories, including selecting their `mods` child; standalone loose-JAR workspaces need a separate storage/identity decision and are outside it.

Resolution returns the normalized profile and discovered data, without writes. Perform it before preparing or retiring the current project. Register a project only after it has opened successfully. An indexing failure after opening a recognized instance leaves its file browser usable and reports the index failure.

Selection priority:

1. Restore authored project state and scripts when present. Validate the saved runtime inventory and its referenced sources. Load its matching index, or rebuild from that inventory if the index is absent or obsolete.
2. If no usable runtime inventory exists, discover `mods/`, reuse a matching local index or build one. A corrupt runtime snapshot must produce a visible diagnostic when falling back; preserve its files for diagnosis.
3. A recognized project with only scripts/state remains browsable, with an explicit absence of code sources. An empty `mods` directory shows an empty file list. Neither should claim to be waiting for Minecraft indefinitely.

A valid saved runtime remains the preferred code view even if the installed mod files have since changed. It represents the last capture. Name its source as runtime data and keep game connectivity separate; do not claim it represents the current on-disk pack. Opening must not synthesize live registries, loaded-mod state or execution readiness.

## Storage and index identity

Keep the existing instance layout and one `cache/runtime/index.jindex`. Do not introduce index generations, another project registry or a second permanent local index.

- Minecraft continues to own `cache/runtime/inventory.json` and its prepared runtime sources. Companion must not write a local scan into that inventory or delete those sources.
- Extend Companion's index manifest and ready-snapshot metadata with an explicit source kind, `LOCAL` or `RUNTIME`, and a source identity. Runtime snapshots retain the real inventory ID. Local snapshots have no runtime inventory ID.
- For local indexes, the embedded manifest records the ordered archive paths, source/module mappings, archive content fingerprints, index format and effective Java runtime identity. This is enough to validate and reopen the index without a separate local inventory file.
- For runtime indexes, retain the existing inventory identity validation and source mappings. Bump the generated index cache format when necessary and rebuild old caches from available inputs. Do not migrate authored files or change the shared runtime inventory format.
- Reuse the existing staged write, native-index validation, runtime cache lock and atomic replacement. Check request ownership immediately before publication. A superseded local build must not overwrite a runtime index.
- Source signatures distinguish local and runtime data even when class names match. Decompiled output, usages and navigation must not reuse results from a different signature.

Local inputs are the installed JAR files, not copies in Minecraft's prepared-source directory. Hash them off the EDT for cache validation; check stability across scanning/building and validate source changes before serving class bytes. If a JAR changes, invalidate its index-dependent view and rescan rather than combining an old index with new bytes. Opening or explicitly refreshing rescans the archive set. Do not add continuous full-directory reindexing in this slice.

Opening an arbitrary folder creates nothing. Opening a recognized instance may create its cache when indexing actually starts. Create `scripts/` when saving the first script, and `state.json` only for an actual persisted change. Audit state restoration so setter calls that merely reapply defaults do not create state files. A missing scripts directory must not break tree construction or watchers. Read-only instances remain file-browsable; report an unwritable cache without turning it into an opening failure.

## Useful browsing before indexing

Give `ProjectScope` an owned source catalog that can exist before its index binding. Discover archives on a worker, publish the file list on the EDT, then index in the background. The file tree must not depend on `scope.runtime()` merely to show archive contents.

Reuse the lazy archive tree, resource readers and editor navigation. Expose JAR contents, textures, JSON and mod metadata immediately. Treat external mod archives as read-only; do not inherit the scripts tree's Delete action. Preserve archive-qualified resource navigation so opening a resource does not require a runtime binding.

The initial local index covers enabled top-level `.jar` files in `mods/`, in deterministic path order, plus Companion's Java runtime classes. Exclude disabled files. Use archive filenames as honest source labels initially; loader-specific names can be read later without blocking inspection. A malformed archive stays visible with its error, while valid archives remain browsable. Report skipped index inputs and incomplete search coverage.

This is static inspection, not loader emulation. Do not recursively index arbitrary embedded JARs or execute mod code to discover sources. Nested dependencies, Minecraft/platform classes absent from the supplied files and loader transformations are not reconstructed in this slice. Decompilation and references may be incomplete where those dependencies are missing. Preserve deterministic source precedence and identify duplicate classes; do not claim it matches Minecraft's class-loading order. Match index and bytecode reads for multi-release JARs using the selected Java version.

## Local-to-runtime handover

Use the existing worker, cancellation checks and binding ownership. Extend the one indexing pipeline with local inputs; do not create a second index manager.

1. Local source discovery publishes the file browser and starts loading/building its index.
2. A runtime-available announcement for the selected project supersedes pending local work. A connection alone is insufficient; wait for a valid inventory.
3. Build/load and validate the runtime replacement before retiring the local binding. While preparation runs, retain useful local browsing. If preparation fails, retain the local view and report the runtime error.
4. Install the replacement under the existing lifecycle lock. Rebind code insight, decompilation and reference search once; retire old source-dependent views and cancel their pending work. Preserve authored script buffers through the current editor lifecycle.
5. Enable runtime-dependent compilation/execution only after the runtime binding is installed and the existing connection/session checks pass. A local index must never enter server-manifest comparison or be sent as a runtime inventory identity.
6. Disconnecting keeps the captured runtime index. Reopening offline prefers that snapshot. Matching live announcements reuse it; they do not rebuild unnecessarily.

`RuntimeBinding.attach()` currently always binds the script compiler, and the compiler reads the game-owned inventory. Make that boundary explicit: local bindings supply browsing and code insight; only runtime bindings attach runtime-dependent compiler/server services. Keep evaluator/compiler architecture unchanged. Do not add offline script execution or reinterpret a local index as an execution target.

Preparation failures and cancellation must retain clear ownership. Do not revive closed bindings as a rollback mechanism. Keep fallible preparation ahead of the detach/install boundary, close discarded native indexes, and test that late local completion cannot replace a runtime or a different project.

## Project menu and MCP

Use the existing persistent registry as `Projects`, ordered by recency. Keep the selected project distinct and rename the removal action to match the catalog. Prism remains discovery of additional instances, not another registry. No new project-management window is needed.

Expose selected source kind and index phase in the existing status response and status presentation. Keep those separate from game connectivity. `project_open` uses the same resolver and returns an actionable error for unsupported directories. Resource browsing works before the index is ready; code-query tools report indexing/unavailable state until it is ready. Execution tools retain their current admission rules plus the explicit runtime-source requirement. Audit descriptions that currently imply all source data came from a running game.

Keep UI changes to these behaviors: project naming, immediate mod browsing, source/index status and errors. Preserve the current picker layout. Inspect screenshots for each visible iteration in both themes; add no subtitles or duplicate headings.

## Implementation sequence

Each step ends with its listed behavior verified before continuing.

1. **Resolve before opening.** Add the shared path resolver, remove writes from profile validation, defer scripts/default-state creation, and rename the project list. Cover all selection forms, stable identity and rejection without side effects. Existing runtime reopen must still work.
2. **Browse unindexed instances.** Add scope-owned local source discovery and expose archive resources independently of index readiness. Verify an unindexed instance can display a texture and JSON without Minecraft; corrupt/empty/read-only cases remain usable.
3. **Build and restore a local index.** Extend snapshot/cache identity and the existing index pipeline. Enable local class search, decompilation and usages within available sources. Verify cache reuse and invalidation when JARs are added, removed or replaced. Local bindings must leave runtime execution unavailable.
4. **Replace with the captured runtime.** Integrate runtime priority, cancellation, binding transitions and persisted-cache replacement. Verify offline reopen after the transition uses the runtime snapshot, and late/failing work cannot corrupt the selected project's view.
5. **Review and document.** Update storage, usage and MCP contracts to describe the implemented behavior. Review the complete diff against the opening rules and ownership boundaries, run the checks below, then discuss the result before commit/PR.

Primary code locations:

- Opening and ownership: [CompanionApplication](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/CompanionApplication.java), [ProjectScope](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/project/ProjectScope.java), [ProjectRegistry](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/session/ProjectRegistry.java).
- Source preparation and cache: [RuntimeIndexService](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/runtime/RuntimeIndexService.java), [IndexCache](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/runtime/IndexCache.java), [RuntimeSnapshotBytecodeSource](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/bytecode/RuntimeSnapshotBytecodeSource.java).
- Binding and execution boundary: [RuntimeBinding](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/runtime/RuntimeBinding.java), [ScriptCompilationService](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/script/ScriptCompilationService.java).
- File browsing: [FileTreeView](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/components/treeView/FileTreeView.java), [NavigationService](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/navigation/NavigationService.java).
- Storage ownership reference: [RuntimeInventoryPublisher](../mod/src/main/java/com/github/minecraft_ta/totaldebug/client/companion/RuntimeInventoryPublisher.java), [storage contract](STORAGE.md).

Expected scope is Companion plus documentation and tests. Keep shared inventory/wire formats and the mod publisher unchanged. If implementation reveals that a shared contract must change, review that decision before broadening the work.

## Validation

Use temporary instance fixtures and small synthetic JARs for automated tests. Never use the real pack as an automated mutation fixture.

- Resolver and persistence: unsupported folders stay byte-for-byte untouched; selecting instance, Prism root, `mods` or `total-debug` yields the same identity; missing scripts/default state are not created by opening/closing; failed resolution preserves the current project.
- Browsing: archive resources work without an index; missing dependencies, disabled/corrupt/empty archives and unwritable cache have explicit behavior. External files have no Delete action.
- Index: cached local reopen, content changes despite unchanged filenames, added/removed JARs, changed Java identity, multi-release consistency, duplicate classes and incomplete-source reporting.
- Handover: pause local native work, publish a runtime, release local work and assert only the runtime installs; cover project switch/close during both phases, failed runtime preparation, matching runtime reuse, and offline reopen of the resulting cache. Assert discarded native indexes are closed.
- Admission: local browsing and code queries succeed as appropriate, while compilation/execution/server comparison never treat local source identity as a runtime ID. Preserve existing client/server checks after runtime installation.
- UI: deterministic fixtures for indexed and unindexed projects, loading/error/empty states and both themes. Confirm actual screenshots, keyboard opening and selection retention.

Run the owning Companion tests during each step, including the existing `RuntimeIndexServiceTest`, `IndexCacheTest`, `RuntimeBindingTest` and project-switch/navigation/MCP coverage. Finish with Companion's suite and executable packaging; run both consumers if shared code changes. Manually demonstrate an unindexed disposable instance, local code/resource browsing, connection to its matching game, then offline reopen. Launching or modifying a real game installation remains a separate action.
