# TotalDebug

TotalDebug is a Minecraft 1.21.1 NeoForge mod for inspecting and debugging a running modpack. Its desktop app, [TotalDebug Companion](companion/README.md), brings source browsing, Java scripts and a debugger to the classes installed in your game. Both applications and their shared libraries live in this repository.

Press **F6** while looking at a block or entity, or hovering an item, to open its runtime class in Companion. You can also use `/decompile block`.

![TotalDebug Companion running a Java script against an ATM10 instance](companion/images/main.png)

- Browse decompiled classes, search members, find usages and follow type hierarchies.
- Write Java scripts with completion, postfix templates, complete-statement editing and inline diagnostics. Run them on the client or server.
- Organize scripts in folders with rename, duplicate and drag-and-drop actions.
- Attach the debugger, inspect paused frames, set breakpoints and evaluate expressions or Java statements.
- Launch a selected Prism instance, reconnect to Minecraft and keep browsing the last runtime snapshot after it closes.
- Connect a local MCP client to Companion's source, scripting and debugger tools.

The supported application environment is **Windows x64, Java 21, Minecraft 1.21.1 and NeoForge 21.1.250 or newer**. Use a full JDK for compilation and debugger support. See [usage and limitations](docs/USAGE.md) for execution requirements.

## Install

Download `total_debug.jar` from the [latest release](https://github.com/Minecraft-TA/TotalDebug/releases/latest), place it in the Minecraft instance's `mods/` directory and launch with NeoForge. Press F6 to open Companion. If Companion is missing, TotalDebug downloads and verifies the version paired with the mod, then starts the desktop app. The initial runtime index can take time to prepare on a large modpack.

**Update the mod and Companion together.** Version 3.0 changes the connection protocol; a 2.x Companion cannot be used with the new mod.

1. Close Minecraft and Companion, then replace the TotalDebug JAR in `mods/`. Keep only one TotalDebug mod JAR installed.
2. Replace `total-debug/companion-app/TotalDebugCompanion.jar` with the Companion JAR from the same release. Alternatively, delete that JAR and press F6 after launching Minecraft to download the matching version.
3. Update any separate Companion or MCP sidecar copy, then restart its client.

Keep the rest of `total-debug/` to retain your scripts and project data. The automatic installer leaves existing Companion JARs untouched. A configured `decompilation.companionDevelopmentJar` takes precedence; clear it when switching from a development build to a release.

## Build

Use Java 21 and the checked-in root Gradle wrapper. One checkout builds both applications:

```powershell
.\gradlew.bat :build
.\gradlew.bat :mod:runClient
```

Use `:companion:test`, `:mod:test`, or a shared module's `:test` task for focused feedback. `:mod:runServer` starts a development dedicated server. Dependency versions are in [the version catalog](gradle/libs.versions.toml). See [build and release instructions](docs/BUILD_RELEASE.md) for task boundaries and artifact preparation.

Development clients use the current Companion build. To launch an explicit JAR, pass `-PtotaldebugCompanionJar=C:/path/to/TotalDebugCompanion.jar`. To use the configured published fallback instead, pass `-PtotaldebugUsePublishedCompanion=true`.

## Deploy locally

Close Minecraft and run:

```powershell
.\gradlew.bat :deployLocal "-PtotaldebugInstanceDir=C:/path/to/instance/minecraft"
```

The target is the Minecraft directory containing `mods/` and `config/`. Save `totaldebugInstanceDir=C:/path/to/instance/minecraft` in your user `~/.gradle/gradle.properties` to omit it from later commands.

Deployment replaces `mods/total_debug.jar` and `total-debug/companion-app/TotalDebugCompanion.jar`, and configures `decompilation.companionDevelopmentJar` to use the mutable Companion build. After installation succeeds, it removes older version-named TotalDebug JARs such as `total_debug-2.0.0.jar`. Other mods, scripts and state are preserved. Restart Minecraft after deployment.

For later Companion-only changes, run `:companion:shadowJar`, close its window and press F6. Each launch uses an immutable copy so the running app does not lock the build output.

`localBundle` produces the two JARs in `build/local-bundle` without installing them or running tests.

## Documentation

- [Usage and limitations](docs/USAGE.md)
- [Builds and publication](docs/BUILD_RELEASE.md)
- [Storage and cache management](docs/STORAGE.md)
- [Profiling startup and shutdown](docs/RUNTIME_PERFORMANCE.md)
- [Companion MCP tools](companion/MCP.md)
