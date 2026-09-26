# Companion UI guide

Status: rules in force from 2026-09-26. Every Companion screen follows them; a change that needs an exception records it here first. The last section lists where the current UI still differs; fix those when touching the code, not by adding workarounds.

[UI_AUDIT.md](UI_AUDIT.md) is the dated audit of September 2026 and tracks its own open work; where the two disagree, this guide wins.

Class names below are in `companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/`.

## Principles

- **IntelliJ's New UI is the reference.** Companion looks and behaves like a JetBrains IDE: its icons, its capitalization, its speed search, its menus. When this guide is silent, do what IntelliJ does.
- **The mod and definition pages are the house style.** They are the most finished screens; new pages copy their anatomy, and older screens move toward it.
- **Text serves an action.** A label names what it labels, a message says what happened and what to do; no subtitles, welcome text, or explanations of the obvious (see the root `AGENTS.md`).
- **One way per job.** The same kind of thing looks and behaves the same everywhere: one table setup, one button per role, one menu order, one way to type into a view.
- **Mouse and keyboard reach the same actions.** Nothing important lives only in a context menu or only behind a shortcut.
- **The layout stays still.** Opening, editing or saving does not make lines appear and push content around.

## Text

### Capitalization

| Kind | Case | Examples |
|---|---|---|
| Commands: menu items, buttons, action names in tooltips, window titles | Title Case, as IntelliJ: every word capitalized except articles, conjunctions and prepositions of up to three letters | Find Usages, Copy Path, Show in Explorer, Reset to Default, Browse Code, Revert All |
| Everything else: labels, check boxes, headings, page, tab and tree names, placeholders, messages, tooltip prose | Sentence case | Key bindings, Entity types, Show inline diagnostics, Filter by action, mod or key |

Ids, file names and key syntax keep their own case inside either style: Find Usages of key.jump.

### Words

- A command that needs more input before it acts ends with an ellipsis character: Choose Key…, Open…. Never three dots.
- Say what happens, with the object when it is not obvious: Unbind 3, Revert to Space, Show in Key Bindings.
- One word per concept:

| Use | Not | For |
|---|---|---|
| Open Source | Jump to Source, Go to Source | Opening code at a location |
| Copy Path, Copy Reference, Copy Name, Copy Value, Copy ID | Copy (bare), Copy Location for a path | Copying one representation; the noun says which |
| Remove | Delete | Taking an entry out of a Companion list (watch, breakpoint, recent project) |
| Delete | Remove | Deleting a file |
| Reset to Default | Restore | Writing a setting's or binding's default |
| Revert, Revert to X | Undo, Restore | Writing back the value Companion replaced |
| Show in X | Reveal, Navigate to | Selecting the object in another view |

### Form and punctuation

- No separator glyphs between parts of a line: no middle dot, vertical bar or bullet. Separate by spacing, by muted secondary text, or by a secondary column; inside one value use commas. Breadcrumbs use `›`, which shows a path, not a list.
- Labels have no trailing colon, in forms as in fact grids.
- Numbers are formatted with `NumberFormat.getIntegerInstance(Locale.ROOT)`; counts follow their name in muted text (Items 12).
- Keys in text use English names and `+`: Ctrl+Shift+F, Left Shift, Mouse Button 4.
- Placeholders say what the filter matches, with an example when the syntax is not obvious: Filter by action, mod or key, such as ctrl+g.
- Messages name the object and the reason: "Jump, Sneak were not changed: The game disconnected before it answered". A list of more than three names ends with "and N more".

## Type and color

- **One emphasis.** The title of a page or window uses FlatLaf's `h3` style class. Everything else is regular text: section headings, form groups, list names, dialog titles inside the window. Emphasis between items comes from color, position and muted secondary text, not weight. Drawn item counts on slots, which copy Minecraft, and the initials of a monogram tile are the only bold text.
- **Colors come from roles**, never literals: [`ThemeColors`](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/theme/ThemeColors.java) for text, secondary, muted, accent, link, error, warning and success, [`EditorPalette`](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/theme/EditorPalette.java) for code-like values. Components keep a role across theme changes with `ThemeColors.keepForeground`.
- **Secondary is not disabled.** Information people read (context, counts, defaults, paths) uses `secondaryText`; `mutedText` is for placeholders and unavailable things.
- **Values look like code of their kind:** numbers, booleans and strings in the editor's literal colors, strings quoted; the same value keeps its color while edited.
- **State marks:**
  - A 3 px bar at the row's left edge marks a changed value: accent when Companion changed it, muted when it merely differs from its default.
  - The previous or default value follows in secondary text: "was Space", "default 4".
  - A collision or error outlines the value in the error color; an overlap or warning in the warning color.

## Icons

1. **Take IntelliJ's own icon** when one exists for the concept: the `expui` set in `lib/intellij.platform.ide.jar` of an installed JetBrains IDE, or [the icon browser](https://intellij-icons.jetbrains.design/). Copy it unchanged with its `_dark` pair.
2. **Otherwise draw one** in the same style: 16 × 16 view box, 1 px strokes, rounded joins, a light fill with a darker stroke from the existing palette, and a matching `_dark` pair. Examples: `item`, `fluid`, `sound`, `modpack`.
3. **Record every icon** in `companion/src/main/resources/icons/README.md`, with its `expui` path or as custom. `IconThemeSwitchTest` checks the dark pairs.
4. **One icon per concept, from one place:** kinds of content from `ContentKinds`, mod page tabs and subjects from `SubjectIcons`, files from `FileTypeResolver`, the rest from `Icons`. A view never picks its own icon for a concept that already has one.
5. **Sizes:** 16 px in rows, menus, tabs and buttons; item, block and texture previews at the `UiMetrics` sizes 32, 48 and 64, always a whole multiple of 16 through `UiMetrics.previewPixels`; pixel art is never smoothed ([`PixelImages`](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/components/PixelImages.java)).
6. **Menus show icons** only for commands that have an established one in IntelliJ, such as Copy, Delete and Open Source; an icon never stands in for missing text.

## Layout

### Window

The Project tree on the left, editor tabs in the middle, the status bar at the bottom. Every page opens as an editor tab and takes part in navigation history; windows beside the main window are for tools that live alongside the editor, such as the debugger. Browsing never happens in a modal dialog.

### Page anatomy

1. **Header** ([`SubjectHeader`](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/components/subject/SubjectHeader.java)): a 64 px preview, the `h3` title, one identifying line of muted parts and links (kind, id, owning mod, the mod's website; for something in the world its position and dimension), and page-level controls at the right. Without a preview the header shows a tile: a mod's initials (`MonogramIcon`) or its kind's icon (`PlateIcon`).
2. **Tabs** with the view's name and a muted count, set with [`TabTitles.setCounted`](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/components/TabTitles.java). A tab with nothing to show is hidden, not disabled.
3. **Tab content** following one of the view patterns below.

### View patterns

- **Facts page:**
  - Sections with a chevron, a regular title and a rule ([`SectionHeading`](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/components/SectionHeading.java)).
  - Each section holds a grid of muted labels and values; links show their type icon.
  - A section collapses only from its chevron.
  - A section a project script reported names the script at the right of its heading, as a link that opens it.
  - The files behind the subject come last, in a Files section of roles and links (`PageSection.linkRows`).
- **Browser:**
  - **Filter bar:** the filter field fills the width; an option of the filter itself, such as searching by pressing a key, is a toggle inside the field. At its right come, in order, the filter check boxes, the view switch, then page actions.
  - **Table or list** below the bar.
  - **Empty or failed state:** replaces the table in the same place.
- **Two-pane browser:**
  - Categories with muted counts in a 190 px list on the left, All first, then the entries of the selected category ([`ContentBrowser`](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/components/catalog/ContentBrowser.java), `ResourceBrowser`).
  - The category list is left out when there is only one category.
  - All shows a column naming each entry's category.
- **Pack-wide and per-mod views are one component:** the pack-wide one adds a Mod column. A new pack-wide view reuses the per-mod component, or the other way round.

### Where details go

- A row's details go in its tooltip; a subject's details go on its own page.
- No descriptions that expand inline, and no detail panel under a list.
- A form beside a list is right for editing the selected entry, as in the breakpoint manager.

### Spacing

| Place | Insets (top, left, bottom, right) | In `UiMetrics` |
|---|---|---|
| Filter bar and toolbars around a view | 6, 8, 6, 8 | `barPadding()` |
| Empty and failed messages | 10, 12, 10, 12 | `messagePadding()` |
| Line under a filter bar, such as a failure | 0, 10, 6, 10 | `noticePadding()` |
| Table cell text | 0, 6, 0, 6 | `cellPadding()` |
| List row | 4, 10, 4, 10 | `listRowPadding()` |
| Page content: header, sections, notices | own top and bottom, 12 at both sides | `pagePadding(top, bottom)` |
| Body of a section under its heading | 4, 18, 0, 0 | `sectionBodyPadding()` |
| Form: label to field, and between rows | 10 and 8 | `formLabelGap()`, `formRowGap()` |
| Small icon button in a popup or beside a value | margin 2, 3, 2, 3 | `compactButtonMargin()` |
| Status bar widget | margin 0, 6, 0, 6 | `statusWidgetMargin()` |
| Indent per tree or group level | 16 | |
| Width of a category list | 190 | |

A component's own inner padding, such as a text field's or an editor's, and a gap between two parts of one control are not page spacing; they stay with the component. Rows are 24 px for text (`UiMetrics.TREE_ROW_HEIGHT`) and the preview size plus 6 when a row shows a preview. A value used in more than one place becomes a constant in [`UiMetrics`](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/UiMetrics.java). A row without an icon among rows with icons uses `Icons.NONE` to stay level.

### Staying still

- Content does not move while someone works in it.
- A line that appears for a state, such as a failure notice, shows only for failures; success shows in the changed row itself.
- Loading replaces content in place.
- Refreshes keep selection, scroll position and expanded groups.

## Controls

| Role | Control | Examples |
|---|---|---|
| Command in a toolbar or filter bar | Icon-only toolbar button, [`FlatIconButton`](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/components/FlatIconButton.java); its toggle variant for a mode that stays on | Run, Zoom In, Press to Search |
| Page-level command | Text button with its icon, Title Case | Browse Code, Open, Save, Discard |
| Switching between views of the same content | Segmented toggle, [`SegmentedToggle`](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/components/SegmentedToggle.java) | Settings / File, Tree / SNBT, Server / Client |
| Narrowing what a list shows | Check box, sentence case | Modified, Changed, Collisions, Not bound |
| Submitting or leaving a form or dialog | Default bordered button; the safe choice is the default | Close, Done, Cancel |

- **Tooltips:**
  - Every icon-only control has one: its action name in Title Case, with its shortcut muted beside it, built with `Tooltip.action("Step Over", "F8")`.
  - An icon-only control also sets its accessible name to the plain action name, since a tooltip with markup would otherwise be read aloud.
  - Every tooltip goes through the [`Tooltip`](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/Tooltip.java) builder: title, muted detail, prose, facts with value colors, and code capped to a readable size.
  - A tooltip only displays; nothing reads data back from its text.
  - Paths are shortened with `Tooltip.shortPath`.
- **Dialogs:**
  - Only to confirm what cannot be undone or affects many things.
  - The text says what will happen to how many things: "Put back the original value of 12 changes?".
  - The button names the action.
- **Editing in place:**
  - Double-click, Enter or F2 starts an edit.
  - Escape cancels; Enter or leaving the field commits.
  - A refused value stays editable, with the reason next to it.

## Lists, trees and tables

### Rows

- A row shows its name in primary text and its context in muted secondary text, through [`PrimarySecondaryLabel`](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/presentation/PrimarySecondaryLabel.java), which also paints speed-search matches.
- Counts follow names in secondary text.
- A group with nothing in it is not shown.

### Tables

- **One setup**, [`Tables.configure`](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/components/Tables.java):
  - no grid, and the table fills the viewport;
  - left-aligned headers, and no column reordering;
  - 24 px rows, 6 px cell padding, and a narrow unsortable icon column;
  - sorting only where ordering by a column helps.
- **Grouped rows** use [`GroupedRowCell`](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/components/GroupedRowCell.java) in the first column, with `GroupedRowCell.onChevron` for clicks:
  - Group headings carry a chevron, a count, and 16 px of indent per level; the text of every level lines up.
  - Clicking the chevron, or Left and Right, collapses and expands.
  - While a filter is active, every match shows regardless of collapsed groups.
- **Opening:** Enter or a double-click opens the row's page. Selecting a row never navigates.

### Selection

- Where rows have actions, a table or list allows several rows to be selected: Shift- and Ctrl-click and Ctrl+A.
- A selected group heading stands for the rows under it that pass the filter.
- Lists whose selection drives something else stay single-selection: choosers, and the category list of a two-pane browser.

### Trees

- Trees follow the Project tree: JetBrains chevrons, and 24 px rows with muted counts.
- The order of rows is fixed by sort priority.
- Activating a row opens its page; a row only expands from its chevron or the keyboard.

## Typing and search

- **A view with a filter field:** typing anywhere in the view goes into the field ([`TypeToFilter`](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/components/TypeToFilter.java)), and Down moves from the field into the list. The filter hides rows that don't match, and it matches everything the row shows: name, id, mod, and key syntax such as ctrl+g.
- **A view without a filter field:** typing starts IntelliJ-style speed search (`ui/speedsearch`). It highlights matches and moves between them, and hides nothing.
- **Search Everywhere** finds every kind of thing that has a page: mods, content of every kind, key bindings, resources, classes, symbols and text. A new kind of page adds its results there.
- **Capture modes win:** while a view waits for a key press, it takes every key, including global shortcuts. It marks itself with `DebuggerShortcuts.TAKES_ALL_KEYS`.

## Context menus

- **Install:**
  - Install menus through [`ContextMenus`](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/ContextMenus.java) (`installTable`, `installList`, `installTree`), which give Shift+F10, the Menu key and Ctrl+C.
  - A right-click inside a selection keeps it; a right-click outside selects the row under the pointer; a right-click on empty space opens nothing.
- **Order:**
  - Open and navigate first, then work on the object, then bulk work, then copy, then removal last.
  - Separators go between the groups; no submenus unless one object really has several representations to copy.
- **Copy:** the command Ctrl+C runs is marked with `ContextMenus.defaultCopy`.
- **Several rows:**
  - With several rows selected, the menu acts on all of them and names the count: Reset 4 to Default, Unbind 3, Copy 12 Names.
  - An action that applies to none of them stays visible, disabled and without a count.
- **Shortcuts:** menus show their shortcut; the removal command also has the Delete key.
- **Applicability:** hide commands that never apply to the object, and disable those that don't apply right now.
- **No hidden essentials:** everything essential is also reachable without the menu.

## Feedback and state

| What | Where |
|---|---|
| A value that cannot be accepted | Next to the field |
| Progress of a running operation, with Cancel | Beside the operation |
| Empty, loading or failed content, with a retry where one helps | In place of the content |
| Game, MCP and index state | The status bar: each widget shows its name in secondary text and then its state (`HtmlText.nameAndValue`) |
| Something that failed after it was asked for | A line under the view's bar until the next action |

- Success is silent when the UI already shows the result, such as the new key in its row.
- A state the game must be in for something to work is named in the view: "The pack catalog is not captured yet.", via `CatalogMessages`.
- Every change Companion writes to the pack is recorded as a change and can be reverted from the view or from Changes.

## Keyboard and focus

- Enter opens or confirms. Escape cancels a capture, an edit or a popup. Delete removes. F2 edits. Ctrl+A selects all.
- Focus is always visible. Toolbar buttons can be reached and pressed from the keyboard, and a click on them leaves focus where it was (`FlatIconButton` does both).
- Shortcuts in text and menus use English key names; the application sets the English locale before Swing starts, so Swing's own key names and dialog buttons follow.

## Building and checking

- Swing work on the event thread; reading files and the catalog off it. A load checks a generation counter, so a stale result never replaces a newer one.
- Everything follows a theme switch: SVG icons with dark pairs, colors from roles, and no colors cached in fields.
- Sizes go through `UIScale` and `UiMetrics`; no fixed pixel sizes for text.
- Every new page or state gets a `UiRenderScenario`. Check it with `./gradlew :companion:uiHarness --args="--scenario=<id> --screenshot=<file>"` in both themes before asking for review.

## Where the UI differs today

Nothing known. Record a difference here when a change has to leave one behind.
