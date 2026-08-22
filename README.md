# TotalDebug

TotalDebug is being ported to Minecraft 1.21.1 as a native NeoForge mod.

This branch contains the clean NeoForge foundation and the F1/F2 core skeleton: lifecycle-owned tick tasks, retained configuration, typed networking, core language resources, and embedded libraries. User-facing legacy features have not been restored yet. See [PORTING.md](PORTING.md) for the auditable progress matrix and next gates.

Local searchable snapshots of the old implementations may live under `legacy/`; their source trees are excluded from Git and Gradle. The 1.7.10 implementation is more advanced for most TotalDebug features and is the primary functional reference. The 1.12.2 snapshot is useful for its newer Minecraft and Forge APIs.

## Development

- Minecraft 1.21.1
- NeoForge 21.1.248
- Java 21
- Mojang mappings with Parchment
- Gradle 9.2.1 and ModDevGradle 2.0.144

Build with:

```powershell
.\gradlew.bat build
```

Run a development client or dedicated server with:

```powershell
.\gradlew.bat runClient
.\gradlew.bat runServer
```

The legacy companion application is maintained separately in [TotalDebugCompanion](https://github.com/Minecraft-TA/TotalDebugCompanion).
