package com.github.minecraft_ta.totalDebugCompanion.ui.components;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryLabel;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryText;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Rectangle;

/**
 * The first cell of a table whose rows are grouped under collapsible headings (docs/UI_GUIDE.md): the row indented by
 * its depth, the tree's chevron on a heading, its text with secondary text beside it, and a bar at the left edge for a
 * changed row. A row that is not a heading leaves the chevron's room empty, so the text of every level lines up.
 */
public final class GroupedRowCell extends JPanel {
    private static final int START = 6;
    private static final int LEVEL = 16;
    private static final int BAR_WIDTH = 3;

    private final PrimarySecondaryLabel label = new PrimarySecondaryLabel();
    private Color bar;

    public GroupedRowCell() {
        super(new BorderLayout());
        add(this.label, BorderLayout.CENTER);
    }

    /**
     * Shows a row at {@code depth}, the outermost being 0. {@code collapsed} is null for a row that is not a heading,
     * and {@code bar} colors the left edge, or is null for none.
     */
    public GroupedRowCell configure(JTable table, PrimarySecondaryText text, int depth, Boolean collapsed, boolean selected,
                                    Color bar) {
        Color background = selected ? table.getSelectionBackground() : table.getBackground();
        Color foreground = selected ? table.getSelectionForeground() : ThemeColors.text();
        this.bar = bar;
        this.label.configure(text, collapsed == null ? Icons.NONE : chevron(collapsed), table.getFont(), selected,
                foreground, background);
        this.label.setOpaque(false);
        setBackground(background);
        setBorder(BorderFactory.createEmptyBorder(0, indent(depth), 0, 6));
        return this;
    }

    /** Whether {@code x}, in table coordinates, is on the chevron of the heading at {@code depth} in {@code viewRow}. */
    public static boolean onChevron(JTable table, int viewRow, int depth, int x) {
        Rectangle cell = table.getCellRect(viewRow, 0, true);
        int left = cell.x + indent(depth);
        return x >= left && x < left + chevron(false).getIconWidth();
    }

    private static int indent(int depth) {
        return START + depth * LEVEL;
    }

    /** The tree's chevron from the look and feel, so it follows theme changes. */
    private static Icon chevron(boolean collapsed) {
        return UIManager.getIcon(collapsed ? "Tree.collapsedIcon" : "Tree.expandedIcon");
    }

    @Override
    protected void paintChildren(Graphics graphics) {
        super.paintChildren(graphics);
        if (this.bar != null) {
            graphics.setColor(this.bar);
            graphics.fillRect(0, 1, BAR_WIDTH, getHeight() - 2);
        }
    }
}
