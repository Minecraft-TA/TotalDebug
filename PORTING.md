# TotalDebug 1.21.1 port

This file is the audit ledger for the NeoForge port. A row is only marked complete when its implementation and listed verification gates are complete. Legacy sources under `legacy/` are references, not production source sets.

Small commits use focused unit tests and `gradlew build`. Expensive client/server smoke runs happen once at a phase boundary or when a change touches runtime-only behavior, not after every foundation commit.

## Fixed decisions

- Target Minecraft 1.21.1 and NeoForge 21.1.x in a single module.
- Keep the mod buildable after every implementation slice.
- Use the local 1.7.10 tree as the primary behavioral reference and consult 1.12.2 where it has a demonstrably better implementation.
- Restore the core workflow before optional diagnostics or integrations.
- Do not recreate the legacy sided-proxy hierarchy.
- Do not carry runtime MCP/SRG remapping into the Mojmap runtime.
- Do not probe unrelated fallback mechanisms to hide unknown behavior. Establish the authoritative mechanism, verify it, and fail explicitly when it is unavailable.

## Progress matrix

| ID | Area | State | Current evidence / next gate |
|---|---|---|---|
| S0 | Clean NeoForge foundation | Complete | Single-module MDK; `gradlew build` passes; commits `7504de6`, `bcbd0f3` |
| F1 | Mod core and tick task lifecycle | Complete | Version identity, isolated client/server pre/post queues, lifecycle cleanup, and unit tests |
| F2a | Configuration | Complete | Separate client/server specs; retained defaults and packet-class validation are tested |
| F2b | Minecraft networking | Complete | Optional protocol v1 registration, bounded typed companion-forward payload, and receiver lifecycle are tested; remaining payloads stay feature-owned |
| F2c | Core resources and libraries | Complete | Core language JSON is tested; SCNet, ClassGraph, Vineflower, and jindex are pinned and present in Jar-in-Jar metadata |
| F3a | Named-class bytecode access | Complete | Defining-loader bytes, including nested target classes, decompile Java 21 test code and Minecraft classes through Vineflower's in-memory API |
| F3b | Transformed bytes and class inventory | Research | Post-transform capture, loaded-class enumeration, JAR inventory, and compiler classpath remain separate proofs |
| F4 | Companion application IPC and lifecycle | Complete | Companion 1.9.1 install/start/connect/ready/open flow passed live; SCNet fork `b763bf8` supplies caller-owned message factories across named modules |
| F5 | Class decompilation | Complete | The live Companion flow is restored; Vineflower now supplies the sole production decompiler and its `GrassBlock` output preserves the bounded bonemeal loops |
| F6 | Live reference search | Not started | Depends on the relevant F3 capabilities |
| F7 | Persistent class index | Complete | Runtime inputs plus JDK modules produce an atomic 6.7 MB index; Companion's jindex 0.0.45 loaded the fork-produced format; unchanged development classes are content-fingerprinted |
| F8 | Java scripting | Not started | Requires permission, cancellation, compiler, and class-definition redesigns |
| F9 | Core decompile command | Complete | `/decompile block <id>` has registry suggestions, rejects unknown IDs, and opened `LeverBlock.java` live; other legacy command targets remain future slices |
| F10 | Code-view keybind | Complete | One F6 press resolves a looked-at block or entity and a hovered GUI item; live gates passed for block, cow, and multiple block items without repeat flooding |
| F11 | Packet logger | Not started | Requires a separate packet-pipeline hook proof |
| F12 | Packet blocker | Not started | Port with F11 |
| F13 | Chunk grid | Not started | Rebuild against modern chunk tickets and dimension identifiers |
| F14 | Tick Block | Deferred | Not part of the current core-restoration phase |
| F15 | TPS/MSPT tab display | Deferred | Not part of the current core-restoration phase |
| F16 | Boss-bar suppression | Dropped | Do not add the legacy `renderBossBar` option |
| F17 | Config screen | Optional | Revisit after retained config is functional |
| F18 | GT/NEI/JEI integrations | Deferred | GT and NEI are dropped; JEI may return with F10 |
| F19 | NEI recipe export | Dropped | Depends on obsolete NEI/GT APIs |
| F20 | Fake players | Optional | Independent late feature |

## Auditable slices

| Slice | Scope | Required verification | State |
|---|---|---|---|
| S0 | Clean NeoForge build and searchable legacy references | `gradlew build` | Complete |
| S1 | F1 mod identity, sided tick lifecycle, and task queues | Unit tests, `gradlew build`, client launch, dedicated-server launch | Complete |
| S2 | F2a retained NeoForge configuration | Config tests, `gradlew build`, generated client config inspection | Complete |
| S3 | F2b typed networking foundation | Codec and receiver-lifecycle tests, `gradlew build`; connection smoke is deferred to the next runtime phase boundary | Complete |
| S4 | F2c language resources and bundled core libraries | Resource validation, final JAR inspection, `gradlew build` | Complete |
| S5 | F4 Companion distribution, SCNet IPC, and lifecycle | Installer and wire-format tests, SCNet fork tests, `gradlew build`, live connect/ready | Complete |
| S6 | F7 runtime class index | jindex fork tests, Java 21 index round-trip, explicit 0.0.45 reader compatibility, live NeoForge index build | Complete |
| S7 | F5/F9 named-class decompile and block command | Unit tests, `gradlew build`, live `/decompile block minecraft:lever` opening in Companion | Complete |
| S8 | F10 F6 target flow | One-request-per-press behavior; live block, entity, and hovered block-item opens in Companion | Complete |
| S9 | Java 21 decompiler modernization | In-memory Vineflower adapter, modern Java recompilation fixture, `Block` coverage, `GrassBlock` control-flow regression, `gradlew build` | Complete |

## Core-flow milestone

The first end-to-end milestone after F1-F3 is complete:

1. Build or validate the runtime class index required by Companion.
2. Install, start, connect to, and wait for Companion 1.9.1.
3. Obtain authoritative runtime bytes from the target class's defining loader.
4. Decompile the class to the persistent source directory.
5. Tell Companion to open the source file.
6. Expose the flow through `/decompile block <id>` and F6 block/entity/item targeting.

Live reference search, additional command targets, and scripting remain separate future slices.

## Verification log

| Date | Slice | Result |
|---|---|---|
| 2026-08-22 | S0 | `gradlew build` passed on Java 21; runtime client/server smoke checks remain to be recorded with S1 |
| 2026-08-22 | S1 | 4 scheduler tests and `gradlew build` passed; client completed resource loading; dedicated server reached `Done` without client-class loading errors |
| 2026-08-22 | S2 | 3 config tests and `gradlew build` passed; generated client TOML contains the retained client defaults |
| 2026-08-22 | S3 | Typed payload codec and receiver-lifecycle tests passed; protocol is optional so client-only installs remain possible; no redundant game launch |
| 2026-08-22 | S4 | All 14 tests and `gradlew build` passed; the final JAR contains the language JSON plus five nested library JARs and valid Jar-in-Jar metadata |
| 2026-08-22 | F3a | Named-class byte lookup tests passed; Procyon successfully decompiled a Java 21 fixture and Minecraft's 1.21.1 `Block` class |
| 2026-08-22 | S5 | SCNet fork `b763bf8` passed its tests and fixed named-module message construction through caller factories; Companion 1.9.1 accepted the live connection and ready handshake |
| 2026-08-22 | S6 | JIndex fork `f314662` passed its clean build; its native loader works under NeoForge `union:` resources; an isolated jindex 0.0.45 process loaded a fork-produced index; NeoForge built 118 JAR inputs into the live index |
| 2026-08-22 | S7 | `/decompile block minecraft:lever` decompiled the runtime `LeverBlock` bytes and Companion opened `LeverBlock.java` |
| 2026-08-22 | S8 | A single run opened `GrassBlock.java`, `Cow.java`, `RotatedPillarBlock.java`, `DoorBlock.java`, and `LeverBlock.java`; one physical F6 press produced one request |
| 2026-08-22 | Core flow | `gradlew clean build` passed 25 tests; the final mod JAR contains `SCNet-b763bf8.jar` and `JIndex-f314662.jar` |
| 2026-08-22 | S9 | Vineflower 1.12.0 slim replaced both Procyon artifacts; the in-memory adapter passed modern Java 21 recompilation, Minecraft `Block`, and `GrassBlock` bounded-loop regressions |

## Foundation dependency decisions

- Keep SCNet and use fork commit `b763bf8`. The wire protocol is unchanged; caller-provided message factories make construction legal across named modules.
- Use Vineflower 1.12.0 slim as the only production decompiler. The adapter supplies defining-loader bytes directly through `IContextSource`, captures warning/error diagnostics through `IFernflowerLogger`, rejects missing or partial output, and fixes the incorrect `GrassBlock.performBonemeal` loop reconstruction observed with Procyon.
- Keep ClassGraph for later class discovery and completion work. It is not used to enumerate the NeoForge runtime because version 4.8.146 cannot enumerate the Java 21 module readers supplied by ModLauncher.
- Use JIndex fork commit `f314662`. It loads its bundled native DLL from a resource stream under NeoForge and produces an index format proven readable by Companion's jindex 0.0.45. This slice remains Windows-only.
- Use the JDK HTTP client when F4 restores companion downloads; Apache HttpClient is not retained.
