# TotalDebug

TotalDebug is a Minecraft 1.21.1 NeoForge development mod. It can decompile a looked-at block or entity and a hovered item, then open the generated Java source in TotalDebugCompanion.

The current port restores the core F6 and `/decompile block` workflow. It uses Vineflower for decompilation, JIndex for the runtime class index, and SCNet for the authenticated per-process Companion connection. [PORTING.md](PORTING.md) records completed slices and remaining features.

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

For Companion development, pass its fat JAR to the client run:

```powershell
.\gradlew.bat runClient -PtotaldebugCompanionJar=C:\path\to\TotalDebugCompanion.jar
```

The Companion application lives in [TotalDebugCompanion](https://github.com/Minecraft-TA/TotalDebugCompanion).
