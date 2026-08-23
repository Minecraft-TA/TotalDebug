# TotalDebugCompanion
The companion app for the [TotalDebug](https://github.com/Minecraft-TA/TotalDebug) mod.

## Development

The project requires JDK 21. Build and test it with:

```shell
./gradlew clean build
```

The mod-facing artifact is `build/libs/TotalDebugCompanion.jar`. It is a self-contained application jar, but it does not include a Java runtime. TotalDebug launches it with the same Java 21 runtime as Minecraft.

The JAR can also be launched directly:

```shell
java -jar build/libs/TotalDebugCompanion.jar
```

Companion keeps one workspace open at a time. Cached sources, scripts, class search, and reference search remain available Offline. Live tools reconnect when a compatible TotalDebug client starts. Closing Minecraft does not close Companion; closing the Companion window exits it.

## Screenshots

![Main View](https://github.com/Minecraft-TA/TotalDebugCompanion/blob/master/images/main.png?raw=true)
