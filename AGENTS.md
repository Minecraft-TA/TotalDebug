# Working in TotalDebug

Use Java 21 and the checked-in Gradle wrapper.

The mod and Companion share this Gradle build. Changes to protocol, storage or evaluation require checking both application consumers. SCNet and JIndex remain external libraries. For artifact packaging, release preparation or deployment, read [docs/BUILD_RELEASE.md](docs/BUILD_RELEASE.md).

Use the owning module's test task for feedback. Root check includes packaging and build-logic functional tests; deployment and installation are explicit tasks that affect external directories.

Keep one current implementation. Remove superseded development code directly instead of adding migrations, compatibility adapters or no-op fallbacks. Report the exact unmet requirement when an operation cannot run.

Keep the evaluator and compiled Code mode within their existing responsibilities. Changes to that architecture require an explicit design decision.

Match checks to the changed behavior and affected consumers. Documentation-only changes need link and diff checks. Preserve unrelated working-tree changes and coordinate file ownership when another task is editing the same repository.
