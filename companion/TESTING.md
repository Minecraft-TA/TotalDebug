# Companion UI tests

Use JDK 21 and the root Gradle wrapper. Run the regular suite with `./gradlew :companion:test`. It uses real Swing components offscreen and does not require native focus. It still needs a graphical environment; offscreen is not AWT headless mode.

For UI changes, `./gradlew :companion:uiTest` runs only the tests that require Swing isolation. The ordinary `test` task still includes them, so `check` keeps the same quiet coverage without running the UI subset twice.

`test` and `check` exclude native desktop tests. Run `./gradlew :companion:desktopTest` on an isolated desktop when validating OS focus or monitor-edge popup placement. This task deliberately opens windows and can take focus. It enables the `totaldebug.desktopTests` property required by `@RequiresDesktop`, so these tests also stay disabled in an ordinary direct JUnit run. For complete verification on an isolated desktop, run both `:companion:check` and `:companion:desktopTest`.

## Shared setup

[UiTestExtension](src/test/java/com/github/minecraft_ta/totalDebugCompanion/testui/UiTestExtension.java) installs one window safety guard for the JUnit run. It opens a fresh [UiTestScope](src/test/java/com/github/minecraft_ta/totalDebugCompanion/testui/UiTestScope.java) only for tests marked [UiTest](src/test/java/com/github/minecraft_ta/totalDebugCompanion/testui/UiTest.java). Unmarked tests skip the Swing snapshots, popup installation, focus restoration, and EDT cleanup. The Gradle tasks enable extension autodetection. Use Gradle when running these tests from an IDE.

Mark the class when its fixtures consistently need isolated window, theme, or focus state. In a mixed class, mark only the methods that need it. `@RequiresDesktop` includes the UI marker. Construct native windows in the test or `@BeforeEach`, after the scope opens. The suite guard rejects unmarked native-window creation before the window is shown and reports the missing scope.

Keep component fixtures, sample services, and assertions in the owning tests. Use the shared scope for Swing mechanics:

- Call `UiTestScope.show(window)` on the EDT after setting its size or packing it. The scope disables native focus and chooses a position beyond all attached monitors before showing it.
- Use `UiTestScope.place(child, owner, x, y)` before showing a child that must preserve an owner-relative position. The preview launcher uses the same method in both interactive and background modes.
- Use `onEdt` and `await` for EDT work and condition-based waits. `await` fails on timeout and cannot block the EDT.
- Use `UiTestScope.focus(component)` when a component behavior test needs logical Swing focus. This sends component focus events without activating a native window. It does not test OS focus dispatch. Mark actual native-focus tests with [RequiresDesktop](src/test/java/com/github/minecraft_ta/totalDebugCompanion/testui/RequiresDesktop.java).

The scope restores the popup factory, keyboard focus manager, and look and feel, clears active menus, and disposes windows created during the test. It also applies the no-focus policy to newly realized windows created inside application actions. Tests should still close their own service fixtures. The guard lives for the whole run, but mutable UI state is reset between marked tests so later tests cannot inherit a window, popup, or synthetic focus owner.

The window guard checks show, move, resize, and native-focus events, including windows disposed before teardown. It fails the test when a window overlaps a monitor or takes native focus. Correct pre-show placement prevents the flash; the guard reports regressions rather than moving an escaped window after the fact.

## Popup placement

[OffscreenPopupFactory](src/test/java/com/github/minecraft_ta/totalDebugCompanion/testui/OffscreenPopupFactory.java) mounts Swing popup contents in the owner's layered pane. It preserves requested coordinates and supports explicit owner-relative placement for rendered scenarios. It rejects popup rectangles that overlap a real monitor. Menu tests can inspect `showingMenu()` instead of installing their own no-op popup factory.

Theme installation normally replaces Swing's popup factory. The scope reinstalls the offscreen factory on look-and-feel changes. [OffscreenPopupMenuUI](src/test/java/com/github/minecraft_ta/totalDebugCompanion/testui/OffscreenPopupMenuUI.java) retains FlatLaf's menu painting and layout while bypassing its native monitor-position adjustment. The quiet test JVM also disables Swing's monitor adjustment with `javax.swing.adjustPopupLocationToFit=false` before Swing initializes.

These adaptations intentionally do not test native monitor clamping or native popup hosting. Keep those assertions in `desktopTest`; do not replace them with assertions against a fixture-supplied position.

## Previews and captures

`./gradlew :companion:uiHarness` remains an interactive application preview and intentionally shows a window. `./gradlew :companion:uiContactSheet` renders the scenario/theme matrix in background processes. Screenshot and `--verify-*` modes in [UiDevHarness](src/test/java/com/github/minecraft_ta/totalDebugCompanion/UiDevHarness.java) use the same offscreen scope and fail the process if the window guard reports a violation.

The capture workflow supplies full application sample data. Small JUnit component tests should not start that application just to obtain window isolation. Captures support visual review; behavioral assertions remain in tests and scenario checks.
