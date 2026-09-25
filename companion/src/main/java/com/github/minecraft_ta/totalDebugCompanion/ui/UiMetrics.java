package com.github.minecraft_ta.totalDebugCompanion.ui;

import com.formdev.flatlaf.util.UIScale;

import javax.swing.BorderFactory;
import javax.swing.border.Border;
import java.awt.Insets;

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

    /** Around a filter bar or toolbar at the top of a view. */
    public static Border barPadding() {
        return BorderFactory.createEmptyBorder(6, 8, 6, 8);
    }

    /** Around an empty or failed message shown in place of a view's content. */
    public static Border messagePadding() {
        return BorderFactory.createEmptyBorder(10, 12, 10, 12);
    }

    /** Around a line under a filter bar, such as a failure. */
    public static Border noticePadding() {
        return BorderFactory.createEmptyBorder(0, 10, 6, 10);
    }

    /** Around a row of a list. */
    public static Border listRowPadding() {
        return BorderFactory.createEmptyBorder(4, 10, 4, 10);
    }

    /** Content of a page keeps this distance from the page's left and right edges. */
    public static final int PAGE_EDGE = 12;
    /** Between a form's label and its field. */
    public static final int FORM_LABEL_GAP = 10;
    /** Between the rows of a form. */
    public static final int FORM_ROW_GAP = 8;

    /** Around a block of page content, such as the header, a section or a notice, with its own space above and below. */
    public static Border pagePadding(int top, int bottom) {
        return BorderFactory.createEmptyBorder(top, PAGE_EDGE, bottom, PAGE_EDGE);
    }

    /** Around the body of a section, indented under its heading's chevron. */
    public static Border sectionBodyPadding() {
        return BorderFactory.createEmptyBorder(4, 18, 0, 0);
    }

    /** Margin of a small icon button inside a popup or beside a value, such as Copy. */
    public static Insets compactButtonMargin() {
        return new Insets(2, 3, 2, 3);
    }

    /** Margin of a widget in the status bar, whose height is fixed. */
    public static Insets statusWidgetMargin() {
        return new Insets(0, 6, 0, 6);
    }

    /** Around the text of a table cell. */
    public static Border cellPadding() {
        return BorderFactory.createEmptyBorder(0, 6, 0, 6);
    }

    /**
     * A preview size after the UI scale, rounded down to a whole multiple of 16 so that every pixel of a 16 x 16
     * texture covers the same number of screen pixels.
     */
    public static int previewPixels(int size) {
        return Math.max(16, UIScale.scale(size) / 16 * 16);
    }
}
