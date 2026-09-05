# Single-player world-load freeze

Captured from Minecraft PID 66528 on 2026-09-05, approximately 21:27-21:34 Europe/Berlin. This is a later JVM than the successful multiplayer acceptance run.

- G1 heap capacity: 8,388,608 KiB. Used: 8,374,893 KiB.
- Six GC samples show old-generation occupancy between 99.78% and 100%. Full collections increase from 77 to 79 while cumulative full-GC time increases from 163.525 to 167.932 seconds. These samples establish severe GC pressure, not a leak by themselves.
- Two server-thread dumps, about 42 seconds apart, are in WorldEdit startup. The first builds `BlockState.populate` / `generateStateMap` through Guava immutable tables. The second converts WorldEdit states to Minecraft states in `Capability.ready`.
- The histogram collected with `GC.class_histogram -all` contains 1,436,475 WorldEdit BlockState objects, 1,397,763 SparseImmutableTable objects, 22,221,400 SingletonImmutableBiMap objects and 39,102,637 ImmutableMapEntry objects. Counts include unreachable objects; they are not a retained-heap dominator analysis.
- The game log reports Crystalix block types with 5,120 states taking 6.4, 17.0 and 31.6 seconds to generate, followed by slow state generation for other mods. Spark repeatedly times out waiting for world statistics.
- Installed WorldEdit is `worldedit-mod-7.3.8.jar`, SHA-256 `5e7752c97876d87411e3760bcc573cc431f43c453722e6959fa7fe54db1b01ca`. Installed bytecode was inspected with javap. The corresponding algorithm generates the Cartesian product of block properties and a neighboring-state table for each state; see [WorldEdit 7.3 branch source](https://github.com/EngineHub/WorldEdit/blob/version/7.3.x/worldedit-core/src/main/java/com/sk89q/worldedit/world/block/BlockState.java).

The strongest current explanation is WorldEdit state-table expansion on this large modpack exhausting the available heap during integrated-server startup. No captured stack points to TotalDebug as the active blocker. A controlled retry with WorldEdit disabled has not happened, so this is not a completed causal isolation or proof that TotalDebug has no retained memory.

Companion was launched at the user's request from the verified `2ac8749...` artifact as PID 60480. It is available but the stalled Minecraft JVM has not connected. A temporary attach helper was prepared to invoke the existing public TotalDebug Companion-open action. It blocked in `VirtualMachine.getAgentProperties` / native `connectPipe` before loading the agent. The helper was stopped; no diagnostic agent was loaded by this attempt. The game was left running and unmodified.

Raw heap, histogram, GC, thread and installed-bytecode captures are retained in `.codex/world-load-freeze/`. Next step: restart Minecraft, connect Companion with F6 at the main menu, attach the debugger, then repeat world loading. Do not claim successful integrated-server release acceptance from this run.
