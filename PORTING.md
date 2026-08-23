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
- Defer exact post-transform byte capture and process-wide loaded-class enumeration. ModLauncher's public loaded-class method only answers one known name, while its internal bytecode method reruns transformation instead of returning the bytes already defined by the JVM. Revisit this only for an explicit runtime-transformed-source feature with authoritative launch-time capture.

## Progress matrix

| ID | Area | State | Current evidence / next gate |
|---|---|---|---|
| S0 | Clean NeoForge foundation | Complete | Single-module MDK; `gradlew build` passes; commits `7504de6`, `bcbd0f3` |
| F1 | Mod core and tick task lifecycle | Complete | Version identity, isolated client/server pre/post queues, lifecycle cleanup, and unit tests |
| F2a | Configuration | Complete | Separate client/server specs; retained defaults and packet-class validation are tested |
| F2b | Minecraft networking | Complete | Optional protocol v1 registration, bounded typed companion-forward payload, and receiver lifecycle are tested; remaining payloads stay feature-owned |
| F2c | Core resources and libraries | Complete | Core language JSON is tested; SCNet 2.0.0, ClassGraph 4.8.193, Vineflower 1.12.0, and JIndex 1.0.0 resolve from versioned coordinates and are present in Jar-in-Jar metadata |
| F3a | Named-class bytecode access | Complete | Defining-loader bytes, including nested target classes, decompile Java 21 test code and Minecraft classes through Vineflower's in-memory API |
| F3b | Transformed bytes and class inventory | Deferred after inventory | The runtime-source inventory is complete and powers the class-name catalog, Companion index, and live-script compiler classpath; exact post-transform capture and process-wide loaded-class enumeration are deferred because the available no-agent hooks do not expose authoritative loaded bytes |
| F4 | Companion application IPC and lifecycle | Complete | Companion 2.0 is Java 21 jar-only; each game process launches and authenticates its exact IPv4-loopback child through protocol v2; cold launch, handshake, ready, and open passed live |
| F5 | Class decompilation | Complete | The live Companion flow is restored; Vineflower now supplies the sole production decompiler and its `GrassBlock` output preserves the bounded bonemeal loops |
| F6 | Live reference search | Engine complete; user flow pending | Typed class, field, and method queries scan the runtime inventory without class loading, resolve inherited member owners, and return class/member sites; references introduced only by runtime transformation remain deferred with F3b |
| F7 | Persistent class index | Complete | Runtime inputs plus JDK modules produce an atomic index; JIndex 1.0.0 now guards native lifetime, retained child objects, and concurrent close while keeping the runtime format working |
| F8 | Java scripting | Complete | Client and server runs share the Java 21 compiler/runner; optional negotiated run/stop payloads, per-player server ownership, config/operator policy, bounded status forwarding, disconnect cleanup, and unsupported-server refusal are covered, and server execution passed live |
| F9 | Core decompile command | Complete | Block, item, entity, and block-entity IDs resolve through their exact registries; class names use the mod's defining loader without initialization and have cached package-aware completion; the expanded command and completion passed live |
| F10 | Code-view keybind | Complete | One F6 press resolves a looked-at block or entity and a hovered GUI item; live gates passed for block, cow, and multiple block items without repeat flooding |
| F11 | Packet logger | Not started | Requires a separate packet-pipeline hook proof |
| F12 | Packet blocker | Not started | Port with F11 |
| F13 | Chunk grid | Not started | Rebuild against modern chunk tickets and dimension identifiers |
| F14 | Tick Block | Deferred | Not part of the current core-restoration phase |
| F15 | TPS/MSPT tab display | Deferred | Not part of the current core-restoration phase |
| F16 | Boss-bar suppression | Dropped | Do not add the legacy `renderBossBar` option |
| F17 | Config screen | Optional | Revisit after retained config is functional |
| F18 | GT/NEI/JEI integrations | Complete | GT and NEI remain dropped; optional JEI 19 integration resolves F6 targets from the ingredient list, bookmarks, recipe screens, and registered GUI handlers before the normal container-slot fallback; the user confirmed the integration live |
| F19 | NEI recipe export | Dropped | Depends on obsolete NEI/GT APIs |
| F20 | Fake players | Optional | Independent late feature |

## Modernization matrix

| ID | Area | State | Evidence / remaining gate |
|---|---|---|---|
| M1 | Companion Java 21 runtime | Complete | Companion builds as a non-preview Java 21 fat JAR; the mod launches it with the exact current Minecraft Java after validating the executable, version, and required modules |
| M2 | Decompiler engine | Complete | Vineflower 1.12.0 is the only engine; modern Java 21, nested-class, Minecraft `Block`, and `GrassBlock` control-flow fixtures pass with no fallback |
| M3 | Companion editor stack | Complete | JDT 3.46 parses at JLS 21/compliance 21; FlatLaf 3.7.2, RSyntaxTextArea 4.0.1, Autocomplete 3.3.3, Gson 2.14.0, and Commons Compress 1.28.0 are pinned |
| M4 | SCNet transport | Complete | SCNet 2.0.0 has bounded framing, partial read/write handling, selector wakeups, explicit lifecycle state, reconnect/close coverage, deterministic drain-before-close, and 59 passing tests |
| M5 | JIndex native lifecycle | Complete | JIndex 1.0.0 serializes close against JNI calls, invalidates retained children exactly, pins Rust/native inputs, and passes Java plus Rust tests |
| M6 | Versioned dependency supply | Complete locally | Default Gradle and IntelliJ builds resolve SCNet 2.0.0 and JIndex 1.0.0 from Maven Local through `com.github.tth05`; external Packagecloud publication waits for approval |
| M7 | Companion session protocol | Complete | Loopback ephemeral endpoints, atomic descriptors, exact child PID checks, per-launch tokens, strict protocol/capability handshake, stable IDs, terminal disconnect ownership, deterministic rejection delivery, restart, and unsupported-feature gating are tested |
| M8 | Client package/lifecycle cleanup | Complete | Client setup now assembles one explicit runtime; static NeoForge adapters delegate into lifecycle, tick, input, command, decompile, and Companion ownership; JDT element numbers exist only at the Companion wire boundary |
| M9 | Modernized live acceptance | Complete | Cold launch, warm reuse, block/entity/hovered block item/spawn egg F6, one-request-per-press, reverse Ctrl-click navigation, graceful close, and fresh-session relaunch passed live |
| M10 | Launch and dependency policy ownership | Complete | SCNet requires explicit factories for received messages and uses Java 21 throughout build and CI; mirrored launch constants and injectable timing policies replace scattered literals; immutable Companion release metadata and checksum are generated from Gradle properties |

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
| S10 | Java 21 Companion and dependency modernization | Companion parser tests; SCNet/JIndex clean builds; versioned Maven-local consumer builds; artifact inspection | Complete locally |
| S11 | Per-process Companion protocol | Golden wire fixtures, descriptor/auth/version rejection tests, both application builds, then one live core-flow run | Complete |
| S12 | Behavior-neutral client cleanup | Unit tests, package/lifecycle review, `gradlew build`, no extra game launch | Complete |
| S13 | Explicit construction and launch policy | SCNet, Companion, and TotalDebug clean builds on Java 21; received-message factory tests; mirrored launch-contract tests; generated release metadata and final JAR inspection | Complete |
| S14 | Project metadata cleanup | Static project resources, two development runs, wrapper metadata, 44 tests, and final JAR inspection | Complete |
| S15 | F9 command target expansion | Command-tree, completion, and resolution-policy tests, `gradlew build`, then one live item/entity/block-entity/class smoke | Complete |
| S16 | F8a client-side Java scripting | Mirrored golden wire fixtures, compiler/classloader/runner tests, clean TotalDebug and Companion builds, then live THREAD/PRE_TICK/POST_TICK, diagnostics, cancellation, and server-rejection checks | Complete |
| S17 | F8b server-side Java scripting | Optional payload codecs and channel gates, policy/status/disconnect tests, per-session ownership review, `gradlew test build`, then one live server-side run through Companion | Complete |
| S18 | F18 JEI code-view integration | JEI API resolver priority and lifecycle tests, optional-dependency artifact inspection, `gradlew test build`, then one live ingredient-list/bookmark/recipe-screen F6 smoke | Complete |
| S19 | F6 reference-search engine | Exact bytecode fixtures for class, field, method, inheritance, annotations, signatures, handles, archive selection, virtual Jar-in-Jar paths, progress, cancellation, and failures; `gradlew test build`; no command, protocol, or UI work | Complete |

## Core-flow milestone

The original end-to-end milestone after F1-F3 and its modernized Companion 2.0 core transport are complete:

1. Build or validate the runtime class index required by Companion.
2. Install, start, authenticate with, and wait for the pinned Companion 2.0 child process.
3. Obtain authoritative runtime bytes from the target class's defining loader.
4. Decompile the class to the persistent source directory.
5. Tell Companion to open the source file.
6. Expose the flow through `/decompile block|item|entity|blockentity <id>`, `/decompile class <binary-name>`, and F6 block/entity/item targeting.

Live reference search remains a future slice. Exact transformed-byte capture and process-wide loaded-class enumeration are deferred until they justify an authoritative launch-time capture component.

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
| 2026-08-22 | S10 | Companion moved to Java 21/JDT 3.46 and jar-only packaging; SCNet passed 59 transport tests; JIndex passed 6 Java and 11 Rust tests; both applications built against Maven-local SCNet 2.0.0 and JIndex 1.0.0 |
| 2026-08-22 | S11 | TotalDebug passed 40 tests and Companion passed 13; mirrored golden handshake bytes, atomic descriptors, exact PID checks, token/version rejection delivered before EOF, terminal disconnect ownership, capability gates, sequential child restart, explicit IPv4 loopback ownership, and JDT `codeSelect` are covered; a clean Companion rebuild reproduced the pinned SHA-256 exactly |
| 2026-08-22 | S12 | TotalDebug passed 39 tests and a warning-free clean build; F6 open-or-focus decisions and Companion source-target conversion have direct tests; the final JAR retains SCNet, ClassGraph, JIndex, and Vineflower Jar-in-Jar entries; no game was launched |
| 2026-08-22 | Assembly | Clean builds passed for all four repositories; the mod JAR embeds SCNet 2.0.0, JIndex 1.0.0, Vineflower 1.12.0 slim, and ClassGraph 4.8.193; Companion is a 21.2 MB Java 21 JAR with no private JRE, Discord, or Procyon payload |
| 2026-08-22 | M9 live | Cold F6 built the runtime index, launched and authenticated the exact Companion child, and opened `GrassBlock.java`; the same session opened `Cow.java`, `RotatedPillarBlock.java`, and `Allay.java` from entity and hovered-item targets with one request per press; Ctrl-clicking `Animal` sent the reverse request and opened `Animal.java` |
| 2026-08-22 | M9 fixes | Live testing exposed NeoForge IPv6 preference versus Companion IPv4 loopback selection and JDT 3.46's stricter synthetic-project contract; both now have red-to-green regression tests, explicit contracts, clean builds, and no fallback path |
| 2026-08-22 | M9 restart | Closing the authenticated Companion ended its owned process; the next F6 launched a new session with a new PID, reused the warm runtime index, and opened `GrassBlock.java` in about two seconds |
| 2026-08-22 | S13 | SCNet passed 59 tests and published Java 21 classfiles to Maven Local; Companion passed 15 tests and reproducibly built SHA-256 `c7f6bf3f63e918aae939f83ddbae68cf2fad904162a387db779f484ea893ea8a`; TotalDebug passed 44 tests and embedded the matching release manifest plus SCNet 2.0.0, JIndex 1.0.0, Vineflower 1.12.0 slim, and ClassGraph 4.8.193 |
| 2026-08-22 | S14 | Removed the MDK template and generated-resource paths, unused data/GameTest runs, empty test roots, and default logging noise; `gradlew clean build` passed 44 tests and the final JAR contains the expected manifest version, NeoForge metadata, Companion checksum, and four Jar-in-Jar libraries |
| 2026-08-22 | S15 | Added exact-registry block, item, entity, and block-entity targets, non-initializing binary-class lookup, and one-level package/class completion backed by a lazy cached runtime inventory; focused tests and `gradlew build` passed all 46 tests; the user's active client held the generated NeoForge JARs open, so the final clean and consolidated live gate remain |
| 2026-08-22 | S16 | The combined TotalDebug tree passed 65 tests and Companion passed 19 in clean builds. With the development Companion JAR, THREAD execution and log output, javac diagnostics, PRE_TICK and POST_TICK execution, cooperative Stop behavior, and explicit refusal of server-side execution passed live. The compiler resolved 115 runtime sources under Java 21; Minecraft's `net.minecraft.world.level.block` package was both exported and open to the unnamed script module. |
| 2026-08-23 | S15 live | The expanded `/decompile` command and its class completion passed in the live client, closing the remaining F9 runtime gate. |
| 2026-08-23 | S17 | Added bounded optional serverbound run/stop payloads, whitelisted clientbound status forwarding, local unsupported-server gating, per-session player runners, config/operator enforcement, and logout/server-stop cleanup. TotalDebug passed all 95 tests and `gradlew test build`; the user confirmed a server-side script run through Companion live. A concurrent live client held NeoForge's generated jars open, so the redundant `clean` task could not delete them. |
| 2026-08-23 | S18 | Added optional JEI 19.27.0.340 integration through its public plugin/runtime APIs. Resolver priority covers the ingredient list, bookmarks, recipe and plugin screen ingredients, then the existing container slot. All 102 tests and `gradlew test build` passed; the final TotalDebug JAR declares JEI as an optional client dependency and does not embed it. The user confirmed the integration live, closing the slice. |
| 2026-08-23 | S19 | Added a UI-independent ASM reference-search engine over physical runtime class directories and runtime-selected multi-release archives. Exact typed queries cover class, field, and method declarations; member searches resolve inherited owners without reflection and stop at class and interface overrides. Results retain class, field, method, or record-component sites. The engine supports bounded parallel scans, phased progress, cooperative cancellation, NeoForge virtual Jar-in-Jar sources, and exact source failures. All 113 tests and `gradlew test build --warning-mode fail` passed. Commands, Companion transport, and UI remain deliberately unwired. |

## Foundation dependency decisions

- Keep SCNet as the transport and publish the hardened 2.0.0 artifact under `com.github.tth05`. TotalDebug owns authentication, capabilities, and application message semantics.
- Use Vineflower 1.12.0 slim as the only production decompiler. The adapter supplies defining-loader bytes directly through `IContextSource`, captures warning/error diagnostics through `IFernflowerLogger`, rejects missing or partial output, and fixes the incorrect `GrassBlock.performBonemeal` loop reconstruction observed with Procyon.
- Use ClassGraph 4.8.193 once per process for class-name metadata over the explicit runtime source list already used by the Companion index. Read Java 21 module names directly from the `jrt:/` filesystem. This avoids relying on ModLauncher's module-reader discovery and avoids the legacy scan on every Tab press.
- Use JIndex 1.0.0 under `com.github.tth05`. It loads its bundled native DLL from a resource stream under NeoForge, protects native lifetime across root and child wrappers, and remains Windows-only.
- Use the JDK HTTP client when F4 restores companion downloads; Apache HttpClient is not retained.
