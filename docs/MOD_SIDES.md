# The mod's client and server parts

Design for item A2 of the roadmap: the mod split into a common, a client and a server part, with one relay that carries Companion's messages for the server through the client. It builds on [GAME_LOCATION.md](GAME_LOCATION.md), which decides *whether* a change goes to the server; this document decides *how* it gets there and who may make it.

## Why

Today the mod does server work in two ways:

- **Scripts** go the right way: Companion → client → server as NeoForge payloads (`RunServerScriptPayload`, `StopServerScriptPayload`, `ServerSourceRequestPayload`), with results coming back as `ForwardedCompanionPayload`. That already works on remote servers, under `ServerScriptPolicy`.
- **Everything added since** reaches from client code straight into the integrated server with `getSingleplayerServer()`: selecting datapacks and reloading data (`ResourceReloads`), naming the world's datapacks (`PackStackPublisher`) and, in #75, game rules (`GameRuleControl`). None of it works on a remote server, and each feature that follows copies the shortcut.

Each script operation also has its own payload type, so every new server feature needs NeoForge networking code on both sides.

## Three parts

| Part | Package | Holds | May use |
|---|---|---|---|
| Common | `totaldebug.common` | Script runtime and value capture, inspection readers, runtime sources, the relay's codec | Neither client nor dedicated-server classes |
| Client | `totaldebug.client` | The Companion connection, F6 input, resource packs and assets, key bindings, options, the relay's client end | Client classes; never `getSingleplayerServer()` |
| Server | `totaldebug.server` | Every server operation: scripts, class manifest, datapacks, data reloads, game rules, what the server reports about its world, the relay's server end | Server classes only |

The rule is checked by a test that reads the compiled classes of `server` and `common` and fails on any reference to `net.minecraft.client`, and on any call to `getSingleplayerServer` from `client`. The mod keeps one entry point; the client part is still created only on the client distribution.

## The relay

One pair of NeoForge payloads replaces the per-feature ones:

| Payload | Direction | Carries |
|---|---|---|
| `ToServer` | client → server | A Companion protocol message addressed to the server, as its protocol id and encoded body, split into chunks |
| `ToCompanion` | server → client | A Companion protocol message from the server, the same way |

- **The client relays; it does not interpret.** Companion marks a message as addressed to the server by its protocol id: the protocol lists which messages belong to the server, as it lists each message's direction today. The client forwards those unread and hands back what the server sends. A new server feature therefore needs a Companion message and a server handler, no NeoForge code.
- **Chunks** keep each payload below Minecraft's limits: about 32 KiB towards the server and 1 MiB towards the client. A transfer id, index and count put a message back together; an incomplete transfer is dropped when its player leaves. Script bytecode then has no special 30,000-byte cap.
- **The sender is the player** whose client relayed the message. The server answers only that player's client, and forgets a player's transfers and sessions when they leave, as `ServerScriptService` already does.
- **Availability** is known before a request is sent: the server's TotalDebug channel is what `PLAYING` reports as `totalDebug`. Without it, server-owned changes are refused with "the server does not have TotalDebug".
- **One server operation table** maps each server-bound message to its handler and to the permission it needs, so the check is made in one place.

The same relay serves the integrated server. A singleplayer game talks to its own server through the in-memory connection, exactly as it would to a remote one, so there is one path to test.

## Server operations

| Operation | Today | After A2 |
|---|---|---|
| Run and stop a script, class manifest, source details | Own payloads | Relay |
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
| 1. Parts | Move the classes into `common`, `client` and `server`; no behaviour change | None | The dependency test; the existing suites unchanged |
| 2. Relay | `ToServer`/`ToCompanion` with chunks; scripts, manifest and source requests move onto it; their payloads go | None (Companion messages unchanged) | Chunking, player attribution, leave cleanup, a large script beyond 30,000 bytes |
| 3. World operations | Datapack selection, data reload, datapack report and game rules move to the server part, for the integrated server | Next version: server-reported datapacks | Singleplayer behaviour unchanged; no client class calls `getSingleplayerServer()` |
| 4. Remote servers | The permission policy and configuration; `GameState` answers for a remote server's world; the World page for a server | Next version if the report needs the server's identity | Owner, operator and non-operator cases; a server without TotalDebug |

The #75 rework lands before layer 3 and is moved there with the rest.

## Open decisions

1. **Default for other players on a server:** operator level (as proposed), or off until the server owner turns it on.
2. **The World page on a server:** the server's world, live (as proposed), or the local world played last with the server's world elsewhere.
3. **Remote file writes** (a resource saved into a server's datapack): a later step as proposed, or part of layer 4.
