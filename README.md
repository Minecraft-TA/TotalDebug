# TotalDebugCompanion
The companion app for the [TotalDebug](https://github.com/Minecraft-TA/TotalDebug) mod.

## Development

The project requires JDK 21. For coordinated local development, first publish the shared storage and evaluation modules from the sibling TotalDebug checkout with `./gradlew :storage:publishToMavenLocal :evaluation:publishToMavenLocal`. Then build and test Companion with:

```shell
./gradlew clean build
```

The mod-facing artifact is `build/libs/TotalDebugCompanion.jar`. It is a self-contained application jar, but it does not include a Java runtime. TotalDebug launches it with the same Java 21 runtime as Minecraft.

The JAR can also be launched directly:

```shell
java -jar build/libs/TotalDebugCompanion.jar
```

### Visual verification

The UI harness renders named application states without a Minecraft session or control of the real mouse:

```shell
./gradlew uiHarness --args="--theme=islands-dark --scenario=search-results"
./gradlew uiHarness --args="--list-scenarios"
./gradlew uiContactSheet
```

`uiContactSheet` renders every registered state in a fresh process for both themes. It writes individual PNGs, `contact-sheet.png`, and `manifest.json` under `build/ui-screenshots` by default. Pass `--args="--output=<directory>"` to choose another output directory.
Use `--assemble-only` with an existing capture directory to rebuild the sheets without rerendering the states.

Companion keeps one workspace open at a time. Cached sources, scripts, class search, and reference search remain available Offline. Live tools reconnect when a compatible TotalDebug client starts. Closing Minecraft does not close Companion; closing the Companion window exits it.

## Screenshots

![Main View](https://github.com/Minecraft-TA/TotalDebugCompanion/blob/master/images/main.png?raw=true)
The current storage layout and manual development reset are documented in the sibling TotalDebug repository's `docs/STORAGE.md`. The current instance state format is 2, including breakpoint actions and Expression/Code history. Existing format-1 development state must be reset before using this build. No legacy files are migrated automatically.
