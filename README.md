# TotalDebug

TotalDebug is being ported to Minecraft 1.21.1 as a native NeoForge mod.

This branch contains only the clean NeoForge foundation. No legacy features have been ported yet. The 1.12.2 implementation remains the main behavioral reference, with the 1.7.10 history available for older or divergent implementations.

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
