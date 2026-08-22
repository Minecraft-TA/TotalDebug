# TotalDebugCompanion
The companion app for the [TotalDebug](https://github.com/Minecraft-TA/TotalDebug) mod.

## Development

The project requires JDK 21. Build and test it with:

```shell
./gradlew clean build
```

The mod-facing artifact is `build/libs/TotalDebugCompanion.jar`. It is a self-contained application jar, but it does not include a Java runtime. TotalDebug launches it with the same Java 21 runtime as Minecraft.

## Screenshots

![Main View](https://github.com/Minecraft-TA/TotalDebugCompanion/blob/master/images/main.png?raw=true)
