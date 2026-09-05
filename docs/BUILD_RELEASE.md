# Coordinated builds and release publication

All four repositories require Java 21. Use each repository's checked-in Gradle wrapper. Packagecloud remains the publication destination; the library sources are the upstream `tth05/SCNet` and `tth05/JIndex` repositories. Local commits and generated publication metadata are not public releases.

## Local development

Application builds use public repositories by default. Pass `-PtotaldebugUseMavenLocal=true` when verifying coordinated local changes. In that mode, SCNet/JIndex and Companion's shared storage/evaluation dependencies resolve exclusively from Maven Local. A missing local library fails instead of selecting different public bytes. Other dependencies still use their normal repositories.

Run the following in the named repository, in order. The versions below are the current development coordinates, not an approved immutable release set.

| Repository | Command |
| --- | --- |
| SCNet | `.\gradlew.bat build publishToMavenLocal -PscnetVersion=2.0.0 --warning-mode fail` |
| JIndex | `.\gradlew.bat build publishToMavenLocal --warning-mode fail` |
| TotalDebug | `.\gradlew.bat :storage:publishToMavenLocal :evaluation:publishToMavenLocal --warning-mode fail` |
| TotalDebugCompanion | `.\gradlew.bat build -PtotaldebugUseMavenLocal=true --warning-mode fail` |
| TotalDebug | `.\gradlew.bat build -PtotaldebugUseMavenLocal=true -PtotaldebugUsePublishedCompanion=true --warning-mode fail` |

The last command verifies the mod without invoking another Companion build. When deliberately using the sibling Companion build, omit `-PtotaldebugUsePublishedCompanion=true`; the root task forwards the Maven Local option and publishes the shared modules first. Use the same Maven Local option for development `localBundle` or `deployLocal` tasks. Deployment still requires closing Minecraft and selecting the intended instance as described in the workspace instructions.

## Publication configuration

The four Maven publications use `https://packagecloud.io/tth05/repo/java/maven2/`. Their publishing credentials read `PACKAGECLOUD_TOKEN` from the environment and use an empty password, following [Packagecloud's Maven Publish configuration](https://packagecloud.io/docs#gradle_maven_publish_deploy). Public consumers use `https://packagecloud.io/tth05/repo/maven2`. No token belongs in a checked-in file or command argument.

Storage and evaluation share the root `mod_version`. Companion's storage and evaluation dependencies have separate `storage_version` and `evaluation_version` properties. Both currently select `2.0.0-SNAPSHOT`.

| Artifact | Producer version property | Consumer version property |
| --- | --- | --- |
| `com.github.tth05:SCNet` | SCNet `scnetVersion` | Both applications `scnet_version` |
| `com.github.tth05:jindex` | JIndex `jindexVersion` | Companion `jindex_version` |
| `com.github.minecraft_ta:totaldebug-storage` | TotalDebug `mod_version` | Companion `storage_version` |
| `com.github.minecraft_ta:totaldebug-evaluation` | TotalDebug `mod_version` | Companion `evaluation_version` |
| Companion JAR | Companion `releaseVersion` | Mod's compatible immutable download URL and SHA-256 pin |
| TotalDebug mod | TotalDebug `mod_version` | Installed candidate JAR |

Before public publication, choose one fixed candidate version set and build it from recorded commits. Generate the POMs locally with `generatePomFileForMavenJavaPublication` in SCNet/JIndex and `:storage:generatePomFileForStoragePublication :evaluation:generatePomFileForEvaluationPublication` in TotalDebug. Review their coordinates, dependencies and source URLs. The owners' deferred license choice has not been filled in.

The repository publication task names are `publishMavenJavaPublicationToPackagecloudRepository` in SCNet/JIndex and `:storage:publishStoragePublicationToPackagecloudRepository :evaluation:publishEvaluationPublicationToPackagecloudRepository` in TotalDebug. These upload artifacts and require separate publication authorization and account access. They have not been executed as part of local metadata verification.

## Candidate acceptance

After authorized dependency publication, build Companion with Maven Local disabled, publish its immutable candidate, and update the mod's compatible Companion pin from the actual uploaded bytes. Then build the mod with both `-PtotaldebugUseMavenLocal=false` and `-PtotaldebugUsePublishedCompanion=true`. The Windows workflows explicitly use these public dependency settings where applicable.

Record commits and SHA-256 hashes for all producers and the installed pair. Resolve from an empty Maven Local directory and fresh dependency cache, compare embedded library bytes, and run the final dev-instance/ATM10 acceptance matrix in [the release audit](RELEASE_AUDIT.md). Candidate installation and stable publication remain separate steps. Current application protocol 11 and Minecraft payload protocol 2 must match the tested pair.

The current [progress record](RELEASE_PROGRESS.md) identifies the completed local slices and remaining native, documentation, public CI and live acceptance work. No stable release has been approved by these local checks.
