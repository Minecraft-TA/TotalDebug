# Working in TotalDebug Companion

Use a full JDK 21 and the checked-in Gradle wrapper. Follow the build instructions in README.md.

For coordinated changes, inspect the affected TotalDebug, SCNet or JIndex checkout. Publish changed libraries to Maven Local before building Companion with -PtotaldebugUseMavenLocal=true.

Keep Swing changes on the event dispatch thread and blocking work outside it. Use the existing UI and debugger test utilities when verifying those behaviors.

Keep the interpreter and compiled Code mode within their existing responsibilities. Architecture changes require an explicit design decision. Remove superseded development paths instead of adding compatibility wrappers.

Preserve unrelated working-tree changes and coordinate overlapping edits.
