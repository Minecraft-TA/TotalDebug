# Where the game is

Companion changes and reads a Minecraft instance in several situations: with no game running, with a game running that is not connected, with a connected game in its menu, in a singleplayer world or on a server. Each situation decides whether a change can be applied live, written into a file, or has to be refused. This document fixes one model of those situations, which every part of Companion asks instead of looking at locks and connections itself. It is item A1 of the [roadmap](ROADMAP.md).

## What there is

- **The instance:** the game directory Companion's project belongs to, with its mods, configuration, `options.txt`, resource packs and saved worlds. Companion can always read and write its files.
- **The game:** the Minecraft process running in that directory. There is at most one: the mod holds the instance's game lock while it runs, and a second game in the same directory would fail to take it.
- **The server the game plays on:** none while it is in its menu; the integrated server of a singleplayer world, which runs inside the game and whose world is a folder of the instance; or a remote server, whose world lies on another machine.

## What Companion knows, and from what

| Fact | Evidence | Needs |
|---|---|---|
| A game runs in the instance | The game lock is held (`total-debug/game.lock`). A lock that cannot be checked counts as held. Companion checks it by taking it for a moment, so the game retries its lock for a second when it starts. | Nothing; blocking file check |
| The game is connected | The authenticated session of the current project | Connection |
| Which process the game is | The debug target the game announces | Connection |
| What the game is playing | `PLAYING`, sent by the game whenever it changes and after it connects: its menu, a singleplayer world by its folder, or a server by its address | Connection |
| A world is held | The world's `session.lock` is held. This says that some program has the world open, not which one. | Nothing; blocking file check |

A world held while the game plays another, or while no game runs, is open in another program, such as a world editor.

## States

The project's `GameLocation` combines these into one state:

| Game | Playing | Meaning |
|---|---|---|
| Closed | | No game runs in the instance |
| Running, not connected | The world whose `session.lock` is held, if any | The game runs; Companion cannot talk to it |
| Connected | Not told yet | Connected a moment ago; `PLAYING` has not arrived. A held world is taken as the one it has open, but not changed live |
| Connected | Menu | No world is open |
| Connected | Singleplayer: world folder, open to LAN or not | The integrated server runs that world of the instance |
| Connected | Multiplayer: address, Realms or not, whether the server has TotalDebug, the player's permission level | The world is on another machine |

Pushed facts (connection, process, playing) change the state at once and tell listeners; what the game tells before Companion takes its connection as established is kept for it. File facts are read when the state is read: the game lock at once, the worlds' locks by the queries that need them. A decision made in the project's write queue therefore sees the files as they are then. Reading the state is blocking; the Swing thread never reads it.

## Who owns what

Each thing Companion changes belongs to one side, which decides how it can change:

| Owner | What | Applies |
|---|---|---|
| The game (client) | `options.txt`: key bindings, the resource pack selection, game options. Resource packs and their assets. Client configuration. | Live in the connected game; otherwise when the game starts |
| Startup | Common and startup configuration, the mods | When the game starts; a running game needs a restart |
| A world's server | Its `level.dat` (game rules, datapack selection), its `serverconfig`, its datapacks and their data | Live in the server that has the world open; otherwise when the world opens |
| New worlds | `defaultconfigs` | When a world is created |

## Access

A category asks the state how a change of what it owns can be made, and receives one of three answers: **live** through the connected game, **files**, or **refused** with the exact requirement. The refusal names the situation and what to do, followed by the purpose the category gives, such as "to change its resource packs".

**Owned by the game:**

| Game | Answer |
|---|---|
| Connected | Live |
| Running, not connected | Refused: "The game is running but not connected to Companion; connect it, or close it, to …", since the game writes `options.txt` over Companion's copy |
| Closed | Files |

**Owned by a world's server,** for a world of the instance:

| The world | Answer |
|---|---|
| Played in the connected game (singleplayer) | Live |
| Held, connected game plays nothing yet (not told yet) | Refused: the game has not said which world it plays yet |
| Held, game running but not connected | Refused: "The world … is open in a game that is not connected to Companion; connect it, or close the world, to …" |
| Held, while the game plays something else or no game runs | Refused: the world is open in another program |
| Not held | Files, whatever the game does |

A world of the instance is never live while the game plays on a remote server: that server's world is on its own machine, and the instance's worlds are closed files.

**The current world** is the world the game has open (the one it plays in singleplayer, or the held one of a game that is not connected), otherwise the world played last. Pages that show "the current world", and data written into a world's datapack, follow it.

## Rules for the rest of Companion

- No category checks the game lock, a `session.lock` or the connection itself. It asks `GameLocation`. The file primitives keep their own last guard: `LevelDat` never writes a held world.
- A live answer's connection sends only on the connection it was given for. Once that connection has ended its sends fail, so a message never goes to a game that connected since.
- A category that waits for the game's answers listens to `GameLocation` to fail them when the game disconnects.

## One side type

Where something runs, a script or a code-mode job, is `Side`: `CLIENT` or `SERVER`, in the protocol. It replaces the separate enums of scripts, snippets and code-mode jobs and the `serverSide` flags passed between them. The wire format of a run request is unchanged.

## Later

The model already names what these need; each is its own item.

- **Server changes on a remote server (A2):** the multiplayer state carries whether the server has TotalDebug and the player's permission level. Live server-owned changes go through the relay to that server and need permission level 2, as `/gamerule` does. Until then a remote server's own world is refused with that requirement.
- **Direct server access (F2):** a dedicated server connects to Companion itself. Its game is then a server process, not a client: the game-owned rows are refused ("this instance has no client"), and its worlds are live through that connection.
- **Server instances:** a project for a dedicated server's directory keeps its world at `level-name` from `server.properties` instead of `saves/`, and has no `options.txt`. The state is the same; where worlds are found is a property of the instance.
- **The status bar** can name what the game plays, such as the singleplayer world or the server's address, from the same state.
