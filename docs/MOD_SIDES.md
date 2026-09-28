# The mod's client and server parts

Design for item A2 of the roadmap: the mod's common, client and server parts, with one relay that carries Companion's messages for the server through the client. It builds on [GAME_LOCATION.md](GAME_LOCATION.md), which decides *whether* a change goes to the server; this document decides *how* it gets there and who may make it.

## Why

Today the mod does server work in two ways:

- **Scripts** go the right way: Companion → client → server as NeoForge payloads (`RunServerScriptPayload`, `StopServerScriptPayload`, `ServerSourceRequestPayload`), with results coming back as `ForwardedCompanionPayload`. That already works on remote servers, under `ServerScriptPolicy`.
- **Everything added since** reaches from client code straight into the integrated server with `getSingleplayerServer()`: selecting datapacks and reloading data (`ResourceReloads`), naming the world's datapacks (`PackStackPublisher`) and, in #75, game rules (`GameRuleControl`). None of it works on a remote server, and each feature that follows copies the shortcut.

Each script operation also has its own payload type, so every new server feature needs NeoForge networking code on both sides.

## Three parts

| Part | Where | Holds | May use |
|---|---|---|---|
| Client | `totaldebug.client` | The Companion connection, F6 input, resource packs and assets, key bindings, options, the relay's client end, and the client's mod entry `TotalDebugClientMod` | Client classes; never `getSingleplayerServer()` |
| Server | `totaldebug.server` | Every server operation: scripts and their link check, datapacks, data reloads, game rules, what the server reports about its world, the relay's server end | Server classes |
| Common | Everything else | The mod's entry `TotalDebug`, the script API (`totaldebug.script`, `totaldebug.inspection`), runtime sources, networking contracts, configuration | Neither client classes nor the client part |

- **The parts are a rule, not a move.** Scripts and inspection tools import `ScriptProgram` and the readers by their package, so the script API keeps its packages; moving them would break every saved script.
- **The rule is checked** by `ModPartsTest`, which reads the mod's compiled classes and fails on any class outside `totaldebug.client` that refers to `net.minecraft.client` or to the client part.
- **The client's setup at mod construction** lives in its own entry class, `@Mod(dist = CLIENT)`, which NeoForge constructs only on the client, instead of a distribution check in `TotalDebug`.
- A second check, that no client class calls `getSingleplayerServer()`, is added in layer 3, once the last such calls have moved.

## The relay

Companion addresses the server explicitly, and the game client carries the message without opening it:

```text
Companion ──TO_SERVER{ message }──► client ──ToServer chunks──► server
server ──ToCompanion chunks──► client ──FROM_SERVER{ message }──► Companion
```

| Part | Carries |
|---|---|
| `TO_SERVER` (Companion → client) | A Companion protocol message for the server as its protocol id and encoded body, with a **correlation** (Companion's id for the request, such as a script run, or 0) and a **game session** (the joined world the message is only valid in, or empty) |
| `ToServer` (client → server, NeoForge) | That message's id and body, split into chunks, with the number of the Companion connection that sent it |
| `ToCompanion` (server → client, NeoForge) | A Companion protocol message from the server, the same way, with the number of the Companion connection it answers |
| `FROM_SERVER` (client → Companion) | The server's message, which Companion unwraps and hands to the same listeners as a message from the game |
| `RELAY_FAILED` (client → Companion) | The correlation of a message the client could not deliver, and why |

- **The client relays; it does not interpret.** It checks only the envelope: the game session must be the joined world's (a script aimed at an inspected block is refused once that world was left), and the server must have TotalDebug. Otherwise it answers `RELAY_FAILED`, and Companion fails the request with that correlation. A new server feature needs a Companion message and a server handler, no NeoForge code and nothing in the client.
- **The server speaks the Companion protocol.** It decodes and encodes the same message classes Companion does; `RelayedMessages` lists which travel to and from the server.
- **Chunks** keep each payload below Minecraft's limits: about 32 KiB towards the server and 1 MiB towards the client. A transfer id, index and count put a message back together; an incomplete transfer is dropped when its player leaves. The transfers in progress on one connection share one byte budget, 2 MiB on the server for each player. A relayed message and its envelope fit one frame of the Companion connection, and the server's results are budgeted for it. Script bytecode then has no special 30,000-byte cap.
- **The sender is the player** whose client relayed the message. The server answers only that player's client, and forgets a player's transfers and sessions when they leave.
- **Companion connections are numbered by the client**, which owns them, and whatever the client sends for a connection reaches only that one. The client stamps each chunk towards the server with the connection's number, the server stamps its answers with the number of the connection they answer, and the client drops an answer for an earlier connection. A restarted Companion counts its run ids from the beginning again, so nothing of an earlier connection may reach it. The server keeps one Companion per player: a message from a newer connection ends the runs of the one before, and so does `COMPANION_LEFT`, the one relayed message the client writes itself when Companion's connection closes.
- **Server runs end with the server.** When `PLAYING` no longer names the server Companion asked, Companion fails its runs there. Leaving a server is published at once, so a rejoin between two checks is still a change. Companion counts a result only from the side its run went to.
- **One server operation table** maps each server-bound message to its handler, so the check is made in one place. Layer 4 adds each operation's permission to it.

## Server scripts

Companion compiles a server script against the client's classes, as it compiles a client script. What it needs to know is whether that bytecode links on the server, so the server answers that for each run, from the classes it actually loaded:

- **Access.** When `PLAYING` names a world or a server with TotalDebug, Companion sends `SERVER_SCRIPTS_REQUEST`, and the server answers `SERVER_SCRIPTS`: allowed, or the script policy's reason. Server runs are ready once it allowed them.
- **The link check.** Before a run starts, the server reads the classes, fields and methods its bytecode references, including those Companion's compiler routes through `ScriptAccessLinker` call sites, and resolves each through its own class loader without initializing anything. Those are the classes as loaded, after Mixins and after the classes and members of the other side were stripped, not the files they came from. A reference that does not resolve, or resolves to a member of another kind (static where the script uses an instance member, or the reverse), refuses the run with its name, such as `Not on the server: com.example.Api.pick(int[], int[])`; another overload does not count. The server also defines the script's own classes, so the JVM checks their superclasses and interfaces as the run would.
- **Client classes are refused.** A server script may not refer to `net.minecraft.client`, `net.neoforged.neoforge.client`, `com.mojang.blaze3d` or TotalDebug's client part, even on the integrated server, where they would resolve because it shares the client's JVM.
- **Classes only the server has** cannot be compiled against, since Companion indexes the client. That belongs to direct server access (F2), which will reuse the same check.

The same relay serves the integrated server. A singleplayer game talks to its own server through the in-memory connection, exactly as it would to a remote one, so there is one path to test.

## Server operations

| Operation | Today | After A2 |
|---|---|---|
| Run and stop a script, script access | Own payloads | Relay; the server checks each script's references |
| Select datapacks (`SET_PACKS` data) | Client code, integrated only | Server handler; checks the world it names |
| Reload data (`RELOAD` data) | Client code, integrated only | Server handler; checks the world it names |
| The world's datapacks (`PACK_STACK` data half) | Client code, integrated only | Server reports them for its world |
| Read and set game rules (#75) | Client code, integrated only | Server handler and server report |
| Resource packs, assets, key bindings, options | Client | Client (unchanged) |

- `PACK_STACK` splits into what each part knows: the client names the resource packs, and the server names its world's datapacks with the world's identity.
- **World identity** on a server that is not integrated is not a folder of the instance. The server names its world by the address the client joined and its `level-name`. Requests for such a world name it the same way and are checked the same way.

## Who may change a server's world

| Player | May change the world |
|---|---|
| The owner of a singleplayer world, open to LAN or not | Always, as today: it is their world, and Companion writes its `level.dat` when it is closed anyway |
| Anyone else | With the permission level the server's configuration sets, by default the operator level (`getOperatorUserPermissionLevel()`, usually 2). The same level `/datapack`, `/reload` and `/gamerule` require. |

- The server's configuration gains a switch for world changes beside the existing one for scripts, both off-able by the server owner. `ServerScriptPolicy` becomes the policy for both.
- `PLAYING` already carries the player's permission level, so Companion can answer "you need operator permission on this server" before sending anything. The server checks again; its answer is the one that counts.

## Companion

- `GameState` gains the server's own world as a target: while the game plays on a remote server, a change of that world is **live through the relay** when the server has TotalDebug and the player may change it, and otherwise **refused** with the requirement. It is never files: the server's world is not on this machine.
- The World page shows what the game plays: the singleplayer world as today, or the server's world as the server reports it (its rules and datapacks), with changes made live. With no game, it shows the local world played last.
- **Not in A2:** writing files on a remote server, such as a resource saved into the server's datapack. That needs a server-side file write with its own permission rule and a size limit, and is recorded as a later step.

## Later

- **Direct server access (F2):** a dedicated server opens its own Companion connection. The server operations and their table stay as they are; only the transport changes, from the relay to that connection. The game-owned rows are refused ("this instance has no client").
- **A server instance** as a Companion project finds its world at `level-name` instead of `saves/`, and holds the instance's game lock from its server part.

## Delivery

A stack, each layer at most about 500 lines, each merged before the next opens:

| Layer | Content | Protocol | Tests |
|---|---|---|---|
| 1. Parts | The rule and its test; the client's mod entry; no behaviour change | None | `ModPartsTest`; the existing suites unchanged |
| 2. Relay | `TO_SERVER`/`FROM_SERVER`/`RELAY_FAILED` and the `ToServer`/`ToCompanion` chunks; scripts, the manifest handshake and source requests move onto it; their payloads go | Next version: the envelope messages, `MANIFEST_REQUEST` and `COMPANION_LEFT` | Chunking, player attribution, leave cleanup, a large script beyond 30,000 bytes |
| 3. World operations | Datapack selection, data reload, datapack report and game rules move to the server part, for the integrated server | Next version: server-reported datapacks | Singleplayer behaviour unchanged; no client class calls `getSingleplayerServer()` |
| 4. Remote servers | The permission policy and configuration; `GameState` answers for a remote server's world; the World page for a server | Next version if the report needs the server's identity | Owner, operator and non-operator cases; a server without TotalDebug |

The #75 rework lands before layer 3 and is moved there with the rest.

Between layers 2 and 3, the class manifest handshake was replaced by the per-run link check described under [Server scripts](#server-scripts), with the numbered Companion connections.

## Open decisions

1. **Default for other players on a server:** operator level (as proposed), or off until the server owner turns it on.
2. **The World page on a server:** the server's world, live (as proposed), or the local world played last with the server's world elsewhere.
3. **Remote file writes** (a resource saved into a server's datapack): a later step as proposed, or part of layer 4.
