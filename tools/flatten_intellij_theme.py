#!/usr/bin/env python3
"""
Flattens an IntelliJ .theme.json parentTheme chain into a single self-contained theme file.

FlatLaf's com.formdev.flatlaf.IntelliJTheme reads only the "colors", "ui" and "icons" sections of a
theme file -- it does not resolve "parentTheme". IntelliJ's Islands themes sit two to three levels
deep in such a chain, so loading one directly yields a half-styled UI. This script performs the
merge that FlatLaf will not, so the app can ship one flat file per theme.

Inputs come from a JetBrains IDE install (Apache-2.0 intellij-community content):
    <IDE>/lib/intellij.platform.ide.impl.jar
        themes/islands/ManyIslandsDark.theme.json
        themes/islands/ManyIslandsLight.theme.json
        themes/expUI/expUI_dark.theme.json
        themes/expUI/expUI_light.theme.json
        themes/expUI/expUI_light_with_light_header.theme.json

Usage:
    python tools/flatten_intellij_theme.py <extracted-themes-dir> <output-dir>
"""
import json
import re
import sys
from pathlib import Path

# "#RRGGBB", "RRGGBB", "#RGB", "RRGGBBAA" ... anything FlatLaf can parse as a literal colour
LITERAL_COLOUR = re.compile(r"^#?[0-9a-fA-F]{3,8}$")

# child last; each entry is (filename, ...) walked root -> leaf
CHAINS = {
    "islands-dark.theme.json": [
        "themes_expUI_expUI_dark.theme.json",
        "themes_islands_ManyIslandsDark.theme.json",
    ],
    "islands-light.theme.json": [
        "themes_expUI_expUI_light.theme.json",
        "themes_expUI_expUI_light_with_light_header.theme.json",
        "themes_islands_ManyIslandsLight.theme.json",
    ],
}


def deep_merge(base, overlay):
    """Recursive dict merge; overlay wins on leaves."""
    result = dict(base)
    for key, value in overlay.items():
        if isinstance(value, dict) and isinstance(result.get(key), dict):
            result[key] = deep_merge(result[key], value)
        else:
            result[key] = value
    return result


def resolve_colour_references(colors):
    """
    Collapses name -> name indirection in the "colors" section.

    IntelliJ lets a named colour alias another named colour ("tool-window-bg": "gray-130").
    FlatLaf's IntelliJTheme.loadNamedColors only accepts literal colours: an alias fails to parse
    and the name is dropped, which in turn strips every "ui" entry that referenced it. The visible
    result is a theme that collapses to the fallback Metal grey, or - when the aliases nest deeply
    enough - a StackOverflowError while FlatLaf resolves the generated references.
    """
    resolved = {}
    for name in colors:
        seen, current = [], name
        while True:
            value = colors.get(current)
            if value is None:
                raise ValueError(f"colour {name!r} resolves to unknown name {current!r}")
            if LITERAL_COLOUR.match(value):
                resolved[name] = value
                break
            if current in seen:
                raise ValueError(f"circular colour reference: {' -> '.join(seen + [current])}")
            seen.append(current)
            current = value
    return resolved


def flatten(source_dir, names):
    merged = {"colors": {}, "ui": {}, "icons": {}}
    leaf = None
    for name in names:
        theme = json.loads((source_dir / name).read_text(encoding="utf-8"))
        leaf = theme
        for section in ("colors", "ui", "icons"):
            if section in theme:
                merged[section] = deep_merge(merged[section], theme[section])
    merged["colors"] = resolve_colour_references(merged["colors"])
    out = {
        "name": leaf["name"],
        "dark": leaf["dark"],
        "author": leaf.get("author", "JetBrains"),
        "colors": merged["colors"],
        "ui": merged["ui"],
    }
    if merged["icons"]:
        out["icons"] = merged["icons"]
    return out


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 1
    source_dir, output_dir = Path(sys.argv[1]), Path(sys.argv[2])
    output_dir.mkdir(parents=True, exist_ok=True)
    for output_name, chain in CHAINS.items():
        theme = flatten(source_dir, chain)
        target = output_dir / output_name
        target.write_text(json.dumps(theme, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        aliases = sum(1 for v in theme["colors"].values() if not LITERAL_COLOUR.match(v))
        assert aliases == 0, f"{output_name} still has {aliases} unresolved colour aliases"
        print(f"{target}  name={theme['name']!r} dark={theme['dark']} "
              f"colors={len(theme['colors'])} ui={len(theme['ui'])} unresolved-aliases={aliases}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
