package com.github.minecraft_ta.totalDebugCompanion.navigation;

/** The tabs of the World page, in display order. */
public enum WorldTab {
    OVERVIEW("Overview"),
    GAME_RULES("Game rules"),
    DATAPACKS("Datapacks");

    private final String title;

    WorldTab(String title) {
        this.title = title;
    }

    public String title() {
        return this.title;
    }
}
