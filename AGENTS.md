# Working in TotalDebug

Use Java 21 and the checked-in Gradle wrapper.

TotalDebug works with three sibling repositories: TotalDebugCompanion, SCNet and JIndex. When changing a shared protocol, index API or transport contract, inspect and verify the affected consumers. For coordinated dependency builds and deployment, follow [docs/BUILD_RELEASE.md](docs/BUILD_RELEASE.md) and [README.md](README.md).

Keep one current implementation. Remove superseded development code directly instead of adding migrations, compatibility adapters or no-op fallbacks. Report the exact unmet requirement when an operation cannot run.

Keep the evaluator and compiled Code mode within their existing responsibilities. Changes to that architecture require an explicit design decision.

Match checks to the changed behavior and affected consumers. Documentation-only changes need link and diff checks. Preserve unrelated working-tree changes and coordinate file ownership when another task is editing the same repository.
