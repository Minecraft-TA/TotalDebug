# Change pipeline

Design for item A3 of the [roadmap](ROADMAP.md): one path for every change Companion makes to the pack, the running game or a world. It builds on [GAME_LOCATION.md](GAME_LOCATION.md), which answers where the game is and whether a change is live, written to a file or refused, and on [MOD_SIDES.md](MOD_SIDES.md), which carries changes the server owns. A category then only says how to read and write its values; the pipeline does the rest, the same way for every category, and tells the user what each edit changes.

## Today

Four categories write: configuration settings, key bindings, resources and textures, and pack order. Each repeats the same steps in its own way:

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
- **Its owner:** the client, startup, a world or a server, as [GAME_LOCATION.md](GAME_LOCATION.md#who-owns-what) divides them. The owner picks the `GameState` question and the words of a refusal.
- **Reading the value:** from its file, and from the connected game where the game knows it better, as the pack stack does.
- **Writing the file:** the value into its file, for a game that is closed or does not have the world open.
- **Applying it live:** the handler in the mod that sets the value in the running game, registered in the mod's change table (below).
- **What a change needs afterwards:** nothing, a reload of some kind, a rejoin or a restart, from which the pipeline tells when it takes effect.
- **How it is named:** a label for the target and the words for its value, which the Changes page, status lines and refusals use.

Values are text, as the change record stores them. A category may read and write them through its own type: a key binding's `Assignment`, a pack stack's list of ids, a resource's content, whose recorded value is its hash while `ResourceOriginals` keeps the bytes.

### What the pipeline does

For a change of a target from the value the user saw to a new one:

1. **Queue.** The change waits in the project's write queue behind the changes before it, so where the game is and what the file holds are read after them, never on the Swing thread.
2. **Decide.** `GameState` answers for the owner: live through the connection, the file, or refused with the exact requirement.
3. **Check.** The value is still the one the user saw, or the change is refused with what changed it: "changed in the game since this view read it". Where the change is live, the game checks, since it holds the value; otherwise the pipeline checks the file. This is one compare-and-set for every category, and gives key bindings the check they lack.
4. **Apply.** It writes the file, or sends the change to the game and waits for its answer. A live answer that comes after the wait is still recorded; a disconnect fails the waiting changes.
5. **Record.** It enters the change in the change record, with the value before the first change kept as the original.
6. **Follow up.** It asks for the reloads the change needs (below) and keeps a rejoin or restart pending until the game has done it, as `ConfigChanges` does today for settings.
7. **Answer.** The change completes with its outcome: the values before and after, its scope, when it takes effect, and what overrides it, if anything (see [Scope](#scope)).

Several targets change together as one change: key bindings set together, a texture with the `.mcmeta` it brings, a configuration file edited as text. They are checked, applied and recorded together, and fail together.

### Live changes

The game gets one message pair for every category, as the relay gave the server one table:

- `CHANGE` (Companion to the game): request id, the category, the target and the value the change expects to replace, and the new value.
- `CHANGE_RESULT` (game to Companion): request id, the value before and the value now in effect, or why it was refused.

In the mod, a change table on the client maps each category to its handler, which runs on the client thread, compares and sets. `SET_KEY_BINDING`, `KEY_BINDING_RESULT` and `SET_PACKS` go away; `RELOAD` and `RELOAD_RESULT` stay for reloads, which are not changes of a value. A change the server owns goes as the same `CHANGE` through the relay (`TO_SERVER`) to the server's `ServerOperations`; A3 defines that owner and builds nothing on it, and A2 layer 3 fills it when datapacks and game rules move to the server.

### Reloads

A category says what a change needs; the pipeline batches it with its owner's other reloads, in one queue per owner:

- **Client resources** (language, textures, all resources), bound to the connection they were asked on.
- **A world's data**, bound to that world and the connection.

This replaces the one shared batch of `ResourceEdits`, whose rules for which world and connection a reload belongs to had to split it (the second point of [#78](https://github.com/Minecraft-TA/TotalDebug/issues/78)).

### Revert and undo

- **Revert** is a change back to the recorded original, expecting the recorded current value. A value changed outside Companion since is left alone and named, unless it is the original again, which ends the entry. This is what `PackSelections.revert` and `ResourceEdits.revert` do today, for every category.
- **Undo and redo** are changes back and forth along the outcomes of the view that has focus: Ctrl+Z, and Ctrl+Shift+Z or Ctrl+Y, as the configuration table has today, for every category. An undo whose value changed since is refused like any stale change.
- The **Changes page** lists the record through each category's labels and reverts through the pipeline; it no longer knows the kinds.

## Scope

Every edit answers four questions, and the user sees the answers before, at and after the edit, in the same words everywhere.

| Question | Answer from | Examples |
|---|---|---|
| **Where is it written?** | The target's file or the game | the instance's `options.txt`, a world's `serverconfig`, `defaultconfigs`, a resource pack |
| **Whom does it affect?** | The owner | you in this game; everyone who plays a world; worlds created later; everyone on a server |
| **When does it take effect?** | The pipeline's outcome | now, after rejoining the world, after restarting the game, when the game starts, when the world opens |
| **What overrides it?** | The category's reading | a pack above the saved copy supplies the same file; the server's pack is fixed on top; a world already has its own copy of a default |

### The scopes

One word for each place a change can land. The user learns these few, and every category uses them:

| Scope | Written to | Affects |
|---|---|---|
| **This instance** | The instance's files: `options.txt`, `config/`, `resourcepacks/` | This game, and the worlds opened in it where the setting is common |
| **World** and its name | That world's folder: `serverconfig/`, `level.dat`, `datapacks/` | Everyone who plays that world |
| **New worlds** | `defaultconfigs/` | Worlds created from now on; existing worlds keep their own copy |
| **Server** and its address (A2 layer 4) | The server, through the relay | Everyone on that server |

The pipeline derives the scope from the target and its owner; no category words it itself.

### Where the user sees it

- **Before editing:** each editor shows the scope it writes to, where the user looks while editing: the configuration file's header names the copy ("World", then its name as secondary text), a resource tab names the pack it saves into, the key bindings page "This instance". Where the same thing exists in several scopes, such as a world's copy of a server configuration and the defaults for new worlds, the editor offers them as a choice and never picks a wider scope than the one shown.
- **At the edit:** the status line after a change names what changed, where and when it takes effect: "Max chunk size saved to World New World, applies when the world opens". An override follows as its own part: "not used: Faithful above it supplies the same texture".
- **Afterwards:** the Changes page groups changes by scope, then by category. A change that still waits for a rejoin or a restart, or that something overrides, says so as the row's secondary text. A scope's changes are reverted together from its group.
- **Refused:** the refusal names the scope and the requirement, as `GameState` words it: "The world New World is open in another program; close it to change its datapacks".

One component draws a scope and one outcome, so every view shows them alike; [UI_GUIDE.md](UI_GUIDE.md) applies to its text: primary and secondary text, no separators, only what serves the edit.

## Adding a category

A new writing category, such as Game options (B2), then consists of:

1. its target and the text form of its values;
2. reading and writing its file;
3. its handler in the mod's change table;
4. its rows in the UI, which A4 lets it register itself.

It does not edit the pipeline, `ChangesPanel`, `CompanionApplication`, `CompanionAppClient` or the protocol version.

## Order of work

A stack, each layer at most about 500 lines and reviewed on its own:

1. **The pipeline and key bindings**, with `CHANGE` and `CHANGE_RESULT` and the mod's change table. Key bindings gain the stale check. One protocol version.
2. **Pack order** onto it; `SET_PACKS` goes.
3. **Configuration** onto it; the writing and undo in `ConfigWriter` leave the UI, and `ConfigChanges.Effect` becomes the pipeline's.
4. **Resources** onto it, as changes of several targets whose values are hashes.
5. **Reload queues per owner**, replacing the shared batch.
6. **Scope in the views:** the scope component in each editor and status line, and the Changes page grouped by scope without knowing the kinds.

Done when each category keeps only its own reading and writing of values, as the roadmap puts it, and the Changes page names no category.

## Not in A3

- Changes the server owns (datapacks and, if wanted, game rules on a server): the owner exists, A2 layer 3 fills it.
- Whether a change ships with the pack when the instance is exported as a modpack: instance files would, worlds would not. The scope table can carry it later.
- Category registration without editing `CompanionApplication` (A4).

## Decisions open

| Decision | Recommendation |
|---|---|
| Resources inside the pipeline or beside it | Inside, as changes of several targets with hashes as values; beside would leave the largest category out |
| Undo for every category | Yes, per view with focus, as the configuration table has it |
| Values as text or typed | Text in the record and on the wire, typed inside each category |
| The words of the scopes | This instance, World, New worlds, Server, as above |
