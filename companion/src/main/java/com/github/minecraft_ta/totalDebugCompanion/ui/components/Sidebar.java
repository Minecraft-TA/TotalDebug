package com.github.minecraft_ta.totalDebugCompanion.ui.components;

import com.formdev.flatlaf.util.UIScale;
import com.github.minecraft_ta.totalDebugCompanion.GlobalConfig;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.Objects;

/**
 * A list beside its content, such as categories or files (docs/UI_GUIDE.md, Sidebars). The divider can be dragged, down
 * to a width where the list's rows still read, and every view of the same kind starts at the width it was last dragged
 * to, across restarts; until then it starts at its default. A hidden sidebar gives its content the whole width.
 */
public final class Sidebar extends JPanel {
    private static final int MINIMUM_WIDTH = 120;

    private final String kind;
    private final int defaultWidth;
    private final JComponent sidebar;
    private final JComponent content;
    private final ThinSplitPane split;
    private boolean shown = true;

    /**
     * {@code kind} names the views that share a width, such as {@code resource-categories} for every Resources view;
     * {@code defaultWidth} is before scaling.
     */
    public Sidebar(String kind, int defaultWidth, JComponent sidebar, JComponent content) {
        super(new BorderLayout());
        this.kind = Objects.requireNonNull(kind, "kind");
        this.defaultWidth = defaultWidth;
        this.sidebar = Objects.requireNonNull(sidebar, "sidebar");
        this.content = Objects.requireNonNull(content, "content");
        sidebar.setMinimumSize(new Dimension(UIScale.scale(MINIMUM_WIDTH), 0));
        this.split = new ThinSplitPane(sidebar, content);
        this.split.setDividerLocation(width());
        this.split.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, event -> {
            // Only a drag in a visible view changes the width; laying out a hidden one does not.
            if (this.split.isShowing() && this.shown) GlobalConfig.getInstance().setSidebarWidth(this.kind, this.split.getDividerLocation());
        });
        add(this.split, BorderLayout.CENTER);
    }

    /** Shows or hides the sidebar, such as a category list with only one category. */
    public void setSidebarShown(boolean shown) {
        if (this.shown == shown) return;
        this.shown = shown;
        removeAll();
        if (shown) {
            this.split.setLeftComponent(this.sidebar);
            this.split.setRightComponent(this.content);
            this.split.setDividerLocation(width());
            add(this.split, BorderLayout.CENTER);
        } else {
            add(this.content, BorderLayout.CENTER);
        }
        revalidate();
        repaint();
    }

    public boolean sidebarShown() {
        return this.shown;
    }

    /** The width the sidebar was last dragged to in this kind of view, or its default. */
    private int width() {
        Integer dragged = GlobalConfig.getInstance().sidebarWidth(this.kind);
        return dragged != null ? dragged : UIScale.scale(this.defaultWidth);
    }
}
