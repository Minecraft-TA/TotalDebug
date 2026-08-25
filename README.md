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

The task mirrors the Minecraft instance layout under `build/local-bundle`. Copy the directory's contents into the instance root. TotalDebug goes to `mods`; Companion goes to `total-debug/companion-app/TotalDebugCompanion.jar`. The bundle does not link their hashes or versions. Runtime compatibility comes from the Companion protocol handshake.

For an external development instance, set `companionDevelopmentJar` in `config/total_debug-client.toml` to the mutable Companion build output. Forward slashes avoid TOML escaping on Windows:

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
