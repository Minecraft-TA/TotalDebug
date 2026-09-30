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
game message ─────────────────────┐
file on disk ─► watch ─► reading ─┼─► owner compares ─► signal (if different) ─► page loader ─► read ─► show
pipeline write ───────────────────┘

page action ─► change pipeline ─► project's write queue ─► owner ─► signal
```

## 1. Owners and signals

Every piece of state Companion shows has one owner: a service of the project scope or of the application. The owner holds the current value and compares every new one with it. It fires a `Signal` only when the value differs, so everything downstream can trust that a signal means a change. The comparison is the owner's, of its domain values (parsed assignments, the enabled packs, the catalog), which have a meaningful equality; nothing compares what a page shows, such as images or editor models.

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
- **Connection-bound work compares connections.** `GameLocation`'s connection value includes the connection's number and the game's process. A request waiting for an answer keeps the number of the connection it was sent on, and is failed when the connection signal shows another. This replaces reacting to `DISCONNECTED` as an event, which a quick reconnect can hide.
- **The current project is state too.** The application owns it and signals a switch. UI that outlives a project (the tab strip, the Project tree, the status bar, search) follows "the current project's catalog" through its loader, which subscribes again on a switch. This deletes the `project.get() != scope` and `currentScope() == scope` checks.

**Newest wins, where it is the same read.** A page loader runs one read at a time: a request during a read makes one more read after it, and the older result is dropped. When file readings (section 2) need the same, it moves into a class of its own, `LatestRead`, that both use, which also removes `KeyAssignments.generation`. It does not replace an owner's protection between requests of different kinds, where a later request must win over an earlier one that finishes last: `ItemIconService` adopting an archive the game announced over restoring the newest from disk, `PackCatalogService` taking a prepared catalog over a restore. Those counters stay in their owners; a signal counts changes, a counter of requests tells which answer is the newest, and they are different jobs.

Owners:

| Owner | Signals | Replaces |
|---|---|---|
| The application's projects | current project | the scope checks in `FileTreeView`, `CompanionApplication` and the UI |
| `PackCatalogService` | catalog | `addListener` |
| `GamePacks` | resource packs, datapacks | `addListener(side)`, `addResourcePackListener`, `addDatapackListener` |
| `ChangeRecord` | changes | `addListener` |
| `GameLocation` | connection (with the process), playing | `addListener(Consumer<Change>)` |
| `CurrentWorld` (new) | world | `WorldReadings`, which is deleted: it reads the current world's `level.dat` itself |
| `KeyAssignments` | assignments | its `addListener` and `KeyBindingControl.addAssignmentListener` |
| `GameLogs` | the listed logs and crash reports | the Logs page's and the tree's own listing |
| `ItemIconService` | icons | `addListener` |
| `ResourceEdits` | edits, working pack | `addEditListener`, `addWorkingPackListener` |
| `RuntimeIndexService` | status | `addStatusListener`, and `CompanionApplication.lastIndexStatus` |

**Not state, and staying as they are:** events inside a subsystem that carry what happened, such as the debugger's session events (`DebuggerSessionController`, `MicrosoftJavaDebugEngine`, `DebuggerEditorPresentation`), the editor's caret and analysis (`AbstractCodeViewPanel`, `ASTCache`), search matches (`SearchManager`), the editor tabs' selection, script runs (`EditorScriptRunService`, `CompanionSession.addExecutionResultListener`) and `NotificationCenter`. Settings and the theme (`GlobalConfig`'s property listeners, `ThemeManager`) also keep theirs: they work, are not game or project state, and caused none of the problems above; they move only if a feature needs them to. The architecture test (section 7) lists them.

## 2. Files on disk

Files Companion shows that others write are followed by an owner through a `FileReading`: a watch, a reader, the last value, a comparison and a signal. It is what `KeyAssignments` is today, made once for all.

```java
FileReading<Assignments> assignments = files.reading(
        options,                         // the file or folder, resolved to one path form
        Assignments::read,               // reads the whole file; runs on file work
        Duration.ofMillis(300));         // settle: read once writes stopped for this long
```

- **One `FileWatch` for the application** does the watching. A registration lives only while its reading has a follower.
- **A folder that does not exist yet** is watched through its nearest existing ancestor, and moves down when it appears: a new world's datapacks, `crash-reports`, `config`.
- **One path form.** Every registration resolves to the real path of its nearest existing ancestor plus the rest, so a linked folder is not watched twice or missed.
- **Settle and maximum wait.** A reading waits until writes stopped for its settle time, but at most a maximum wait, so a file written without pause still reads.
- **A read that fails is retried** after 1, 5 and 30 seconds, as a file written in place can be read half written, and a watch that fails or overflows reads everything it follows once.
- **Folders that move are the owner's.** `CurrentWorld` follows `playing` and moves its reading to the world the game plays, or with the game closed, the last played.
- **Windows renames.** A watched folder and its ancestors cannot be renamed while watched. `FileWatch` pauses around Companion's own renames; registrations sit on the smallest folders that answer the question, and readings of a world are dropped when the game leaves it. Whether the game deleting or renaming a world while it is followed works is tested on Windows in the step that adds world readings.
- **A reading's value is a domain value with a meaningful equality**, such as parsed assignments or a list of names; the reader supplies it, and a reading whose value cannot be compared fires on every change.
- **What each reading holds decides what fires.** The logs' reading holds the names of the logs and crash reports, not their sizes, so `latest.log` growing does not fire; the Logs page reads the log a row shows when the row is shown. A configuration folder's reading ignores Companion's own `*.totaldebug-original` files.
- **Readings replace every "read whenever shown":** `options.txt`, configuration files, logs and crash reports, the current world's `level.dat` and icon, the `saves` and `resourcepacks` folders.
- **Caches are allowed where keyed by what they cache**, a file's size and time or its hash, as `TextureThumbnails`, `CatalogIcons` and the parsed logs are. A cache is never the truth another part reads.

**Adopting a file is a write, not a reading.** A texture saved in an external editor is taken into the pack: `ExternalEdits` registers with `FileWatch`, keeps its baseline and checks that the image is complete, and its adoption runs on the project's write queue through the pipeline. Before it records a change or asks for a reload, the adoption compares the saved content with what the pack already holds, by hash, and does nothing when they are the same: a notification of content Companion itself wrote, or a second notification of one save, must not record or reload again. Deduplication happens before side effects, in the owner, never after them in a page. The watchers of `FileUtils`, `ExternalEdits` and `KeyAssignments` become registrations.

## 3. Pages

A page reads and shows through `PageLoader`, and only through it. It names the signals it follows; the loader does the rest, the same way for every page:

```java
this.loader = new PageLoader<>(this::read, this::show, this::fail)
        .page(this)
        .follows(catalog.changed())
        .follows(record.changed(), () -> !Objects.equals(record.change(target), this.seen));
```

- **It reads when the page is first shown**, never in a constructor and never because of a navigation.
- **While the page is hidden, it only notes that a followed signal fired**, and reads once when the page is shown again. A page shown again with nothing changed reads nothing, unless its last read failed, as for a file read while it was written.
- **One read at a time, newest wins.** The loader shows every result it gets; it does not compare results, since owners only signal real changes.
- **Reads run on file work, results are shown on the Swing thread.**
- **`show` keeps what the user chose:** the selection and scroll position of rows that are still there (`Tables.keepingSelection`), and a selection a navigation asked for, such as a key binding to show, which the page keeps until a result contains it.
- **A page that writes holds its reads** (`hold`, `release`): while its save runs, a read under way is not shown, since it may predate the write, and followed changes wait as while the page is hidden; released, the page reads once what concerned it meanwhile. A page with unsaved changes that must not be replaced by a read (`ConfigPanel`) holds its reads the same way while they last. A page does not read again because it saved; it hears the owner's signal like every other follower.
- **A follow may say whether a change concerns the page**, asked when the page would read: the resource editor follows the whole change record, but only its own file's entry, and not its own save's.
- **A read never changes an owner.** What the Changes page's labels do today with `ChangeRecord.observed` moves to the owners, which notice a value put back outside Companion on their own readings, on the write queue.
- A part of a page that reads on its own, such as the resources of a definition, has its own loader with that part as its page.
- Work that only redraws from values in memory, such as a tab's title and icon, uses `loader.updates(runnable)`: the same waiting and merging, with no read.

**Navigation never reads files.** It shows a page, and passes a selection to it. A navigation that carries a new request to the game, as inspecting a subject again, asks the owner of that request. `InspectionSession`'s timer, which polls the game for live values, stays until live channels (C2) push them, and is listed in the architecture test.

This replaces `ShownUpdates`, the three modes, the subscriptions pages hold themselves, the reads in constructors, the `refresh()` calls on navigation and in the `model/*View` classes, and `CompanionUi.catalogChanged` and `changesRecorded`.

## 4. Threads

The Swing thread runs Swing, and nothing that waits or grows with the data: no file access, and no measuring or parsing every row, as the freeze fixed in #104 did. Other work runs on workers from one class, `Workers`:

| Worker | For | Instead of |
|---|---|---|
| File work (a bounded pool of platform threads) | Reads for pages and readings | The shared pool in `PageLoader` and the one-argument `supplyAsync` calls; `ResourceViewPanel`'s pool |
| Serial workers (one thread each, by name) | Components whose state is confined to one thread, or whose order matters | The own executors of `TextureThumbnails`, `ModLogoIcons`, the item icon renderer, `JsonStateWriter` |
| The project's write queue (a serial worker the pipeline owns) | Every write of the pipeline, adoptions, and owners noticing values put back | `ConfigChanges`' executor, which every category borrows today |
| Timers (one scheduler) | Settle delays, retries, timeouts; a timer only hands work to another worker | `KeyAssignments`' and `ExternalEdits`' schedulers, `JsonStateWriter`'s scheduled flush |

- Platform threads, not virtual ones: on Java 21 a virtual thread is pinned inside `synchronized` and by `ZipFile`, which most reads use.
- Swing timers stay for delays and animations on the Swing thread.
- **Allowed own threads**, each owned by a service that closes it: the debugger (`DebuggerSessionQueue`, `DebuggerEvaluationRunner`), the editor's Java analysis, script compilation, decompilation, search (also `SearchEverywherePopup`'s) and the runtime index, the MCP job service, `CompanionApplication`'s project switching and MCP lifecycle, and `ProjectSelectionServer`. The list lives in the architecture test; adding to it is a decision recorded here.

## 5. Writes

The change pipeline ([CHANGE_PIPELINE.md](CHANGE_PIPELINE.md)) stays the one way to change the game's and the packs' files and what the running game keeps.

- **The pipeline owns the project's write queue.** `ConfigChanges` stops creating it; `ResourceEdits` gets it from the pipeline.
- **A category may write beside its value inside its own write task**, as `ResourceEdits` enables the managed pack in `options.txt` and writes its `pack.mcmeta`. Nothing writes the game's or the packs' files outside a write task.
- **Companion's own files** (its state, script files, originals, decompiled sources) are not the pipeline's; they are written by their owners on serial workers.
- **After a write, the category's owner fires its signal** if the value differs.

## 6. Game messages

A message reaches the owners that handle it directly, without a relay:

```java
// When the project scope attaches to a connection:
connection.on(PackStackMessage.class, packs::told);
```

- **Handlers belong to the connection attached to the scope**, registered when the scope attaches and removed when it detaches, so a scope never receives another connection's messages. This replaces `CompanionApplication`'s checks that the scope is still current.
- **Several handlers per message**, run on the receiving thread in arrival order: `PLAYING` reaches the game location and the server scripts, and the datapacks are checked against what the game plays in the order the game sent them.
- **Answers are matched on connection and request**, as the change and reload answers are since #107. `RELAY_FAILED` goes to the owner of the refused request by its id, instead of a switch in `CompanionApplication`.
- **In the mod**, `CompanionAppClient` registers a handler through one method that checks the connection is authenticated and passes its number; a request that has no handler is refused, as now. `ProtocolBindings` keeps its two lists, which say which way each message goes.
- What stays in `CompanionApplication` is the application's own messages: connection, focus and inspection.
- This is the first part of A4, category registration on the roadmap; A4 then lets a category register its pages and Modpack rows the same way.

## 7. The rules

These go into [AGENTS.md](../AGENTS.md) and are checked by an architecture test that reads the sources, so a new listener list or thread is found by the build, not by a review round:

1. State Companion shows has one owner, which compares and fires a `Signal` only on a change. No other listener lists for state; outcomes go through the action's future.
2. A page reads and shows through `PageLoader` and follows signals only through it: no subscriptions, file reads or threads of its own, no reads in constructors or on navigation, and a read never changes an owner.
3. Threads, executors and schedulers come from `Workers`, except the listed ones; no one-argument `supplyAsync` or `runAsync`.
4. Only `FileWatch` watches files. A file others write is followed through a `FileReading`; caches are keyed by what they cache and are never the truth.
5. The game's and the packs' files are written only inside a pipeline write task, on the project's write queue.
6. A game message is registered by the owners that handle it, on the connection.
7. The Swing thread does nothing that waits or grows with the data.
8. A problem of timing, staleness or reading twice is fixed in the system that owns it, never with a flag in a feature.
9. Moving a feature onto a system deletes its old way in the same PR: no superseded route stays. A PR says how many production lines it adds and removes, as information; no PR is sized to balance them. The finished migration is judged as a whole, for being simpler than what it replaced.

The test lists each exception with its reason. Adding one is a decision recorded in this document.

## Order of work

Six PRs on 1.21.1, each reviewed until clean. A shared mechanism comes with its first users; the PRs that carry risk (1, 2 and 4) are kept small, the ones that repeat a proven pattern (3, 5 and 6) move many users at once. A moved feature keeps no part of its old way. Until the last user of an old way has moved, the old way stays for those not moved yet, as `PageLoader`'s modes do; the PR that moves the last user deletes it. Decided on 2026-09-30 over a finer split of about 22 PRs, whose extra review rounds bought no safety for the mechanical moves.

| PR | Content | Deletes |
|---|---|---|
| 1 | The slice: `Signal` and the new `PageLoader` (`page`, `follows`, `hold`), with `Tables.keepingSelection`; its first users the Key bindings page, which only shows, and the resource editor, which edits, with the owners they follow on signals (catalog, key assignments, change record, packs, resource edits), key assignments read again after Companion's own write; the architecture test with every exception of today listed | the two pages' own subscriptions, constructor reads, navigation refreshes and selection keeping, `KeyBindingControl.addAssignmentListener`, `PackResourceEditor`'s read and write counters and follow flags |
| 2 | Game messages registered by their owners on the connection, in Companion and the mod | `CompanionSession.Listener`'s message methods, the relay and scope checks in `CompanionApplication`, the `RELAY_FAILED` switch, the mod's setters and repeated guards |
| 3 | The remaining owners on signals; the current project as state; connection numbers for waiting requests; the pipeline owning the write queue; the remaining executors onto `Workers` | the listener lists, `ConfigChanges`' executor, the scope checks, the UI classes' executors |
| 4 | `Workers`' timers, `FileWatch` and `FileReading`, with `KeyAssignments` and `CurrentWorld` (the World page and the tree) as first users | the watchers and schedulers of `FileUtils` and `KeyAssignments`, `WorldReadings` |
| 5 | The remaining readings (`GameLogs`, the configuration folders, `saves` and `resourcepacks`) with their pages; `ExternalEdits` as an adoption | `ExternalEdits`' watcher and scheduler, the `readsWhenShown` mode |
| 6 | All remaining pages, the Project tree and the tab strip; no file checks on the Swing thread | `ShownUpdates`, the old modes, the pages' own subscriptions, the reads in constructors, the navigation refreshes, the `CompanionUi` relays, `ChangeRecord.observed` from page reads |

If PR 1 shows a flaw, the design is revised here before anything else moves. PR 3 needs `Workers` before PR 4; it brings the file work and serial workers, PR 4 the timers.

After the messages, A4 continues with categories registering their pages and Modpack rows. Splitting `CompanionApplication` (the game connection and the MCP server into their own classes) and the mod's `CompanionAppClient` (launching Companion) is easier then and is decided at that point.

UI work that follows no system, such as every page's loading, empty and failed states through `BrowserBody`, the wrong reason when the catalog is missing, and command text against [UI_GUIDE.md](UI_GUIDE.md), goes in on its own meanwhile.

## Tests

- `Signal`: listeners in order, removal of one subscription. `LatestRead`, with PR 4: a request during a read, several during one, a failure.
- `FileReading` and `FileWatch`, through a clock the test advances instead of real waits: settle and maximum wait, a folder that appears later, a linked folder, overflow, a failed read retried, a pause for a rename, a reading whose value did not change.
- `PageLoader`: hidden and shown, a signal during a read, several while hidden, a change that does not concern the page, a held page, a read under way when the page holds, a part of a page. The old modes' tests go with the modes.
- Messages: two handlers in order, a handler of a detached scope not called, an answer to another connection not taken, a request without a handler refused, an unauthenticated message refused in the mod.
- The architecture test: no listener list, thread, executor, watcher or one-argument async call outside the owners and exceptions it lists.
- **Each moved page is tested along the user's path**, not only through its parts: the page built as the application builds it, opened through `NavigationService` as a click opens it, then shown. The test counts the reads: one when it opens; none when it is shown again with nothing changed; one after a signal while it was hidden; none when navigating to it while it is showing, apart from the selection asked for. `LatestRead` passing its own tests does not show that opening a page reads once.
- Each migration PR keeps the tests of what it moves.

## Not in this

- The debugger, the editor's analysis and search keep their own events and threads; they are self-contained and not project state.
- The mod keeps its own threads for captures (`PackCatalogPublisher`, `ResourceSnapshots`) and reads game state on the game's thread, as now. Only its message handling changes (section 6).
- No reactive library and no general event bus: a signal is a list of listeners, a loader a running read and a flag, and every other rule is about who owns what.
