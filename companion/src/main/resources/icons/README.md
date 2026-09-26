# Icons

Most icons use JetBrains' New UI (`expui`) icon set, taken from intellij-community and licensed under Apache 2.0.
See [INTELLIJ_LICENSE.txt](INTELLIJ_LICENSE.txt).

`mod.svg` and `mod_dark.svg` are custom extension-piece icons.
`modpack.svg` and `modpack_dark.svg` are custom as well: the extension piece rising out of an open box.
`save.svg` (`general/save`), `revert.svg` (`vcs/revert`), `changes.svg` (`general/history`, used for Companion's
change record) and `keyboard.svg` (`general/keyboard`), each with its `_dark` pair, are unchanged copies from `platform/icons/src/expui` in
[intellij-community](https://github.com/JetBrains/intellij-community/tree/master/platform/icons/src/expui).
`item.svg` and `item_dark.svg` are custom item-tag icons, `fluid.svg` a custom droplet and `sound.svg` a custom speaker, each with its `_dark` pair. `grid.svg` (the image viewer's pixel grid) and `pause.svg` (pausing texture animations) are custom as well. All of them use the existing icon set's stroke weights and palette.

Completion modifier/visibility icons (`finalMark`, `staticMark`, `accessPrivate`, `accessProtected`,
`accessLocal`) are the matching light/dark assets from `platform/icons/src/expui/nodes` in
[intellij-community](https://github.com/JetBrains/intellij-community/tree/master/platform/icons/src/expui/nodes).

`runServer.svg` and `runServer_dark.svg` combine the unchanged JetBrains `run/run` triangle with a custom
8 × 7 px server badge and one green LED pixel on the upper row. The triangle is cut out at the badge's
rounded outer edge without an extra gap or halo.
They retain the upstream Apache 2.0 notice and light/dark palette.

Each icon ships as a light/dark pair, `<name>.svg` and `<name>_dark.svg`. That is FlatLaf's
convention: a single `FlatSVGIcon` resolves the `_dark` sibling on its own when the active look and
feel is dark, and re-resolves it when the theme changes - which is what lets the shared constants in
`Icons.java` follow the theme. `IconThemeSwitchTest` guards all of this, including that no icon is
missing its dark variant.

Window icons are the exception because AWT requires raster `Image` instances. Windows call
`Icons.createWindowIconImages` again on each theme change so the factory can rasterize the matching
light or dark SVG.

## Where they came from

The assets are from `lib/intellij.platform.ide.jar` in a JetBrains IDE install or the corresponding
[IntelliJ Platform Icons](https://intellij-icons.jetbrains.design/) entry. The file name here is the
Companion's own name for the icon; the second column is the upstream `expui` path.

| here | expui | | here | expui |
|---|---|---|---|---|
| class | `nodes/class` | | search | `general/search` |
| method | `nodes/method` | | matchCase | `inline/matchCase` |
| field | `nodes/field` | | regex | `inline/regex` |
| enum | `nodes/enum` | | close | `general/closeSmall` |
| interface | `nodes/interface` | | closeHovered | `general/closeSmallHovered` |
| constructor | `nodes/constructor` | | delete | `general/delete` |
| constant | `nodes/constant` | | template | `nodes/template` |
| property | `nodes/property` | | copy | `general/copy` |
| variable | `nodes/variable` | | information | `status/info` |
| javaFile | `fileTypes/java` | | success | `status/success` |
| | | | warning | `status/warning` |
| jar | `fileTypes/archive` | | error | `status/error` |
| text | `fileTypes/text` | | primitive | `debugger/dbPrimitive` |
| run | `run/run` | | value | `debugger/value` |
| stop | `run/stop` | | array | `json/array` |
| debug | `run/debug` | | resume | `run/resume` |
| stepOver | `run/stepOver` | | stepInto | `run/stepInto` |
| stepOut | `run/stepOut` | | breakpoint | `breakpoints/breakpoint` |
| breakpointValid | `breakpoints/breakpointValid` | | breakpointInvalid | `breakpoints/breakpointInvalid` |
| breakpointMethod | `breakpoints/breakpointMethod` | | breakpointMethodValid | `breakpoints/breakpointMethodValid` |
| questionBadge | `breakpoints/questionBadge` | | detach | `CidrDebuggerIcons/icons/expui/detach` |
| | | | arrow_down | `general/chevronDown` |
| previousOccurrence | `general/chevronUp` | | nextOccurrence | `general/chevronDown` |
| jumpToSource | [`general/edit`](https://github.com/JetBrains/intellij-community/blob/master/platform/icons/src/expui/general/edit.svg) | | | |
| zoomIn | `image/zoomIn` | | zoomOut | `image/zoomOut` |
| fitContent | `image/fitContent` | | actualZoom | `image/actualZoom` |
| reformatCode | `actions/reformatCode` | | | |

File-tree and navigation assets:

| here | expui | | here | expui |
|---|---|---|---|---|
| folder | `nodes/folder` | | package | `nodes/package` |
| sourceRoot | `nodes/sourceRoot` | | resourcesRoot | `nodes/resourcesRoot` |
| library | `nodes/library` | | resourceBundle | `nodes/resourceBundle` |
| module | `nodes/module` | | image | `fileTypes/image` |
| json | `fileTypes/json` | | config | `fileTypes/config` |
| xml | `fileTypes/xml` | | yaml | `fileTypes/yaml` |
| propertiesFile | `fileTypes/properties` | | markdown | `fileTypes/markdown` |
| manifest | `fileTypes/manifest` | | binaryData | `fileTypes/binaryData` |
| html | `fileTypes/html` | | css | `fileTypes/css` |
| javaScript | `fileTypes/javaScript` | | font | `fileTypes/font` |
| settings | `general/settings` | | filter | `general/filter` |
| implementedMethod | `gutter/implementedMethod` | | implementingMethod | `gutter/implementingMethod` |
| overriddenMethod | `gutter/overridenMethod` | | overridingMethod | `gutter/overridingMethod` |

Taken unchanged from `expui` as well, with their `_dark` pairs:

| here | expui | | here | expui |
|---|---|---|---|---|
| arrow_right | `general/chevronRight` | | refresh | `general/refresh` |
| addToWatch | `debugger/addToWatch` | | watch | `debugger/watch` |
| evaluateExpression | `run/evaluateExpression` | | parameter | `nodes/parameter` |
| viewBreakpoints | `run/viewBreakpoints` | | muteBreakpoints | `run/muteBreakpoints` |
| breakpointDisabled | `breakpoints/breakpointDisabled` | | breakpointMuted | `breakpoints/breakpointMuted` |
| breakpointMutedDisabled | `breakpoints/breakpointMutedDisabled` | | breakpointMethodMuted | `breakpoints/breakpointMethodMuted` |
| breakpointMethodMutedDisabled | `breakpoints/breakpointMethodMutedDisabled` | | moveToFolder | `actions/moveToButton` |
| notifications | `toolwindows/notifications` | | web | `toolwindows/web` |
| expand_editor | `inline/expand` | | collapse_editor | `inline/collapse` |

Custom, in the same stroke weights and palette: `content` (four squares in the colors of the content kinds, for all
registered content), `block` (an isometric cube for block types), `entity` (a face for entity
types), `script` (Companion's script file) and `companion` (the application icon), each with its `_dark` pair.
`prism.svg` is Prism Launcher's logo, with its SVG metadata kept, and `prism-instance.svg` its grass instance icon;
see [PRISM_NOTICE.txt](PRISM_NOTICE.txt).

Folder actions use JetBrains `expui/actions/newFolder`, `expui/actions/moveToButton`, and `expui/general/edit` for rename, with their matching dark variants.
