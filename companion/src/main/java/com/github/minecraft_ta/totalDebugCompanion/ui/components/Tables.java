package com.github.minecraft_ta.totalDebugCompanion.ui.components;

import com.formdev.flatlaf.util.UIScale;
import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;

import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.JViewport;
import javax.swing.SwingConstants;
import javax.swing.table.TableCellRenderer;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Point;
import java.util.HashSet;
import java.util.Set;
import java.util.function.IntFunction;

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

    /**
     * Runs {@code update}, which puts other rows in {@code table}, keeping what was selected and where the table was
     * scrolled: the rows selected before are selected again where they still are, known by {@code identity} of a row as
     * the table shows it, such as a binding's name. A table read again, as after a change elsewhere, stays as the user left
     * it (docs/SYSTEMS.md, section 3).
     */
    public static void keepingSelection(JTable table, IntFunction<Object> identity, Runnable update) {
        Set<Object> selected = new HashSet<>();
        for (int row : table.getSelectedRows()) {
            if (row < table.getRowCount()) selected.add(identity.apply(row));
        }
        Point scrolled = table.getParent() instanceof JViewport viewport ? viewport.getViewPosition() : null;
        update.run();
        if (!selected.isEmpty()) {
            for (int row = 0; row < table.getRowCount(); row++) {
                if (selected.contains(identity.apply(row))) table.addRowSelectionInterval(row, row);
            }
        }
        if (scrolled != null && table.getParent() instanceof JViewport viewport) viewport.setViewPosition(scrolled);
    }
}
