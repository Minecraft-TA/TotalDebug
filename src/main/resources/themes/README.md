# Bundled themes

`islands-dark.theme.json` and `islands-light.theme.json` are JetBrains' **Islands** themes, taken
from intellij-community and licensed under Apache 2.0 (see `../icons/INTELLIJ_LICENSE.txt`).

They are **generated**, not hand-edited. Regenerate with:

```
python tools/flatten_intellij_theme.py <extracted-themes-dir> src/main/resources/themes
```

## Why they are generated

`com.formdev.flatlaf.IntelliJTheme` reads only the `colors`, `ui` and `icons` sections of a theme
file. Two things in the originals therefore need preprocessing:

1. **`parentTheme` is ignored by FlatLaf.** Islands sits two to three levels deep in a chain, so
   loading `ManyIslandsDark.theme.json` on its own yields a half-styled UI. The generator merges the
   chain into one self-contained file:
   - dark: `expUI_dark` &rarr; `ManyIslandsDark`
   - light: `expUI_light` &rarr; `expUI_light_with_light_header` &rarr; `ManyIslandsLight`
2. **Named colours may alias other named colours** (`"tool-window-bg": "gray-130"`). FlatLaf's
   `loadNamedColors` accepts literal colours only; an alias fails to parse, the name is dropped, and
   every `ui` entry referencing it goes with it. The visible symptom is a theme that collapses to
   Metal's fallback grey, or a `StackOverflowError` when the aliases nest deeply enough. The
   generator resolves all aliases down to literals.

`ThemeLoadingTest` guards both of these - it asserts that keys which exist only in the expUI parents
still resolve, and that each theme's chrome agrees with its declared darkness.

## Extracting the inputs

The source files live in a JetBrains IDE install, in
`lib/intellij.platform.ide.impl.jar`:

```
themes/islands/ManyIslandsDark.theme.json
themes/islands/ManyIslandsLight.theme.json
themes/expUI/expUI_dark.theme.json
themes/expUI/expUI_light.theme.json
themes/expUI/expUI_light_with_light_header.theme.json
```

Extract them flat, naming each file after its path with `/` replaced by `_` (for example
`themes_islands_ManyIslandsDark.theme.json`), which is the layout the generator expects.

Editor (syntax) colours are **not** in these files. They come from
`themes/islands/IslandSchemeDark.xml` and `themes/expUI/expUI_lightScheme.xml`, and are transcribed
by hand into `EditorPalette` - see the javadoc there.

Do not vendor anything from `plugins/rider-theme-pack/`; that jar is proprietary Rider content.
