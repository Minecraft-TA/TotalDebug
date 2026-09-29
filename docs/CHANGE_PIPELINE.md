# Change pipeline

Design for item A3 of the [roadmap](ROADMAP.md): one path for every change Companion makes to the pack, the running game or a world. It builds on [GAME_LOCATION.md](GAME_LOCATION.md), which answers where the game is and whether a change is live, written to a file or refused, and on [MOD_SIDES.md](MOD_SIDES.md), which carries changes the server owns. A category then only says how to read and write its values; the pipeline does the rest, the same way for every category.

Built so far: layers 1 to 3 of the [order of work](#order-of-work): the pipeline with key bindings and resources on it, and its reload queues; of layer 4, the resource pack selection.

## Before the pipeline

Four categories wrote: configuration settings, key bindings, resources and textures, and pack order. Each repeated the same steps in its own way:

| Step | Configuration (`ConfigChanges`, `ConfigWriter`) | Key bindings (`KeyBindingControl`) | Resources (`ResourceEdits`) | Pack order (`PackSelections`) |
|---|---|---|---|---|
| Live or file | Always the file; NeoForge's config watcher applies it | Asks `GameState`, then `SET_KEY_BINDING` or `options.txt` | Asks, writes the pack, then `RELOAD` | Asks, then `SET_PACKS` or `options.txt` and `level.dat` |
| Stale check | The text the edit was made against | None; the key shown is only used for the record | The content's hash | Only when reverting |
| Recorded | After the file write | When the game answers, also after the wait timed out | After the write | After the write or the game's answer |
| Revert | In the UI (`ConfigWriter`) | Sets the original through `setAll` | Its own `revert` | Its own `revert` |
| When it takes effect | `Effect`, with rejoin and restart kept pending | A `live` flag | `Saved.effect` | Borrows `ConfigChanges.Effect` |
| Undo | Ctrl+Z in the configuration table | None | None | None |

`ChangesPanel` knows every kind by name to list and revert it, and the game has one message pair and one handler setter in `CompanionAppClient` per category.

## The pipeline

### What a category provides

A category describes its values and nothing else:

- **Its target:** what one change names, such as a setting of a file, a key binding, a resource in a pack, or a pack stack. Targets are the change record's (`ChangeRecord.Target`) and keep its stored form.
- **Its owner:** the client, startup, a world or a server, as [GAME_LOCATION.md](GAME_LOCATION.md#who-owns-what) divides them. The owner names the refusals and binds a reload to its connection or world; it does not decide how the value is written.
- **How it is written**, one of two:
  - **The file, then the game takes it up.** The file is written whether or not a game runs, since the game reads it again: configuration, which NeoForge's watcher applies, and resources and datapack files, which a reload applies.
  - **The game, or the file where no game writes over it.** A running game keeps the value itself and saves it over the file, so a connected game changes it live, a closed game's file is written, and a game running without a connection is asked to connect: key bindings in `options.txt`, the resource pack selection, a world's datapack selection in `level.dat`.
- **Reading the value:** from its file, and from the connected game where the game knows it better, as the pack stack does.
- **Writing the file**, and for the second kind **applying it live:** the handler in the mod that sets the value in the running game, registered in the mod's change table (below).
- **How the game takes it up:** nothing more, NeoForge's config watcher, a reload of some kind, a rejoin or a restart, from which the pipeline tells when the change takes effect.
- **How it is named:** a label for the target and the words for its value, which the Changes page, status lines and refusals use.

Values are text, as the change record stores them. A category may read and write them through its own type: a key binding's `Assignment`, a pack stack's list of ids, a resource's content, whose recorded value is its hash while `ResourceOriginals` keeps the bytes.

### What the pipeline does

For a change of a target from the value the user saw to a new one:

1. **Queue.** The change waits in the project's write queue behind the changes before it, so where the game is and what the file holds are read after them, never on the Swing thread.
2. **Decide.** A change written to its file goes there. A change the game keeps asks `GameState` for its owner: live through the connection, the file, or refused with the exact requirement.
3. **Check.** The value is still the one the user saw, or the change is refused with what changed it: "changed in the game since this view read it". Where the change is live, the game checks, since it holds the value; otherwise the pipeline checks the file. This is one compare-and-set for every category, and gives key bindings the check they lack.
4. **Apply.** It writes the file, or sends the change to the game and waits for its answer. A live answer that comes after the wait is still recorded; a disconnect fails the waiting changes.
5. **Record.** It enters the change in the change record, with the value before the first change kept as the original.
6. **Follow up.** It asks for the reloads the change needs (below) and keeps a rejoin or restart pending until the game has done it, as `ConfigChanges` does today for settings.
7. **Answer.** The change completes with its outcome: the values before and after, where it was written, when it takes effect, and what overrides it, if anything (see [What an edit changes](#what-an-edit-changes)).

Several targets change together as one change: key bindings set together, such as one taken off a key and another put on it, a texture with the `.mcmeta` it brings, a configuration file edited as text.

- **Checked first.** Every target's expected value is checked before any is changed; one stale value refuses the whole change.
- **Live, at once.** The game gets the whole change in one request and applies it in one step on its thread, so it never holds half of it.
- **Files, in order and recorded.** Files cannot be replaced together. Each is staged and moved into place, the files it brings before the one they belong to, as resource saves do today. Should a move fail, the files already in place stay and are recorded, so the Changes page shows them and a revert takes them back, and the failure names what was and was not written.

### Live changes

The game gets one message pair for every category, as the relay gave the server one table:

- `CHANGE` (Companion to the game): request id, and one or more edits, each the category, the target, the value it expects to replace and the new value.
- `CHANGE_RESULT` (game to Companion): request id, and for each edit the value before and the value now in effect; or, for the whole change, why it was refused.

In the mod, a change table on the client maps each category to its handler. The client checks every edit's expected value, then sets them all, on the client thread. A target that already holds its new value is answered as it is, as the pipeline does for a file: a revert of a value the player put back in the game ends the change instead of being refused. A category names its target to the game in the game's words (`gameTarget`), such as `resourcePacks`, and to the user in the user's, such as "The resource packs". `SET_KEY_BINDING`, `KEY_BINDING_RESULT` and `SET_PACKS` go away; `RELOAD` and `RELOAD_RESULT` stay for reloads, which are not changes of a value. A change the server owns goes as the same `CHANGE` through the relay (`TO_SERVER`) to the server's `ServerOperations`; A3 defines that owner and builds nothing on it, and A2 layer 3 fills it when datapacks and game rules move to the server.

### Reloads

A category says what a change needs; the pipeline batches it with its owner's other reloads, in one queue per owner:

- **Client resources** (language, textures, all resources), bound to the connection they were asked on.
- **A world's data**, bound to that world and the connection.

This replaces the one shared batch of `ResourceEdits`, whose rules for which world and connection a reload belongs to had to split it (the second point of [#78](https://github.com/Minecraft-TA/TotalDebug/issues/78)).

### Revert and undo

- **Revert** is a change back to the recorded original, expecting the recorded current value. A value changed outside Companion since is left alone and named, unless it is the original again, which ends the entry. This is what `PackSelections.revert` and `ResourceEdits.revert` do today, for every category.
- **Undo stays where it is.** Editors undo before a save: the texture editor its strokes, the text editors their typing. After a save, revert takes a change back to its original. The configuration table keeps its Ctrl+Z, now a change back to the value before, which is short text. The pipeline keeps no history of saved versions: going back to a version between the original and the last save would need every saved content kept, for a case revert and the editors' own undo already cover.
- The **Changes page** lists the record through each category's labels and reverts through the pipeline; it no longer knows the kinds.

## What an edit changes

The outcome carries where a change was written, when it takes effect and what overrides it, as data: the pipeline needs them anyway, to keep a rejoin pending or to revert the right copy.

How much of that the views show is decided later. The aim is that edits just work: an editor writes where the user expects, such as the open world's copy of a server configuration, and the user hears about an edit only when it will not do what it seems to: it waits for a rejoin or a restart, something overrides it, or it only reaches worlds created later. A3 keeps what the views show today: a refusal names what to do, and success is silent where the view already shows the result, as [UI_GUIDE.md](UI_GUIDE.md) asks; a status line appears only when the change does not take effect at once.

## Adding a category

A new writing category, such as Game options (B2), then consists of:

1. its target and the text form of its values;
2. reading and writing its file;
3. its handler in the mod's change table;
4. its rows in the UI, which A4 lets it register itself.

It does not edit the pipeline, `ChangesPanel`, `CompanionApplication`, `CompanionAppClient` or the protocol version.

## Order of work

A stack, each layer at most about 500 lines and reviewed on its own. The first two layers take the two ways of writing, a value the game keeps and a file the game takes up; the rest moves onto the pipeline only once both hold.

1. **The pipeline and key bindings**, with `CHANGE` and `CHANGE_RESULT` and the mod's change table. Key bindings gain the stale check, and a change of several bindings is one request. One protocol version.
2. **Resources**, written to their files while the game runs and taken up by a reload: changes of several targets with hashes as values, the files written in order and recorded.
3. **Reload queues per owner**, replacing the shared batch.
4. **Pack order** onto it; `SET_PACKS` goes. Two PRs, one per owner:
   - **The resource packs:** a `CHANGE` of the category `resourcePacks` selects them in the game client and saves `options.txt`, as the pack screen does; the pipeline then asks the client's reload queue for the reload that makes the game use them, so the Apply button waits for it and names a reload that failed. A closed game's `options.txt` is written through the pipeline. Setting a selection replaces what the game holds, as the pack screen does; a revert expects what Companion last enabled. The game client no longer takes `SET_PACKS`.
   - **The datapacks:** the same `CHANGE` through the relay to the world's server, with the data reload in the world's queue; `SET_PACKS` goes.
5. **Configuration** onto it; the writing in `ConfigWriter` leaves the UI, its Ctrl+Z becomes a change back, and `ConfigChanges.Effect` becomes the pipeline's.
6. **The Changes page** through the categories' labels, without knowing the kinds.

Each layer stands on its own; nothing outside these categories waits for the whole stack.

Done when each category keeps only its own reading and writing of values, as the roadmap puts it, and the Changes page names no category.

## Not in A3

- Changes the server owns (datapacks and, if wanted, game rules on a server): the owner exists, A2 layer 3 fills it.
- Showing more of what an edit changes, such as where it was written, in the views; see [What an edit changes](#what-an-edit-changes).
- Whether a change ships with the pack when the instance is exported as a modpack: instance files would, worlds would not.
- Category registration without editing `CompanionApplication` (A4).
- Saved versions; see [Later: saved versions](#later-saved-versions).

## Later: saved versions

A3 keeps no history of saved versions, but the pipeline is where one would go.

**When it would pay off:**
- **Trying variants in the game:** a texture saved as B, looked at in the game, saved as C, and B wanted back after its tab closed. The editor's undo ends with the tab, and revert only reaches the original.
- **Edits an agent makes:** a series of changes to the pack that the user reviews step by step and takes back to a point between the original and the last one, not only to the original. This grows with every category that writes.
- **Authoring a pack over time:** what a configuration was before yesterday's changes. Version control of the instance answers that today, for those who use it.

**Why it is one addition:** every change passes through the pipeline, which knows each change's values before and after, and `ResourceOriginals` already stores contents by hash. Keeping versions means storing the values a change replaced; no category changes.

**Still to decide then:** how long versions are kept and how much space they may take; whether the history is per target, per view, or per set of changes, such as one agent session; and how it is shown, which, like [what an edit changes](#what-an-edit-changes), is state the user asks for rather than something each view shows.

## Decided

On 2026-09-29:

- Resources are inside the pipeline, as changes of several targets with hashes as values.
- No undo history of saved versions in A3; editors keep their own undo, and revert covers the rest. Why and when versions would pay off is under [Later: saved versions](#later-saved-versions).
- Values are text in the record and on the wire, and typed inside each category.
- How much the views show of what an edit changes is decided later.
