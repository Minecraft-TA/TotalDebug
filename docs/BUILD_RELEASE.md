# Builds and publication

Use a full JDK 21 and the repository-root wrapper. Keep the IDE's Gradle JVM and command-line `JAVA_HOME` consistent so sequential commands can reuse a daemon. The five projects are `mod`, `companion`, `protocol`, `storage` and `evaluation`. Shared code uses direct project dependencies. SCNet and JIndex are external libraries.

## Development commands

Run these from the repository root:

| Command | Purpose |
| --- | --- |
| `.\gradlew.bat :protocol:test` | Message codecs and transport direction checks |
| `.\gradlew.bat :storage:test` | Shared storage tests |
| `.\gradlew.bat :evaluation:test` | Compiler/runtime helpers and class-bundle tests |
| `.\gradlew.bat :companion:test` | Desktop tests without application packaging |
| `.\gradlew.bat :mod:test` | Mod tests without application packaging |
| `.\gradlew.bat :test` | All normal application/library tests |
| `.\gradlew.bat :check` | Full verification, including packaging and deployment-task tests |
| `.\gradlew.bat :build` | Verification and application artifacts |
| `.\gradlew.bat :localBundle` | Both current JARs in `build/local-bundle`, without tests or installation |
| `.\gradlew.bat :companion:shadowJar` | Desktop executable only |
| `.\gradlew.bat :mod:runClient` | Development Minecraft client with the current Companion build |
| `.\gradlew.bat :mod:runServer` | Development dedicated server |

Use `--tests 'package.TestClass'` on the owning test task for focused feedback. Normal tests do not depend on Shadow or mod JAR packaging. NeoForge dependencies are still needed to compile and test the mod. Companion tests retain the generated Parchment index used by decompilation; third-party notice collection runs only when packaging the executable.

The checked-in configuration enables configuration cache, build cache and filesystem watching. Avoid habitual `clean` during editing. A warm unchanged invocation and a test that actually executes measure different things. The worker cap and heap ceiling are shared build settings, not a guarantee about total resident JVM memory.

Build conventions and custom tasks live in `build-logic`. Root `check` explicitly includes its functional tests. Those tests use temporary fake Minecraft instances; normal builds do not install anything.

## Dependencies

Dependency versions live in `gradle/libs.versions.toml`; settings own plugin resolution and repositories. Minecraft/desktop runtime constraints can require different versions of the same library. Review final nested and shaded artifacts when changing dependency scopes.

Builds use published SCNet and JIndex by default. When developing those libraries, publish them from their own checkouts, then use `-PtotaldebugUseMavenLocal=true`. That opt-in resolves the `com.github.tth05` group exclusively from Maven Local; a missing local artifact fails. Internal protocol/storage/evaluation artifacts are never selected from Maven Local and require no publication. Existing library publications remain available for older application releases.

The current checkout requires SCNet 2.1.0 for explicit endpoint message registration. Until that version is published, build and publish the SCNet checkout to Maven Local and enable `totaldebugUseMavenLocal`. JIndex must also be present locally when that option is enabled.

## Local installation

Close Minecraft, then run:

```powershell
.\gradlew.bat :deployLocal "-PtotaldebugInstanceDir=C:/path/to/instance/minecraft"
```

The directory must already contain `mods/` and `config/`. The task replaces the two application JARs and updates the Companion development path. After installation succeeds, it removes older version-named TotalDebug JARs. It preserves other mods, saved scripts and user state. See [local deployment](../README.md#deploy-locally).

Client runs use the current built Companion by default. `-PtotaldebugCompanionJar=C:/path/to/TotalDebugCompanion.jar` selects an explicit development JAR. `-PtotaldebugUsePublishedCompanion=true` selects the bundled published fallback instead. These options change client launching, not the contents of `localBundle`. Development run data and `totaldebug.workspaceRoot` remain anchored at the repository root.

To install the desktop MCP sidecar independently:

```powershell
.\gradlew.bat :installCodexCompanionMcp
```

The destination is `%USERPROFILE%/.codex/mcp/totaldebug-companion/TotalDebugCompanion.jar`. Override the Codex home with `-PcodexHome=C:/path/to/.codex` or `CODEX_HOME`. Rerun the task after MCP changes. It builds only Companion and its dependencies. See [MCP setup](../companion/MCP.md).

## Prepare a paired release

Select a new, unused version, then run from one checkout:

```powershell
.\gradlew.bat :releaseBundle "-PreleaseVersion=<new-version>" --warning-mode fail
```

`build/release` contains `total_debug.jar`, `TotalDebugCompanion.jar` and their `.sha256` files. Release preparation runs the full automated checks. The mod's release descriptor contains the exact staged Companion hash and its intended immutable asset URL under `Minecraft-TA/TotalDebug`. It does not download or publish anything.

Ordinary mod resources retain the valid last-published Companion descriptor for existing installer behavior. Local development explicitly supplies the current Companion path. `:mod:releaseJar` creates a separate artifact by replacing the descriptor in the final ModDev JAR and preserving nested libraries and manifest metadata. The release variant receives its own module-packaging test. Never upload the ordinary development mod JAR as a release.

The release workflow creates a draft with both validated JARs and checksums, refuses asset replacement, and checks downloaded asset hashes. Publish the draft after verifying F6 navigation, search/usages/hierarchy, scripts, debugger evaluation, reconnect/offline reopening and relevant client/server permission checks on the staged pair. Upload the same files that were hashed; do not rebuild Companion between hashing and uploading.

Old Companion release assets remain in their original repository because released mods pin those URLs. Keep existing releases immutable. After a new release is published, update the checked-in fallback descriptor and development version deliberately for subsequent development.

Both peers still require the same application protocol. A handshake or wire-layout change requires coordinated consumers and a protocol version increment. Existing installed Companion JARs remain untouched; users updating the pair must follow the [manual update procedure](../README.md#install).
