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

When `../TotalDebugCompanion` exists, `runClient` builds its `shadowJar` and uses that JAR automatically. This keeps local Companion changes testable without publishing a release or changing its version.

Build a matching pair for an external Minecraft instance with:

```powershell
.\gradlew.bat localCompanionPair
```

The task builds the sibling Companion shadow JAR, embeds its SHA-256 in TotalDebug, verifies the match, and writes both JARs to `build/local-companion-pair`.

Install that verified Companion build as the MCP sidecar used by new Codex tasks with:

```powershell
.\gradlew.bat installCodexCompanionMcp
```

The task copies the Companion JAR to the stable Codex-owned path `%USERPROFILE%\.codex\mcp\totaldebug-companion\TotalDebugCompanion.jar`. Moving either repository afterward does not affect Codex. Rebuilding alone does not replace the installed copy; rerun the install task after Companion MCP changes. Override the Codex directory with `-PcodexHome=C:\path\to\.codex` or the `CODEX_HOME` environment variable.

TotalDebug installs Companion builds side by side by content hash. F6 reuses a compatible running Companion or starts one when needed. Closing Minecraft disconnects the live tools but leaves the Companion and its current workspace open Offline.

Pass an explicit JAR to override the sibling checkout:

```powershell
.\gradlew.bat runClient -PtotaldebugCompanionJar=C:\path\to\TotalDebugCompanion.jar
```

To test the pinned published Companion instead, disable sibling discovery:

```powershell
.\gradlew.bat runClient -PtotaldebugUsePublishedCompanion=true
```

The Companion application lives in [TotalDebugCompanion](https://github.com/Minecraft-TA/TotalDebugCompanion).
