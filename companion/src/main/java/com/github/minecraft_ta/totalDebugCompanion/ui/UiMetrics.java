package com.github.minecraft_ta.totalDebugCompanion.ui;

import com.formdev.flatlaf.util.UIScale;

/** Application-wide logical dimensions that every theme and component must preserve. */
public final class UiMetrics {
    public static final int TREE_ROW_HEIGHT = 24;
    public static final int TAB_HEIGHT = 30;
    public static final int TAB_SEPARATOR_HEIGHT = 1;
    public static final int STATUS_BAR_HEIGHT = 24;
    /** Previews in editor tabs and tree rows, the largest size that fits {@link #TAB_HEIGHT} and tree rows. */
    public static final int ROW_ICON_SIZE = 16;
    /** Item, block and mod previews in search results, lists and slots. */
    public static final int ITEM_ICON_SIZE = 32;
    /** Item, block and mod previews at the top of a subject page. */
    public static final int HEADER_ICON_SIZE = 64;
    /** Texture thumbnails in resource grids and on definition pages. */
    public static final int THUMBNAIL_SIZE = 48;

    private UiMetrics() {
    }

    /**
     * A preview size after the UI scale, rounded down to a whole multiple of 16 so that every pixel of a 16 x 16
     * texture covers the same number of screen pixels.
     */
    public static int previewPixels(int size) {
        return Math.max(16, UIScale.scale(size) / 16 * 16);
    }
}
