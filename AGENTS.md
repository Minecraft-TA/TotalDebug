# Local workspace

TotalDebug spans four local repositories. When a change crosses the Companion, transport, or index boundary, inspect and verify the corresponding sibling repository at its exact path:

- TotalDebug: `C:\Users\Admin\IdeaProjects\TotalDebug`
- TotalDebugCompanion: `C:\Users\Admin\IdeaProjects\TotalDebugCompanion`
- SCNet: `C:\Users\Admin\IdeaProjects\SCNet`
- JIndex: `C:\Users\Admin\IdeaProjects\JIndex`

Use the checked-in Gradle wrapper in each repository. The workspace JDKs are:

- Java 21: `C:\Users\Admin\.jdks\temurin-21.0.12`

All four repositories use Java 21. Local coordinated dependency builds are published to Maven Local before verifying their consumers.

When deployment is requested, close Minecraft and run `.\gradlew.bat deployLocal "-PtotaldebugInstanceDir=<absolute Minecraft directory>"` in TotalDebug. The target may instead be saved in user Gradle properties. The task installs both JARs and configures the mutable Companion development JAR; it leaves instance data and other mods alone. Version-named TotalDebug JARs require manual removal first. `.\gradlew.bat localBundle` only produces a flat pair of JARs, not an instance-layout tree. For later Companion-only changes, rebuild Companion, close it, and press F6.

# Development compatibility

This project has no released users or persistent-data compatibility contract. Keep one current implementation. Breaking changes to local development state are allowed.

- Remove obsolete code and local state directly.
- Do not add migrations, legacy cleanup paths, compatibility adapters, or fallback behavior for superseded development versions.
- Establish the supported behavior from runtime truth. Fail with the exact unmet requirement when it cannot be satisfied.
