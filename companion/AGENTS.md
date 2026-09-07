# Working in TotalDebug Companion

Use a full JDK 21 and the repository-root Gradle wrapper. Follow the root build documentation.

Shared libraries are direct project dependencies. Inspect the mod when changing shared contracts, and SCNet or JIndex when changing their APIs.

Keep Swing changes on the event dispatch thread and blocking work outside it. Use the existing UI and debugger test utilities when verifying those behaviors.

Keep the interpreter and compiled Code mode within their existing responsibilities. Architecture changes require an explicit design decision. Remove superseded development paths instead of adding compatibility wrappers.

Preserve unrelated working-tree changes and coordinate overlapping edits.
