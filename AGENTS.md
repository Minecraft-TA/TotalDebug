# Local workspace

TotalDebug spans four local repositories. When a change crosses the Companion, transport, or index boundary, inspect and verify the corresponding sibling repository at its exact path:

- TotalDebug: `C:\Users\Admin\IdeaProjects\TotalDebug`
- TotalDebugCompanion: `C:\Users\Admin\IdeaProjects\TotalDebugCompanion`
- SCNet: `C:\Users\Admin\IdeaProjects\SCNet`
- JIndex: `C:\Users\Admin\IdeaProjects\JIndex`

Use the checked-in Gradle wrapper in each repository. The workspace JDKs are:

- Java 21: `C:\Users\Admin\.jdks\temurin-21.0.12`

All four repositories use Java 21. Local coordinated dependency builds are published to Maven Local before verifying their consumers.

When deploying current TotalDebug and Companion builds to an external Minecraft instance, run `.\gradlew.bat localBundle` in TotalDebug and copy both JARs from `build\local-bundle`. For Companion development without restarting Minecraft, point `companionDevelopmentJar` in `config\total_debug-client.toml` at the mutable Companion shadow JAR, rebuild Companion, close it, and press F6.
