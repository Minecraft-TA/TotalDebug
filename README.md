# TotalDebug

TotalDebug is a Minecraft 1.21.1 NeoForge mod for inspecting and debugging a running modpack. Its desktop app, [TotalDebug Companion](https://github.com/Minecraft-TA/TotalDebugCompanion), brings source browsing, Java scripts and a debugger to the classes installed in your game.

Press **F6** while looking at a block or entity, or hovering an item, to open its runtime class in Companion. You can also use `/decompile block`.

- Browse decompiled classes, search members, find usages and follow type hierarchies.
- Run Java scripts on the client or server.
- Attach the debugger, inspect paused frames, set breakpoints and evaluate expressions or Java statements.
- Keep browsing the last runtime snapshot after Minecraft closes.
- Connect a local MCP client to Companion's source, scripting and debugger tools.

The supported application environment is **Windows x64, Java 21, Minecraft 1.21.1 and NeoForge 21.1**. Use a full JDK for compilation and debugger support. See [usage and limitations](docs/USAGE.md) for execution requirements.

## Install

Place the TotalDebug mod JAR in the Minecraft instance's `mods/` directory and launch with NeoForge. If Companion is missing, TotalDebug downloads and verifies the Companion version paired with the mod, then starts the desktop app. An existing Companion JAR is left untouched, including manual development replacements. The initial runtime index can take time to prepare on a large modpack.

Update TotalDebug and Companion together. To download the Companion paired with a newly installed mod, close Companion and delete `total-debug/companion-app/TotalDebugCompanion.jar` from the Minecraft instance before opening it again with F6. Independent Companion updates are not supported.

## Build

Use the checked-in Gradle wrapper. For changes across the four repositories, follow the dependency order in [build and release instructions](docs/BUILD_RELEASE.md), then run:

```powershell
.\gradlew.bat build -PtotaldebugUseMavenLocal=true
.\gradlew.bat runClient -PtotaldebugUseMavenLocal=true
```

`runServer` starts a development dedicated server. Versions are defined in [gradle.properties](gradle.properties).

When a sibling `../TotalDebugCompanion` checkout exists, the mod build prepares its application JAR. To use an explicit JAR, pass `-PtotaldebugCompanionJar=C:/path/to/TotalDebugCompanion.jar`. To use the configured published Companion instead, pass `-PtotaldebugUsePublishedCompanion=true`.

## Deploy locally

Close Minecraft and run:

```powershell
.\gradlew.bat deployLocal -PtotaldebugUseMavenLocal=true "-PtotaldebugInstanceDir=C:/path/to/instance/minecraft"
```

The target is the Minecraft directory containing `mods/` and `config/`. Save `totaldebugInstanceDir=C:/path/to/instance/minecraft` in your user `~/.gradle/gradle.properties` to omit it from later commands.

Deployment installs `mods/total_debug.jar` and `total-debug/companion-app/TotalDebugCompanion.jar`, and configures `decompilation.companionDevelopmentJar` to use the mutable Companion build. Remove any version-named `total_debug-*.jar` first. Other mods, scripts and state are preserved. Restart Minecraft after deployment.

For later Companion-only changes, rebuild Companion, close its window and press F6. Each launch uses an immutable copy so the running app does not lock the build output.

`localBundle` produces the two JARs in `build/local-bundle` without installing them or running tests.

## Documentation

- [Usage and limitations](docs/USAGE.md)
- [Builds and publication](docs/BUILD_RELEASE.md)
- [Storage and cache management](docs/STORAGE.md)
- [Profiling startup and shutdown](docs/RUNTIME_PERFORMANCE.md)
- [Companion MCP tools](https://github.com/Minecraft-TA/TotalDebugCompanion/blob/master/MCP.md)
