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

## Progress matrix

| ID | Area | State | Current evidence / next gate |
|---|---|---|---|
| S0 | Clean NeoForge foundation | Complete | Single-module MDK; `gradlew build` passes; commits `7504de6`, `bcbd0f3` |
| F1 | Mod core and tick task lifecycle | Complete | Version identity, isolated client/server pre/post queues, lifecycle cleanup, and unit tests |
| F2a | Configuration | Complete | Separate client/server specs; retained defaults and packet-class validation are tested |
| F2b | Minecraft networking | Not started | Establish typed payload registration; payloads remain owned by their feature slices |
| F2c | Core resources and libraries | Not started | Convert core language resources and verify bundled dependency metadata |
| F3 | Runtime class and bytecode access | Research | Prove loaded-class enumeration, runtime bytes, JAR inventory, and compiler classpath separately |
| F4 | Companion application IPC and lifecycle | Not started | First core-flow dependency after the F3 gate |
| F5 | Class decompilation | Not started | First core-flow target: decompile one named class and open it in the companion |
| F6 | Live reference search | Not started | Depends on the relevant F3 capabilities |
| F7 | Persistent class index | Not started | Windows-only jindex is accepted initially |
| F8 | Java scripting | Not started | Requires permission, cancellation, compiler, and class-definition redesigns |
| F9 | Decompile commands | Not started | Start with `class`; assess `eventlistener` separately |
| F10 | Code-view keybind | Not started | Add after the named-class decompile flow works |
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
| S3 | F2b typed networking foundation | Codec/registration tests where practical, `gradlew build`, client/server connection | Planned |
| S4 | F2c language resources and bundled core libraries | Resource validation, dependency report, final JAR inspection, `gradlew build` | Planned |

## Core-flow target

The first end-to-end feature slice after F1-F3 is deliberately narrow:

1. Connect to the companion application.
2. Obtain the bytecode for one explicitly named class.
3. decompile it to a source file;
4. tell the companion application to open the file;
5. expose the flow through the `class` decompile command.

The look-at/hover keybind, additional command targets, search, indexing, and scripting follow only after this path works.

## Verification log

| Date | Slice | Result |
|---|---|---|
| 2026-08-22 | S0 | `gradlew build` passed on Java 21; runtime client/server smoke checks remain to be recorded with S1 |
| 2026-08-22 | S1 | 4 scheduler tests and `gradlew build` passed; client completed resource loading; dedicated server reached `Done` without client-class loading errors |
| 2026-08-22 | S2 | 3 config tests and `gradlew build` passed; generated client TOML contains the retained client defaults |
