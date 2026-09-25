package com.github.minecraft_ta.totalDebugCompanion.navigation;

/** The tabs of a mod page, in display order. */
public enum ModTab {
    OVERVIEW("Overview"),
    CONTENT("Content"),
    CONFIGURATION("Configuration"),
    KEY_BINDINGS("Key bindings"),
    RESOURCES("Resources");

    private final String title;

    ModTab(String title) {
        this.title = title;
    }

    public String title() {
        return this.title;
    }
}
