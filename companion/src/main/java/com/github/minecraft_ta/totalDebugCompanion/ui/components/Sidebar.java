package com.github.minecraft_ta.totalDebugCompanion.ui.components;

import com.formdev.flatlaf.util.UIScale;
import com.github.minecraft_ta.totalDebugCompanion.GlobalConfig;
import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Objects;

/**
 * A list beside its content, such as categories or files (docs/UI_GUIDE.md, Sidebars). The divider can be dragged, down
 * to a width where the list's rows still read, and every view of the same kind starts at the width it was last dragged
 * to, across restarts and UI scales; until then it starts at its default. A hidden sidebar gives its content the whole
 * width.
 */
public final class Sidebar extends JPanel {
    private final String kind;
    private final int defaultWidth;
    private final JComponent sidebar;
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
        sidebar.setMinimumSize(new Dimension(UIScale.scale(UiMetrics.SIDEBAR_MINIMUM_WIDTH), 0));
        this.split = new ThinSplitPane(sidebar, Objects.requireNonNull(content, "content"));
        this.split.setDividerLocation(width());
        followDivider();
        // A theme or font size change replaces the split's look, and with it the divider.
        this.split.addPropertyChangeListener("UI", event -> followDivider());
        add(this.split, BorderLayout.CENTER);
    }

    /** Remembers the width a drag of the divider leaves; the layout squeezing the sidebar in a narrow window is not a drag. */
    private void followDivider() {
        if (!(this.split.getUI() instanceof BasicSplitPaneUI ui)) return;
        ui.getDivider().addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent event) {
                if (Sidebar.this.shown) {
                    GlobalConfig.getInstance().setSidebarWidth(Sidebar.this.kind, UIScale.unscale(Sidebar.this.split.getDividerLocation()));
                }
            }
        });
    }

    /**
     * Shows or hides the sidebar, such as a category list with only one category. The content stays where it is, so it
     * keeps focus and its state.
     */
    public void setSidebarShown(boolean shown) {
        if (this.shown == shown) return;
        this.shown = shown;
        this.sidebar.setVisible(shown);
        this.split.setDividerSize(shown ? 1 : 0);
        if (shown) this.split.setDividerLocation(width());
        this.split.revalidate();
    }

    public boolean sidebarShown() {
        return this.shown;
    }

    /** The width the sidebar was last dragged to in this kind of view, or its default, scaled and never below the minimum. */
    private int width() {
        Integer dragged = GlobalConfig.getInstance().sidebarWidth(this.kind);
        return UIScale.scale(Math.max(UiMetrics.SIDEBAR_MINIMUM_WIDTH, dragged != null ? dragged : this.defaultWidth));
    }
}
