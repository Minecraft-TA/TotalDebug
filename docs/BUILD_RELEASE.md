# Builds and publication

Use Java 21 and each repository's checked-in Gradle wrapper. The application consists of TotalDebug and TotalDebugCompanion, with SCNet for transport and JIndex for indexing. TotalDebug also produces the shared storage and evaluation libraries.

## Coordinated local builds

Place the four repositories alongside each other and run these commands in order:

| Repository | Command |
| --- | --- |
| SCNet | `.\gradlew.bat build publishToMavenLocal '-PscnetVersion=2.0.0' --warning-mode fail` |
| JIndex | `.\gradlew.bat build publishToMavenLocal --warning-mode fail` |
| TotalDebug | `.\gradlew.bat :storage:publishToMavenLocal :evaluation:publishToMavenLocal --warning-mode fail` |
| TotalDebugCompanion | `.\gradlew.bat build -PtotaldebugUseMavenLocal=true --warning-mode fail` |
| TotalDebug | `.\gradlew.bat build -PtotaldebugUseMavenLocal=true -PtotaldebugUsePublishedCompanion=true --warning-mode fail` |

Application builds use public repositories by default. `-PtotaldebugUseMavenLocal=true` resolves the coordinated libraries exclusively from Maven Local, so a missing local dependency fails instead of selecting different published bytes. Other dependencies keep their normal repositories.

The last command verifies the mod without building Companion again. Omit `-PtotaldebugUsePublishedCompanion=true` to use the sibling Companion checkout; the root task forwards the local dependency option and publishes the shared modules first.

Use the same local dependency option with `localBundle`, `deployLocal`, `runClient` and `runServer`. See [local deployment](../README.md#deploy-locally) for instance paths and restart instructions.

## Maven publication

SCNet and JIndex are published from their upstream repositories, `tth05/SCNet` and `tth05/JIndex`. The four library publications use `https://packagecloud.io/tth05/repo/java/maven2/`. Public consumers use `https://packagecloud.io/tth05/repo/maven2`.

Publishing credentials read `PACKAGECLOUD_TOKEN` from the environment with an empty password. Keep credentials out of repository files and command arguments.

| Artifact | Producer version property | Consumer version property |
| --- | --- | --- |
| `com.github.tth05:SCNet` | SCNet `scnetVersion` | Both applications `scnet_version` |
| `com.github.tth05:jindex` | JIndex `jindexVersion` | Companion `jindex_version` |
| `com.github.minecraft_ta:totaldebug-storage` | TotalDebug `mod_version` | Companion `storage_version` |
| `com.github.minecraft_ta:totaldebug-evaluation` | TotalDebug `mod_version` | Companion `evaluation_version` |
| Companion JAR | Companion `releaseVersion` | Mod's immutable download URL and SHA-256 pin |
| TotalDebug mod | TotalDebug `mod_version` | Installed mod JAR |

Generate and inspect publication metadata with `generatePomFileForMavenJavaPublication` in SCNet/JIndex and `:storage:generatePomFileForStoragePublication :evaluation:generatePomFileForEvaluationPublication` in TotalDebug.

Publish using `publishMavenJavaPublicationToPackagecloudRepository` in SCNet/JIndex and `:storage:publishStoragePublicationToPackagecloudRepository :evaluation:publishEvaluationPublicationToPackagecloudRepository` in TotalDebug.

## Publish a matching application pair

1. Select immutable library versions, update consumer properties and publish the libraries.
2. Build Companion with `-PtotaldebugUseMavenLocal=false`, then publish its application JAR.
3. Set the mod's Companion URL and SHA-256 pin to those uploaded bytes.
4. Build TotalDebug with `-PtotaldebugUseMavenLocal=false -PtotaldebugUsePublishedCompanion=true`.
5. Verify public dependency resolution using a fresh Gradle dependency cache, then install and exercise the resulting pair.

Verify F6 navigation, search, usages, hierarchy, scripts, debugger evaluation, disconnect/reconnect and offline reopening on the pair intended for distribution. Include the applicable client/server permission checks. Record the artifact hashes with the release.

Both peers require the same application protocol. Changing the handshake or message layout requires coordinated changes and a protocol version increment.

## MCP sidecar installation

To install Companion at a stable path for Codex's local MCP integration:

```powershell
.\gradlew.bat installCodexCompanionMcp -PtotaldebugUseMavenLocal=true
```

The task copies the application JAR to `%USERPROFILE%/.codex/mcp/totaldebug-companion/TotalDebugCompanion.jar`. Override the directory with `-PcodexHome=C:/path/to/.codex` or `CODEX_HOME`. Rebuilding Companion does not update that copy; rerun the install task after MCP changes. See [Companion's MCP documentation](https://github.com/Minecraft-TA/TotalDebugCompanion/blob/master/MCP.md) for connection setup.
