# Local workspace

TotalDebug spans four local repositories. When a change crosses the Companion, transport, or index boundary, inspect and verify the corresponding sibling repository at its exact path:

- TotalDebug: `C:\Users\Admin\IdeaProjects\TotalDebug`
- TotalDebugCompanion: `C:\Users\Admin\IdeaProjects\TotalDebugCompanion`
- SCNet: `C:\Users\Admin\IdeaProjects\SCNet`
- JIndex: `C:\Users\Admin\IdeaProjects\JIndex`

Use the checked-in Gradle wrapper in each repository. The workspace JDKs are:

- Java 21: `C:\Users\Admin\.jdks\temurin-21.0.12`

All four repositories use Java 21. Local coordinated dependency builds are published to Maven Local before verifying their consumers. Pass `-PtotaldebugUseMavenLocal=true` to application development builds; [the coordinated build instructions](docs/BUILD_RELEASE.md) give the dependency order.

When deployment is requested, close Minecraft and run `.\gradlew.bat deployLocal -PtotaldebugUseMavenLocal=true "-PtotaldebugInstanceDir=<absolute Minecraft directory>"` in TotalDebug. The target may instead be saved in user Gradle properties. The task installs both JARs and configures the mutable Companion development JAR; it leaves instance data and other mods alone. Version-named TotalDebug JARs require manual removal first. `.\gradlew.bat localBundle -PtotaldebugUseMavenLocal=true` only produces a flat pair of JARs, not an instance-layout tree. For later Companion-only changes, rebuild Companion, close it, and press F6.

# Development compatibility

This project has no released users or persistent-data compatibility contract. Keep one current implementation. Breaking changes to local development state are allowed.

- Remove obsolete code and local state directly.
- Do not add migrations, legacy cleanup paths, compatibility adapters, or fallback behavior for superseded development versions.
- Establish the supported behavior from runtime truth. Fail with the exact unmet requirement when it cannot be satisfied.

# Task continuity and verification

- Carry authorized work through implementation and verification. Answer progress questions briefly, then resume the active task. Treat follow-up messages as steering unless the user explicitly stops or replaces the task.
- Preserve decisions already made in the task. For the stable-release audit, keep the evaluator and fix demonstrated correctness issues within its existing design. Replacing it requires a new user decision.
- Resolve routine implementation choices from repository evidence. If required input blocks one part, complete independent authorized work and identify the exact decision needed. Prepare a concrete, reviewable result before requesting approval for a remaining action.
- User instructions take precedence over skill guidelines. If a skill causes a pause or changes the requested scope, link the exact SKILL.md, quote the relevant instruction, and explain the conflict. Distinguish an explicit requirement from an inferred preference.
- Match verification to the changed behavior and affected consumers. Once relevant checks pass, repeat or broaden them only for a new change, failure, or unresolved concern. Documentation-only edits need link and diff checks, not Gradle builds.
- When another task is working in these repositories, coordinate file ownership before overlapping edits. Preserve its uncommitted changes and report which files and checks belong to this task.
