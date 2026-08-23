package com.github.minecraft_ta.totalDebugCompanion.ui.theme;

import java.util.List;

/**
 * A selectable appearance: an IntelliJ UI theme plus the editor palette that belongs with it.
 *
 * @param id           stable identifier persisted in the settings file
 * @param displayName  shown in the settings dialog
 * @param dark         whether this is a dark theme (drives {@code _dark.svg} icon selection)
 * @param resourcePath classpath location of the flattened {@code .theme.json}
 * @param editor       code editor colours
 */
public record CompanionTheme(
        String id,
        String displayName,
        boolean dark,
        String resourcePath,
        EditorPalette editor
) {

    public static final CompanionTheme ISLANDS_DARK = new CompanionTheme(
            "islands-dark",
            "Islands Dark",
            true,
            "/themes/islands-dark.theme.json",
            EditorPalette.islandsDark()
    );

    public static final CompanionTheme ISLANDS_LIGHT = new CompanionTheme(
            "islands-light",
            "Islands Light",
            false,
            "/themes/islands-light.theme.json",
            EditorPalette.islandsLight()
    );

    public static final CompanionTheme DEFAULT = ISLANDS_DARK;

    private static final List<CompanionTheme> ALL = List.of(ISLANDS_DARK, ISLANDS_LIGHT);

    public static List<CompanionTheme> available() {
        return ALL;
    }

    /** Resolves a persisted id, falling back to {@link #DEFAULT} for unknown or absent values. */
    public static CompanionTheme byId(String id) {
        for (CompanionTheme theme : ALL) {
            if (theme.id.equals(id)) {
                return theme;
            }
        }
        return DEFAULT;
    }

    @Override
    public String toString() {
        return this.displayName;
    }
}
