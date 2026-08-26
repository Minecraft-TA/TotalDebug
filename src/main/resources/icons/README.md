# Icons

JetBrains' **New UI** (`expui`) icon set, taken from intellij-community and licensed under Apache 2.0
(see `INTELLIJ_LICENSE.txt`). They replaced the pre-New-UI 2021 icons this project previously
vendored, which had colours baked in for a dark background only.

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
| constant | `nodes/constant` | | download | `general/download` |
| property | `nodes/property` | | copy | `general/copy` |
| variable | `nodes/variable` | | information | `status/info` |
| javaFile | `fileTypes/java` | | success | `status/success` |
| classFile | `fileTypes/javaClass` | | warning | `status/warning` |
| jar | `fileTypes/archive` | | error | `status/error` |
| text | `fileTypes/text` | | primitive | `debugger/dbPrimitive` |
| run | `run/run` | | value | `debugger/value` |
| stop | `run/stop` | | array | `json/array` |
| pause | `run/pause` | | clock | `general/history` |
| debug | `run/debug` | | resume | `run/resume` |
| stepOver | `run/stepOver` | | stepInto | `run/stepInto` |
| stepOut | `run/stepOut` | | breakpoint | `breakpoints/breakpoint` |
| breakpointDependent | `breakpoints/breakpointDependent` | | | |
| detach | `CidrDebuggerIcons/icons/expui/detach` | | | |
| clear | `actions/clearCash` | | upDown | `diff/arrowLeftRight` |
| runServer | `actions/deploy` | | overlayMode | `general/layout` |
| arrow_right | `general/chevronRight` | | arrow_down | `general/chevronDown` |
| previousOccurrence | `general/chevronUp` | | nextOccurrence | `general/chevronDown` |
| target | `general/locate` | | decompile | `actions/preview` |
| block | `general/remove` | | | |

The file-tree polish set uses the following additional New UI assets:

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

`decompile` and `block` have no exact New UI counterpart; those two are judgement calls and are the
first place to look if an icon reads wrong.

`process/step_1..8.svg` is the spinner driven by `AnimatedFlatSVGIcon`. It is still the older
JetBrains asset and has no dark variant.
