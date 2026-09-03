# TotalDebug

TotalDebug is a Minecraft 1.21.1 NeoForge development mod. It resolves a looked-at block or entity and a hovered item, then asks TotalDebugCompanion to decompile and open the matching runtime class.

The current port restores the core F6 and `/decompile block` workflow. Companion owns Vineflower, JIndex browsing, source caching, and offline navigation over the last published runtime snapshot. SCNet carries authenticated live requests from Minecraft. Companion stays open when Minecraft exits and reconnects on the next run. [PORTING.md](PORTING.md) records completed slices and remaining features.

Local searchable snapshots of the old implementations may live under `legacy/`; their source trees are excluded from Git and Gradle. The 1.7.10 implementation is more advanced for most TotalDebug features and is the primary functional reference. The 1.12.2 snapshot is useful for its newer Minecraft and Forge APIs.

## Development

- Minecraft 1.21.1
- NeoForge 21.1.248
- Java 21
- Mojang mappings with Parchment
- Gradle 9.7.1 and ModDevGradle 2.0.144

Build with:

```powershell
.\gradlew.bat build
```

Run a development client or dedicated server with:

```powershell
.\gradlew.bat runClient
.\gradlew.bat runServer
```

When `../TotalDebugCompanion` exists, `build` and `runClient` build its `shadowJar`. The development client reads that mutable JAR whenever it starts Companion. Rebuild Companion while Minecraft is running, close Companion, then press F6 to launch the new build. TotalDebug stages each launch by content hash, so the running process never locks the mutable development JAR.

Build and collect both current artifacts for an external Minecraft instance with:

```powershell
.\gradlew.bat localBundle
```

The flat `build/local-bundle` directory contains `total_debug.jar` and `TotalDebugCompanion.jar`. Building the bundle does not deploy files or run tests. Runtime compatibility still comes from the Companion protocol handshake.

To build and install both into an external instance, close Minecraft and run:

```powershell
.\gradlew.bat deployLocal "-PtotaldebugInstanceDir=C:/path/to/instance/minecraft"
```

Save `totaldebugInstanceDir=C:/path/to/instance/minecraft` in your user `~/.gradle/gradle.properties` to use just `.\gradlew.bat deployLocal`. The target must be the actual Minecraft directory containing `mods/` and `config/`, not the launcher instance directory above it.

Deployment replaces only `mods/total_debug.jar` and `total-debug/companion-app/TotalDebugCompanion.jar`, and sets `decompilation.companionDevelopmentJar` in `config/total_debug-client.toml` to the selected mutable Companion build. Other configuration values and comments are preserved. Scripts, state, caches and other mods are untouched. Version-named `total_debug-*.jar` files must be removed manually first; deployment reports them and stops without changing the instance. Each replacement is atomic, but the three files are not one transaction. A failed deployment reports which files were installed; close Minecraft and rerun to finish.

Restart Minecraft after deployment. Later Companion-only changes need just a rebuild, closing Companion, and F6. Deployment does not restart processes, update the global MCP copy, or publish a release.

To configure that development override manually instead, set `companionDevelopmentJar` in `config/total_debug-client.toml` to the mutable Companion build output. Forward slashes avoid TOML escaping on Windows:

```toml
[decompilation]
companionDevelopmentJar = "C:/Users/Admin/IdeaProjects/TotalDebugCompanion/build/libs/TotalDebugCompanion.jar"
```

Build Companion normally. After it closes, the next F6 reads and launches the current bytes at that path without restarting Minecraft.

You can also replace `total-debug/companion-app/TotalDebugCompanion.jar` directly. TotalDebug verifies JARs while downloading them, then leaves the installed file alone. Close Companion and press F6 to launch the replacement. Delete the file to restore the published release on the next launch.

Install that verified Companion build as the MCP sidecar used by new Codex tasks with:

```powershell
.\gradlew.bat installCodexCompanionMcp
```

The task copies the Companion JAR to the stable Codex-owned path `%USERPROFILE%\.codex\mcp\totaldebug-companion\TotalDebugCompanion.jar`. Moving either repository afterward does not affect Codex. Rebuilding alone does not replace the installed copy; rerun the install task after Companion MCP changes. Override the Codex directory with `-PcodexHome=C:\path\to\.codex` or the `CODEX_HOME` environment variable.

TotalDebug stages Companion builds side by side by content hash. The hash identifies immutable launch bytes; protocol and capabilities decide compatibility. F6 reuses a compatible running Companion or starts the current development or published JAR when needed. Closing Minecraft disconnects the live tools but leaves Companion and its current workspace open Offline.

Pass an explicit JAR to override the sibling checkout:

```powershell
.\gradlew.bat runClient -PtotaldebugCompanionJar=C:\path\to\TotalDebugCompanion.jar
```

To test the pinned published Companion instead, disable sibling discovery:

```powershell
.\gradlew.bat runClient -PtotaldebugUsePublishedCompanion=true
```

The Companion application lives in [TotalDebugCompanion](https://github.com/Minecraft-TA/TotalDebugCompanion).
Storage ownership, paths, retention and the development reset are documented in [docs/STORAGE.md](docs/STORAGE.md).
