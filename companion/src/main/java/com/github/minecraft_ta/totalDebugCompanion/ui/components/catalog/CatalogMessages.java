package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.catalog.PackCatalogService;

/** Why registered content cannot be shown, for each pack catalog state. */
public final class CatalogMessages {
    private CatalogMessages() {
    }

    /** Empty while the catalog is ready. */
    public static String unavailable(PackCatalogService.State state) {
        return switch (state) {
            case PackCatalogService.Ready ignored -> "";
            case PackCatalogService.None ignored -> "Connect Minecraft once to capture blocks, items and entity types";
            case PackCatalogService.Capturing ignored -> "Minecraft is capturing blocks, items and entity types";
            case PackCatalogService.Stale stale -> stale.detail();
            case PackCatalogService.Failed failed -> "The pack catalog could not be read: " + failed.detail();
        };
    }

    /** A short state for the Mods tree root; empty while the catalog is ready. */
    public static String status(PackCatalogService.State state) {
        return switch (state) {
            case PackCatalogService.Ready ignored -> "";
            case PackCatalogService.None ignored -> "not captured";
            case PackCatalogService.Capturing ignored -> "capturing";
            case PackCatalogService.Stale ignored -> "outdated";
            case PackCatalogService.Failed ignored -> "failed";
        };
    }
}
