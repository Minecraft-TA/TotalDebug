# Companion's systems

The few systems every feature of Companion uses to learn that something changed, read it, show it, write a change and receive what the game sends, and the rules that keep features on them. Written on 2026-09-30, after the reload work (#104 to #108) and an audit showed that most edge cases of the last weeks came from each feature solving these jobs again in its own way. Revised the same day after three reviews of the draft.

A feature adds only what is its own: how to read and write its values, and how to show them. Everything else is one of the systems below. A feature that needs something none of them offers changes the system, with a decision recorded here, instead of building beside it.

## Why

Companion shows state that others own: the game, and files on disk that the game, the player and Companion all write. For each piece of it, a feature has to answer the same questions: where the value comes from, how it learns of a change, on which thread it reads, what happens while its page is hidden, when two reads overlap, who else must hear of it, and which answer belongs to which request. Every feature answered them again:

| Job | Ways it is done today |
|---|---|
| Tell that something changed | 27 `add…Listener` methods; about a dozen classes keep their own list of listeners, which run on whichever thread saw the change; some pass details, as `GameLocation.Change` |
| Know which read is the newest | A counter per class for the same read repeated: `KeyAssignments.generation`, `PageLoader`'s `again`; owners also guard between requests of different kinds, as `ItemIconService.adoptions` and `PackCatalogService.generation`, which is their job |
| Bring a page up to date | `PageLoader` in three modes (`whenShown`, `waitsWhileHidden`, `readsWhenShown`, 11 pages); `ShownUpdates` (6 pages); subscriptions pages hold themselves (`PackResourceEditor`, `TextureEditor`, `FileTreeView`, `MainWindow`, `CatalogIcons`); reads in the constructor (5 pages, and 2 that build from memory); `refresh()` on every navigation (`NavigationService`); `CompanionUi.catalogChanged` and `changesRecorded`; `InspectionSession`'s timer |
| Share what one page read | `WorldReadings` holds what the World page read last, so the World page reads while hidden for the tree and its tab |
| Notice a file changed | `FileUtils` polls a watcher every second for created and deleted files; `ExternalEdits` and `KeyAssignments` each run their own watcher and scheduler with a 300 ms settle; only the first pauses for Windows folder renames, and they key paths differently |
| Work off the Swing thread | 22 classes create their own threads or executors; most of the 47 `supplyAsync` and `runAsync` calls use the JVM's shared pool, also for blocking file reads |
| Write | The change pipeline, on a queue that `ConfigChanges` owns and names "Configuration writes"; page reads that change the change record (`ChangeRecord.observed` from the Changes page's labels) |
| Receive a game message | `CompanionSession.Listener` with one method per message, relayed by `CompanionApplication`, which checks the scope is still current; in the mod, `CompanionAppClient` has a setter per message and repeats an authentication guard, which `RetryRuntimeInventoryMessage` lacks |

Each way was reasonable where it was added. Together they are the edge cases: a page read twice or three times when it opens, a hidden tab reading on every reload, a status bar flickering when tabs are renamed, a folder rename that one watcher pauses for and two do not, a check missing from one handler of seven. Review rounds made it worse: a finding about timing or staleness was fixed with one more flag, counter or retry where it showed, instead of in a system that owns the problem.

## The flow

State moves one way. A page learns of a change only through a signal, and reads only through its loader.

```text
game message ──────────────────────────────────┐
file on disk ─► reading (shown, user back) ─────┼─► owner compares ─► signal (if different) ─► page loader ─► read ─► show
pipeline write ─────────────────────────────────┘

page action ─► change pipeline ─► project's write queue ─► owner ─► signal
```

## 1. Owners and signals

Every piece of state Companion shows has one owner: a service of the project scope or of the application.

> Each state owner updates its state through one serialized path and publishes immutable snapshots. Background reads return results to that path; they never modify owner state directly. Consumers use published snapshots. External changes are rechecked before writes.

The serialized path is a `Strand`: the owner's tasks run one at a time and in order, on the shared threads of `Workers`, not on a thread of its own. It rules out races within Companion, such as a page's read and the owner's own read disagreeing about which one the next change is told against. It does not stop the game or an editor from changing a file meanwhile: a published snapshot is consistent but can become outdated, so conflict checks at the write, incomplete files and superseded results are still handled, once, in the owner. The owner holds the current value and compares every new one with it. It fires a `Signal` only when the value differs, so everything downstream can trust that a signal means a change. The comparison is the owner's, of its domain values (parsed assignments, the enabled packs, the catalog), which have a meaningful equality; nothing compares what a page shows, such as images or editor models.

```java
public final class Signal {
    /** Runs {@code listener} after each change, on the thread that fired it; returns what removes it. */
    public Runnable subscribe(Runnable listener);
    /** Tells every listener; never while holding the owner's lock. */
    public void fire();
}
```

`subscribe` has the shape of today's `addListener` methods, so owners move to it without touching their followers.

- **A signal carries nothing; the follower asks the owner.** Payloads invite followers to keep their own copy of the state, a second truth that goes stale. Where followers need to tell two changes apart, the owner has two signals, as `GamePacks` has one for the resource packs and one for the datapacks.
- **State, outcomes and events are different things.**
  - *State* is what an owner holds, and is followed through signals.
  - *The outcome of an action* goes to whoever started it, through the action's future: a write's result, a save's success. An outcome someone else caused becomes state: the last outcome of a file followed in an external editor is held by that follow.
  - *A failure that must not be missed*, such as the runtime index failing, is published by the owner to `NotificationCenter` when it happens, instead of followers watching for a brief state.
- **Connection-bound work belongs to one connection.** The connection `GameLocation` holds sends only on the connection it was established for: a send after that connection ended is refused, under the session's lock. The application tells the current project's location of a connection and of its end under `lifecycleLock`, one after the other, so the location's owners see the end before a new connection, and fail what waits for an answer there. A project that stops being current loses the game the same way. A split of the game connection out of `CompanionApplication` (step 6) keeps these two calls serialized ([GAME_MESSAGES.md](GAME_MESSAGES.md)).
- **The current project is state too.** The application owns it and signals a switch. UI that outlives a project (the tab strip, the Project tree, the status bar, search) follows "the current project's catalog" through its loader, which subscribes again on a switch. This deletes the `project.get() != scope` and `currentScope() == scope` checks.

**Newest wins, where it is the same read.** A page loader runs one read at a time: a request during a read makes one more read after it, and the older result is dropped. A file reading does the same: its reads run on its strand, one after the other, and a read under way when another is asked for publishes nothing. It does not replace an owner's protection between requests of different kinds, where a later request must win over an earlier one that finishes last: `ItemIconService` adopting an archive the game announced over restoring the newest from disk, `PackCatalogService` taking a prepared catalog over a restore. Those counters stay in their owners; a signal counts changes, a counter of requests tells which answer is the newest, and they are different jobs.

Owners:

| Owner | Signals | Replaces |
|---|---|---|
| The application's projects | current project (`CurrentProject`, whose `follows` moves to the next project and drops what the one before told) | the scope checks in `CompanionApplication`'s relays |
| `PackCatalogService` | catalog | `addListener` |
| `GamePacks` | resource packs, datapacks | `addListener(side)`, `addResourcePackListener`, `addDatapackListener` |
| `ChangeRecord` | changes | `addListener` |
| `GameLocation` | connection, process, playing | `addListener(Consumer<Change>)` |
| `WorldReading` (new) | world | `WorldReadings`, which is deleted: it reads the current world's folder itself |
| `KeyBindingControl` | assignments | `KeyAssignments`, folded into it, and `KeyBindingControl.addAssignmentListener` |
| `GameLogs` | the listed logs and crash reports | the Logs page's and the tree's own listing |
| `ItemIconService` | icons | `addListener` |
| `ResourceEdits` | edits, working pack | `addEditListener`, `addWorkingPackListener` |
| `RuntimeIndexService` | status | `addStatusListener`, and `CompanionApplication.lastIndexStatus` |

**Not state, and staying as they are:** events inside a subsystem that carry what happened, such as the debugger's session events (`DebuggerSessionController`, `MicrosoftJavaDebugEngine`, `DebuggerEditorPresentation`), the editor's caret and analysis (`AbstractCodeViewPanel`, `ASTCache`), search matches (`SearchManager`), the editor tabs' selection, script runs (`EditorScriptRunService`, `CompanionSession.addExecutionResultListener`) and `NotificationCenter`. Settings and the theme (`GlobalConfig`'s property listeners, `ThemeManager`) also keep theirs: they work, are not game or project state, and caused none of the problems above; they move only if a feature needs them to. The architecture test (section 7) lists them.

## 2. Files on disk

Nothing watches the files others write, apart from one case. Who wrote a file decides how Companion learns of it:

| Who wrote it | How Companion learns of it |
|---|---|
| The connected game | Its messages: packs, `PLAYING`, the answers to changes and reloads, and that it saved changed key bindings (section 6) |
| Companion | The owner reads again after the write and fires if the value differs (section 5) |
| Another program, or a game without a connection | What shows the file reads it when it is shown, and again when the user comes back to Companion from another program (`WindowFocus.returned()`: a window of Companion taking the focus after Companion lost it to another program, which the main window tells of; Companion's first window opening is no return) |
| A program Companion opened a file in | `FileWatch`, which takes its saves at once (below) |

A value that others than its page need is held by its owner in a `FileReading`: a reader, the last value, a comparison and a signal.

```java
FileReading<Map<String, Assignment>> assignments = new FileReading<>(
        () -> KeyBindings.readOptions(options));  // reads the whole value; a missing file is a value too
assignments.changed();                           // fires only when a read found another value
assignments.value();                             // the published snapshot; read now, on the strand, where none was read yet
assignments.refresh();                           // after Companion's own write, or when the game told of a change
```

Its whole contract, which `FileReading`'s documentation states the same way:

- **Reads run one at a time, in order, on the reading's strand**, whoever asks. `value()` returns the value published last, or reads it now where none was, so a page that asks first gets the value later changes are told against.
- **`refresh()` reads once after it was asked**; requests made while one waits are that one, so the game playing another world, which both `playing` and the datapacks tell of, reads the world once. A read under way when a request is made publishes nothing, since the read asked for is newer: the world the game left is never shown as current after it left. The first value a refresh reads is told, for followers that show it without asking, as the World tab's title.
- **`refresh()` returns the read it asked for**: its future completes with the value once that read published it or found it unchanged, passes to the newer read that overtook it, fails with a failed read and is cancelled by closing. Whether the reading closed or a newer request came is checked in the step that publishes, under the reading's lock; the signal fires and the futures complete after it.
- **`published()` answers without reading or waiting**: the value read last, or nothing before the first read. A follower that computes what it shows on the Swing thread, as the Project tree, reads this, never `value()`.
- **The user coming back refreshes a value read before**; a value nobody asked for stays unread.
- **A read that fails keeps the value read before**, and the next read that succeeds is told, since a page may show the failure. Nothing retries on a timer: the next return or request reads again.
- **Once closed, no read starts and none publishes.**
- **A reading's value is a domain value with a meaningful equality**, such as parsed assignments, and what it holds decides what fires: the volume changing in `options.txt` fires nothing.
- **What moves is the reader's.** The current world's reader asks the game location which world is current at each read, and its owner asks it to read again when the game connects, plays another world or leaves one, and after Companion changed the datapacks. Keeping the value read before is for the same world read again: another world that fails to read is published without what it holds, so nothing shows or changes the world before as if it were current.
- **Three readings:** the keys `options.txt` assigns (`KeyBindingControl`); the current world (`WorldReading`), which the Project tree, the World tab's title and the change pipeline use beside their pages; and the instance's folders (`InstanceFolders`): whether the scripts folder and `saves` exist and whether the game wrote logs or crash reports, which the Project tree's roots need. The instance's folders are read when the project opens, when the user comes back, when the game connects or plays another world, since the game makes `saves` and writes its logs, and after Companion made the scripts folder. A page that is the only reader of its files, as the logs, the configuration files, the resource packs and the Changes page, reads whenever it is shown and when the user comes back while it is shown (`readsWhenShown`). The Project tree lists its loaded Scripts folders again then; the decompiled sources follow the decompiler's `cached` signal.
- **Caches are allowed where keyed by what they cache**, a file's size and time or its hash, as `TextureThumbnails`, `CatalogIcons` and the parsed logs are. A cache is never the truth another part reads.

**Adopting a file is a write, not a reading.** A texture opened in another program from Companion is taken into the pack as that program saves it, while the user stays in the editor and looks at the game. This is the one watch: `FileWatch` tells `ExternalEdits` that a followed file was written, on its one thread; `ExternalEdits` keeps the file's baseline, waits until writes stopped, checks that the image is complete, and adopts it on the project's write queue through the pipeline. Before it records a change or asks for a reload, the adoption compares the saved content with what the pack already holds, by hash, and does nothing when they are the same: a notification of content Companion itself wrote, or a second notification of one save, must not record or reload again. Deduplication happens before side effects, in the owner, never after them in a page. A file whose folder cannot be watched is not opened, and the user is told why. Every opening watches the file anew, so one whose folder was removed and made again is followed again.

**Why nothing else is watched.** Decided on 2026-10-01, after #114 to #117 moved every watch onto one watcher. Watching folders that others own needed watches of ancestors for folders not there yet, real paths for links, retries, pauses so that Windows can rename or delete a watched folder (the game's Delete World), and settle times for files written in parts; most review findings of those PRs were about these. Reading when shown and when the user comes back answers the same question with none of them. A change that must show while Companion keeps the focus comes as a game message, not as a watch: a key rebound in the game while Companion is visible beside it comes as `KEY_ASSIGNMENTS`. When any screen closes, the game compares its key bindings with those when a screen closed before, and where they differ, tells Companion once the screen saved `options.txt`, which it does as the screen is removed, after NeoForge's `ScreenEvent.Closing`. The message carries nothing: Companion reads `options.txt` again, which stays the one place it reads keys from.

## 3. Pages

A page reads and shows through `PageLoader`, and only through it. It names the signals it follows; the loader does the rest, the same way for every page:

```java
this.loader = new PageLoader<>(this::read, this::show, this::fail)
        .page(this)
        .follows(catalog.changed())
        .follows(record.changed(), () -> !Objects.equals(record.change(target), this.seen));
```

- **It reads when the page is first shown**, never in a constructor and never because of a navigation.
- **While the page is hidden, it only notes that a followed signal fired**, once per source however often it fired, and reads once when the page is shown again. No read runs for a hidden page, whoever asks, the page itself too, as to check a finished read against a later change. A page shown again with nothing changed reads nothing, unless its last read failed, as for a file read while it was written.
- **One read at a time, newest wins.** The loader shows every result it gets; it does not compare results, since owners only signal real changes.
- **Reads run on file work, results are shown on the Swing thread.**
- **`show` keeps what the user chose:** the selection and scroll position of rows that are still there (`Tables.keepingSelection`).
- **A selection a navigation asked for lasts while its page is shown** (`PageLoader.Request`), such as a key binding to show before the keys are read. A new request replaces it, and a generic one only drops it. The page applies it when a read lists it, and drops it when a read that succeeded does not, or when the user chooses for themselves. The loader drops it when the page becomes hidden after the request: leaving the page, by a navigation or a click on another tab, ends what it was asked for. A category or kind asked for in a browser is not a page request: it settles against the listing shown when it arrives, or against the first listing when none is shown yet, so All chosen again is the user's.
- **A write checks where it goes when it is made**, not by what the page last read: the resource editor's save refuses a pack the tab no longer saves into, as the change pipeline refuses a copy changed since.
- **A page that writes holds its reads** (`hold`, `release`): while its save runs, a read under way is not shown, since it may predate the write, and followed changes wait as while the page is hidden; released, the page reads once what concerned it meanwhile. A page with unsaved changes that must not be replaced by a read (`ConfigPanel`) holds its reads the same way while they last. A page does not read again because it saved; it hears the owner's signal like every other follower.
- **A follow may say whether a change concerns the page**, asked when the page would read: the resource editor follows the whole change record, but only its own file's entry, and not its own save's. A read's preparation may also ask which followed signals led to it (`fired`): the resource editor lets Save wait only for a read after a pack change, which may move it to another pack, not for one after an edit elsewhere.
- **A read never changes an owner**, with one exception: the Changes page's read drops a change whose file holds its original value again, put back outside Companion (`ChangeRecord.observed`), and reads whenever it is shown. The configuration and resource files have no owner that reads them, so an owner would notice only when this page asked it to read anyway. Decided on 2026-09-30 instead of a step that moved the check into the owners.
- A part of a page that reads on its own, such as the resources of a definition, has its own loader with that part as its page.
- Work that only redraws from values in memory, such as icons again after new ones came, uses `loader.updates(signal, redraw)`: the same waiting and merging, with no read. Work the page runs itself instead of through the loader's read, because it has its own runs and cancellation, uses `loader.starts(signal, work)` with the same waiting and merging, and decides when it starts whether it is still wanted: an inspection asking the game again (`InspectionSession.readWanted`), the Project tree listing its Scripts folders again on the user's return. A page shown again redraws what it missed as it is shown, before what the same Swing step asks of it, such as a navigation choosing a tab the redraw adds; work it missed starts at the end of that step, after a navigation that may read the page anew. A page with nothing to read of its own has a loader without a read (`PageLoader.withoutRead(page)`). What shows outside the page, such as its tab's title, is redrawn from the owners' published values whenever their signal fires, shown or not (`retitles`).

**Navigation never reads a page's files.** It shows a page, and passes a selection to it. A navigation that carries a new request to the game, as inspecting a subject again, asks the owner of that request. `InspectionSession`'s timer, which polls the game for live values, stays until live channels (C2) push them, and is listed in the architecture test.

**A document tab opens with what it shows.** A script, a resource, a local source file and a configuration file are documents, not pages that follow state. The navigation that opens one runs its kind's open read on file work (`ScriptView.read`, `ResourceView.read`, `CodeView.read`, `ConfigFileView.read`), checks that it is still current, and then builds the tab complete on the Swing thread, placing its position in the same step. Nothing waits for a document tab to become ready. An open tab is focused without a read, except a read-only text tab, which reads its text again when a navigation places an offset in it, as in a log the game writes on. A caret moved later checks that its navigation is still current in the Swing step that moves it.

- **Editors keep what the user edits.** A script follows nothing; its save refuses a file changed since it was read. A pack editor and a configuration file keep their loaders, which keep unsaved changes; a configuration tab's first read finds the text it opened with and changes nothing.
- **A read-only tab does not follow its file**: it shows the file as read when it was opened or last navigated to. Mod archives do not change while the game runs, and the logs have their own page, which reads whenever it is shown.
- **A failed open read** fails the navigation with its reason and opens no tab.

**The Project tree computes its roots; it reads nothing to decide them.** `FileTreeView.syncRoots()` builds them on the Swing thread from what the current project's owners published: the catalog's shown index, the runtime's sources, the change count (`ChangeRecord.count()`, which takes no lock), what the game plays and the server's datapacks, the world as read last (`WorldReading.published()`) and the instance's folders. It follows those owners through `CurrentProject.follows`, the current project itself, the runtime binding through `runtimeChanged` (which has no signal yet, step 6), and the user's return, which lists the loaded Scripts folders again through `PageLoader.withoutRead(tree).starts`.

- **Signals only say when to compute; sources decide what loads.** Each root is built from a source, an immutable value of what its rows show, and its rows, also those the user expands later, are built from it. A root whose source equals the one shown keeps its node and item; another source replaces the item and loads the rows again, its own (`ROWS`, as a count of changes or the logs) or also every row loaded below (`BELOW`, as a new catalog index). A root whose rows were never loaded stays unloaded. A row may name its source too, and keeps its item, and a load under way below it, while that source is the same.
- **A reveal computes the roots first**, in the Swing step it runs, and takes the caller's validity, which it asks in the step that selects the row: a reveal overtaken while rows load below its root changes nothing. A reveal that selects ends any restoration of rows a reload under way would put back. A script action's restoration takes the action's validity the same way.
- **The decompiled sources row subscribes to its decompiler's `cached` signal**, a recorded exception until the runtime binding has a signal (step 6). It is built for one binding and replaced with it, and loads its rows again only where they were loaded.

This replaces `ShownUpdates`, the modes `whenShown` and `waitsWhileHidden`, the subscriptions pages hold themselves, the reads in constructors, the `refresh()` calls on navigation and in the `model/*View` classes, and `CompanionUi.catalogChanged` and `changesRecorded`.

## 4. Threads

The Swing thread runs Swing, and nothing that waits or grows with the data: no file access, and no measuring or parsing every row, as the freeze fixed in #104 did. Other work runs on workers from one class, `Workers`:

| Worker | For | Instead of |
|---|---|---|
| File work (`Workers.files()`, a bounded pool of platform threads) | Reads for pages and readings, and other short work off the Swing thread | The shared pool in `PageLoader` and the one-argument `supplyAsync` calls; `ResourceViewPanel`'s pool |
| Serial paths (`Workers.strand()` for owners, `Workers.fileStrand()` for file work), each a `Strand` over the shared threads | Owners, and components whose state stays on one thread or whose order matters, as open archives | The own executors of `TextureThumbnails` and `ModLogoIcons` |
| The project's write queue (`WriteQueue`, with its own serial worker from `Workers.projectWrites()`) | Every write of the pipeline, independent of unrelated reads, so closing can finish its writes | `ConfigChanges`' executor, which every category borrowed |
| Timers (`Workers.later`, one scheduler) | Settle delays, retries, timeouts; a timer only hands work to another worker | `KeyAssignments`' and `ExternalEdits`' schedulers, `JsonStateWriter`'s scheduled flush |

- Platform threads, not virtual ones: on Java 21 a virtual thread is pinned inside `synchronized` and by `ZipFile`, which most reads use.
- Swing timers stay for delays and animations on the Swing thread.
- **Allowed own threads**, each owned by a service that closes it: the debugger (`DebuggerSessionQueue`, `DebuggerEvaluationRunner`), the editor's Java analysis, script compilation, decompilation, search (also `SearchEverywherePopup`'s) and the runtime index, the MCP job service, `CompanionApplication`'s project switching and MCP lifecycle, `ProjectSelectionServer`, the item icon renderer, which its graphics library confines to one thread, and `FileWatch`'s one thread. The list lives in the architecture test; adding to it is a decision recorded here.

## 5. Writes

The change pipeline ([CHANGE_PIPELINE.md](CHANGE_PIPELINE.md)) stays the one way to change the game's and the packs' files and what the running game keeps.

- **The pipeline owns the project's write queue.** The project makes one `WriteQueue` for its pipeline and closes it before its change record; `ConfigChanges` makes none, and `ResourceEdits` and configuration settings write through the pipeline's. Its worker is separate from the shared file pool, so unrelated reads cannot delay writes or closing an empty queue.
- **A category may write beside its value inside its own write task**, as `ResourceEdits` enables the managed pack in `options.txt` and writes its `pack.mcmeta`. Nothing writes the game's or the packs' files outside a write task.
- **Companion's own files** (its state, script files, originals, decompiled sources) are not the pipeline's; they are written by their owners on serial workers.
- **After a write, the category's owner fires its signal** if the value differs.

## 6. Game messages

A message reaches the owners that handle it directly, without a relay:

```java
// When the project scope attaches to a connection:
connection.on(PackStackMessage.class, packs::told);
```

- **A project's handlers are registered while it is the current one** (`ProjectScope.listen`), and removed when it stops being current, so a project never receives another project's messages. This replaces `CompanionApplication`'s checks that the scope is still current. Owners that live as long as the application, as the server-script access and the script runs, register on the session themselves.
- **Several handlers per message**, run on the receiving thread in arrival order: `PLAYING` reaches the game location and the server-script access, and the datapacks are checked against what the game plays in the order the game sent them.
- **Answers are matched on connection and request**, as the change and reload answers are since #107. Each owner takes the `RELAY_FAILED` of its own requests, by the refused message and its id.
- **In the mod**, `CompanionAppClient` registers a handler through one method that checks the connection is authenticated and passes its number; a request that has no handler is refused, as now. `ProtocolBindings` keeps its two lists, which say which way each message goes.
- What stays in `CompanionApplication` is the application's own messages: connection, focus and inspection.
- This is the first part of A4, category registration on the roadmap; A4 then lets a category register its pages and Modpack rows the same way.

## 7. The rules

These go into [AGENTS.md](../AGENTS.md) and are checked by an architecture test that reads the sources, so a new listener list or thread is found by the build, not by a review round:

1. State Companion shows has one owner, which compares and fires a `Signal` only on a change. No other listener lists for state; outcomes go through the action's future.
2. A page reads and shows through `PageLoader` and follows signals only through it: no subscriptions, file reads or threads of its own, no reads in constructors or on navigation, and a read never changes an owner. A part of the window that outlives a project follows its owners through `CurrentProject.follows`; the architecture test counts the subscriptions left in `ui/`, each with its reason.
3. Threads, executors and schedulers come from `Workers`, except the listed ones; no async call without an executor, and no `delayedExecutor` without one: work for later goes through `Workers.later`.
4. Only `FileWatch` watches files, and only files Companion opened in another program. A file others write is read when it is shown and when the user comes back to Companion, through a `FileReading` where others than its page need the value; caches are keyed by what they cache and are never the truth.
5. The game's and the packs' files are written only inside a pipeline write task, on the project's write queue.
6. A game message is registered by the owners that handle it, on the connection.
7. The Swing thread does nothing that waits or grows with the data.
8. A problem of timing, staleness or reading twice is fixed in the system that owns it, never with a flag in a feature.
9. Moving a feature onto a system deletes its old way in the same PR: no superseded route stays. A PR says how many production lines it adds and removes, as information; no PR is sized to balance them. The finished migration is judged as a whole, for being simpler than what it replaced.

The test lists each exception with its reason. Adding one is a decision recorded in this document.

## Order of work

PRs on 1.21.1, stacked, each reviewed until clean. A shared mechanism comes with its first users; the PRs that carry risk are kept small, the ones that repeat a proven pattern move many users at once. A moved feature keeps no part of its old way. Until the last user of an old way has moved, the old way stays for those not moved yet, as `PageLoader`'s modes do; the PR that moves the last user deletes it. Decided on 2026-09-30 over a finer split of about 22 PRs, whose extra review rounds bought no safety for the mechanical moves. The files came before the remaining owners the same day: they carry most of the races the reviews found, and they are `Workers`' first users. The owners still telling through their own listeners come before the pages, which can follow only signals.

| PR | Content | Deletes |
|---|---|---|
| 1 (#112) | The slice: `Signal` and the new `PageLoader` (`page`, `follows`, `hold`), with `Tables.keepingSelection`; its first users the Key bindings page, which only shows, and the resource editor, which edits, with the owners they follow on signals (catalog, key assignments, change record, packs, resource edits), key assignments read again after Companion's own write; the architecture test with every exception of today listed | the two pages' own subscriptions, constructor reads, navigation refreshes and selection keeping, `KeyBindingControl.addAssignmentListener`, `PackResourceEditor`'s read and write counters and follow flags |
| 2 (#113) | Game messages registered by their owners on the connection, in Companion and the mod | `CompanionSession.Listener`'s methods for the project's messages, their relay in `CompanionApplication`, the mod's setters and repeated guards |
| 3 (#114) | `Workers` and `Strand`, `FileWatch` and `FileReading`, with `KeyAssignments` as first user | `KeyAssignments`' watcher, scheduler and counters |
| 4 (#115) | The current world as a `FileReading` that follows `playing`, with the World page and the tree | `WorldReadings`, the World page's read whenever shown |
| 5 | The owners still telling through their own listeners on signals: `GameLocation`, `ItemIconService`, `RuntimeIndexService` | `GameLocation.Change` and the three listener lists |
| 6 | The last watchers onto `FileWatch`: the Project tree's folders, told of entries only, and `ExternalEdits`, whose settle runs on the timer; `FileUtils`' pause for Companion's own moves becomes `FileWatch.pausing` | `FileUtils`, the watchers and schedulers of `ExternalEdits` |
| 7 | All remaining pages on `page` and `follows`, the logs, configuration and resource packs pages reading whenever shown; the World tab's title from its owner; reads on the file workers | `ShownUpdates`, `whenShown`, `waitsWhileHidden` and `follow`, the pages' own subscriptions, the reads in constructors, the navigation refreshes |
| 8a | The pipeline owning the write queue; the remaining executors and one-argument async calls onto `Workers` | `ConfigChanges`' executor, the UI classes' and `JsonStateWriter`'s executors, every use of the shared pool |
| 8b | The current project as state (`CurrentProject`), whose signals the main window follows | the `CompanionUi` relays and their scope checks |
| 9 | Files read when shown and when the user comes back to Companion; `FileWatch` only for files opened in another program; the decompiled sources on a signal | `KeyAssignments`; the watches of `options.txt`, `saves`, the current world, its datapacks, the Project tree's folders and the decompiled sources; `FileReading`'s watch, settle, retries and counter; `FileWatch`'s ancestors, links, retries and pauses |

After the messages, A4 continues with categories registering their pages and Modpack rows. Splitting `CompanionApplication` (the game connection and the MCP server into their own classes) and the mod's `CompanionAppClient` (launching Companion) is easier then and is decided at that point.

UI work that follows no system, such as every page's loading, empty and failed states through `BrowserBody`, the wrong reason when the catalog is missing, and command text against [UI_GUIDE.md](UI_GUIDE.md), goes in on its own meanwhile.

## Tests

- `Signal`: listeners in order, removal of one subscription. `FileReading`: read when first asked, not before; told only on another value; read again when the user comes back, not once closed; a failed read keeping the value and the next success told; reads never at once, with one paused mid-flight.
- `FileWatch`: a write of the followed file told and one of its neighbour not, two files of one folder, a stopped watch, a folder that cannot be watched.
- `PageLoader`: hidden and shown, a signal during a read, several while hidden, a change that does not concern the page, a held page, a read under way when the page holds, a part of a page. The old modes' tests go with the modes.
- Messages: two handlers in order, a handler of a detached scope not called, an answer to another connection not taken, a request without a handler refused, an unauthenticated message refused in the mod.
- The architecture test: no listener list, thread, executor, watcher or one-argument async call outside the owners and exceptions it lists.
- **Each moved page is tested along the user's path**, not only through its parts: the page built as the application builds it, opened through `NavigationService` as a click opens it, then shown. The test counts the reads: one when it opens; none when it is shown again with nothing changed; one after a signal while it was hidden; none when navigating to it while it is showing, apart from the selection asked for. The loader passing its own tests does not show that opening a page reads once.
- Each migration PR keeps the tests of what it moves.

## Not in this

- The debugger, the editor's analysis and search keep their own events and threads; they are self-contained and not project state.
- The mod keeps its own threads for captures (`PackCatalogPublisher`, `ResourceSnapshots`) and reads game state on the game's thread, as now. Only its message handling changes (section 6).
- No reactive library and no general event bus: a signal is a list of listeners, a loader a running read and a flag, and every other rule is about who owns what.
