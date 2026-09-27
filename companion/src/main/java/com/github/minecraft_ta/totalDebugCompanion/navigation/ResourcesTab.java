package com.github.minecraft_ta.totalDebugCompanion.navigation;

/** The tabs of the Resources page, in display order. */
public enum ResourcesTab {
    FILES("Files"),
    PACKS("Packs");

    private final String title;

    ResourcesTab(String title) {
        this.title = title;
    }

    public String title() {
        return this.title;
    }
}
