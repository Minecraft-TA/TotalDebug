# Status UI interaction review

Status: implemented and locally verified, September 21, 2026. UI refinements remain open for user feedback.

## What failed

The previous pass improved density, but did not establish clear control behavior. Its verification checked component rendering and backend operations more thoroughly than real placement, initial focus, and action discoverability.

The main-window preview painted the actual Swing components into manually positioned images. That bypassed `PopupElements.showAbove`, so those images did not verify the real placement code. The history test checked popup dimensions without checking its bounds relative to the application window.

### Confirmed implementation problems

- `ApplicationStatusBar` places `NotificationWidget` after the breadcrumbs and before horizontal glue. The same widget carries changing message text, changing severity icons, and the history opener. Its position and size are unstable.
- `PopupElements.showAbove` right-aligns every popup against its invoker without considering the owning window. A controlled 984-pixel-wide root pane produced a 458-pixel history popup with a requested x-coordinate of **-404** relative to that pane. Screen fitting alone cannot keep a popup inside a window occupying only part of the screen.
- Swing's default focus traversal target in a displayed, non-activating test window was **`Debugger: Unavailable`**. Its model reported `selected=false`, `default=false`, `focusable=true`, and `enabled=true`. The apparent default selection is consistent with initial keyboard focus landing on this toolbar button.
- MCP's endpoint is one borderless button containing both URL and copy glyph. The glyph is not a separate control. Its tooltip and accessible name are initialized from an empty string and do not follow the later URL assignment. The cursor remains the default arrow. Copying has no local success feedback.
- Unavailable debugger state still offers a menu even without a target. An unavailable indicator should not promise an action it cannot perform. Offline breakpoint management is useful, however, and needs to remain reachable if the status control is disabled.

Probe: `companion/build/status-ui-design/StatusInteractionProbe.java`. It uses an isolated temporary project and an offscreen, non-activating window. It does not launch MCP or attach to Minecraft. The reported placement is the implementation's requested owner-relative geometry, not a claim about the user's monitor arrangement.

## References and their implications

- [JetBrains icon buttons](https://plugins.jetbrains.com/docs/intellij/icon-button.html) distinguishes immediate actions, toggles, and menu openers, illustrates normal/hover/pressed/disabled states, and requires tooltips. These should have distinct behavior in Companion.
- [JetBrains links](https://plugins.jetbrains.com/docs/intellij/link.html) permits links for compact secondary actions, but recommends buttons for primary actions and unconstrained UI. An external-link arrow means opening a browser; it should not mean copying a value.
- [IntelliJ notifications](https://www.jetbrains.com/help/idea/notifications.html) separates transient balloons, the status message, and persistent history in a Notifications tool window. Clicking a status message opens its history entry.
- [JetBrains tool windows](https://plugins.jetbrains.com/docs/intellij/tool-window.html) describes panes inside the main workspace and stable monochrome opener icons with badges. The opener should not turn into each notification's severity icon.
- [Vercel's interface-review guidance](https://raw.githubusercontent.com/vercel-labs/web-interface-guidelines/main/command.md) reinforces clear action semantics, visible keyboard focus, restrained autofocus, meaningful labels, hover feedback, and content overflow checks.
- [UI/UX Pro Max's interaction checklist](https://raw.githubusercontent.com/nextlevelbuilder/ui-ux-pro-max-skill/main/.claude/skills/ui-ux-pro-max/references/quick-reference.md) emphasizes distinguishable states, feedback after actions, platform conventions, and targets that do not require precise pointing.
- [Anthropic frontend design](https://github.com/anthropics/skills/blob/main/skills/frontend-design/SKILL.md) is useful for deliberate visual choices, but its aesthetic direction should not replace Companion's established theme and desktop conventions.

These general skills are supplementary references. Web CSS rules and mobile target dimensions should not be copied mechanically into a dense Swing application. The JetBrains desktop conventions take priority for this design.

## Implemented interaction contract

### Service controls

Keep compact status indicators. Use a clear hover target where clicking opens controls. The user requested removing the added dropdown arrows; service controls do not include them. Use tooltips for passive information that does not warrant an empty interactive panel.

MCP keeps its native checkbox. Show the endpoint as a value with a separate compact **Copy** button using the existing copy icon. Give that button an action tooltip, keyboard focus, normal/hover/pressed/disabled states, and brief **Copied** feedback that does not change row width or publish another history event.

Index retains measured counts and duration. Make **Rebuild** or **Retry** an identifiable compact action, rather than an isolated glyph that looks like metadata. Keep the busy behavior already implemented.

Connected Game details remain useful: instance name, directory, and real process ID. Copy is a separate action. Keep connection controls with the separate connection work; do not add inert placeholders.

### Debugger

- No target: muted disabled bug icon, with the actual reason in its tooltip. No permanent `Debugger: Unavailable` text and no unavailable-only popup.
- Target available: normal bug icon; the menu offers the actions currently possible.
- Paused or failed: provide a meaningful attention indicator and explanatory tooltip. Retain concise status text where it helps identify a paused session.
- The control must not appear selected when the menu is closed. Initial focus belongs to the editor, or the project tree when no editor is open. Keyboard users still need visible focus when they deliberately reach the control.
- Preserve offline breakpoint management in the Script menu. Disabling the status icon must not remove the only access to saved breakpoints.

### Notifications

Use a stable bell/history button at the far right of the status bar. Its icon remains stable; a badge indicates unread attention. The transient message may remain beside the breadcrumbs, but does not own history's position. Clicking it opens the corresponding entry.

History container: a right-side panel inside the main window, opened on demand and closed with its close control or Escape. It consumes no permanent panel space while closed. This follows IntelliJ's division between history and transient notifications without introducing a general docking system or new sidebars everywhere.

Keep a compact header, clear-history action, readable entries, and explicit per-entry actions. Empty history shows only its real empty state; unavailable actions are omitted or disabled appropriately.

Routine feedback stays quiet. Important balloons remain inside the application's content bounds and do not take focus.

## Verification required before calling the UI polished

1. Open actual popups and panels through their controls. Capture their actual placement. Do not use manually positioned composites as placement evidence.
2. Test the main window on the left and right halves of a display, away from the screen origin, at the minimum supported width, and across supported display scales. Assert the full interactive area remains inside the owner and usable display.
3. Inspect idle, hover, pressed, keyboard-focused, disabled, busy, and completed-action states. Verify Copy is discoverable before hovering and provides success feedback afterward.
4. Test first launch with a project but no editor, opening an editor, reopening a window, closing menus, Escape, and Tab navigation. The debugger must not receive accidental initial focus or retain a false selected appearance.
5. Exercise no debugger target, detached target, attaching, running, paused, failed, and disconnected states. Verify offline breakpoint access remains available.
6. Exercise empty history, one entry, long text, many entries, new messages while history is open, and changing editor breadcrumbs. The opener stays in one place; content and focus do not jump.
7. Check both themes using the real application controls and interaction states. Preserve the existing backend lifecycle tests.

Implement the control states, focus, and containment behavior before another broad visual pass. The notification backend and MCP/index ownership do not need another redesign for these presentation problems.

## Implementation and verification notes

- Settings is a single gear in the top bar; the File menu containing only Settings was removed.
- Notification history is one workspace panel, with a fixed right-hand bell and a separate transient message. Closing restores previous focus and releases that component reference. Inactive windows do not acknowledge unseen entries; activation acknowledges visible rows.
- Popup placement uses owner/display bounds and invalidates nested layout caches before measuring. Reused MCP/index panels resize immediately as their content changes.
- Script and text-resource views forward cause-aware Swing focus requests to their text areas, including window activation.
- The imported dark theme yielded fully transparent derived toolbar hover/pressed colors. The existing defaults addon now supplies the Islands toolbar overlays for both buttons and toggles. Notification links retain native button borders for keyboard focus. Mouse-event rendering tests cover normal, hover and pressed states in both themes.
- Collapsed/expanded notification controls use a matching outlined chevron pair. The unread badge is an antialiased circle; the added status dropdown arrows were removed at the user's request.
- Actual MainWindow controls were opened through action listeners and captured in both themes at 100% and 150% display scale. Popups were confirmed to be descendants of the root pane, using their actual on-screen locations rather than manually composited positions. Empty/expanded/narrow history, copy hover/press, header and disclosure actions were inspected. The isolated fixture is under ignored `companion/build/status-ui-design/CurrentStatusPreview.java`; images are under `companion/build/status-ui-revised` and `companion/build/status-ui-revised-150`.
- Regression tests cover both-side owner placement, narrow history containment, stable bell position, live MCP size transitions, inactive history, source activation, copy feedback without resizing, offline breakpoint access and actual editor focus events.
- These are controlled Swing fixtures, not a claim to have inspected the user's particular monitor arrangement or every debugger runtime phase. Existing debugger action tests retain phase coverage.

Validation result: the full Companion suite passed with 1,017 tests and no failures, errors or skips. The final text-spacing adjustment was followed by a passing focused presentation/interaction suite. Both standards and behavior review rechecks reported no remaining actionable findings.
