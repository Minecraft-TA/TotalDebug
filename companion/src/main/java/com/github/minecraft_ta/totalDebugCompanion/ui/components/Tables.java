package com.github.minecraft_ta.totalDebugCompanion.ui.components;

import com.formdev.flatlaf.util.UIScale;
import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;

import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.TableCellRenderer;
import java.awt.Component;
import java.awt.Dimension;

/**
 * The table setup every Companion table shares (docs/UI_GUIDE.md): no grid, filling the viewport, headers aligned
 * with their column's text, fixed column order and rows as tall as tree rows.
 */
public final class Tables {
    private Tables() {
    }

    public static void configure(JTable table) {
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setFillsViewportHeight(true);
        table.setRowHeight(UIScale.scale(UiMetrics.TREE_ROW_HEIGHT));
        table.getTableHeader().setReorderingAllowed(false);
        TableCellRenderer header = table.getTableHeader().getDefaultRenderer();
        table.getTableHeader().setDefaultRenderer((owner, value, selected, focused, row, column) -> {
            Component component = header.getTableCellRendererComponent(owner, value, selected, focused, row, column);
            if (component instanceof JLabel label) label.setHorizontalAlignment(SwingConstants.LEADING);
            return component;
        });
    }
}
