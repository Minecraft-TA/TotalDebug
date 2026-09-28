package com.github.minecraft_ta.totalDebugCompanion.ui.components;

import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;

/**
 * A flow layout that asks for the height of the rows it wraps into, so a toolbar narrower than its controls grows a row
 * instead of cutting the last controls off, as a plain {@link FlowLayout} does in a {@code BorderLayout.NORTH}.
 */
public final class WrapLayout extends FlowLayout {
    public WrapLayout(int align, int horizontalGap, int verticalGap) {
        super(align, horizontalGap, verticalGap);
    }

    @Override
    public Dimension preferredLayoutSize(Container target) {
        return size(target, true);
    }

    @Override
    public Dimension minimumLayoutSize(Container target) {
        Dimension minimum = size(target, false);
        minimum.width -= getHgap() + 1;
        return minimum;
    }

    /** The size of the rows the components wrap into at the width the container is given. */
    private Dimension size(Container target, boolean preferred) {
        synchronized (target.getTreeLock()) {
            // The width the container can have: its own once laid out, otherwise its parent's.
            Container sized = target;
            while (sized.getSize().width == 0 && sized.getParent() != null) sized = sized.getParent();
            int available = sized.getSize().width == 0 ? Integer.MAX_VALUE : sized.getSize().width;
            Insets insets = target.getInsets();
            int horizontalSpace = insets.left + insets.right + getHgap() * 2;
            int maximum = available - horizontalSpace;
            Dimension size = new Dimension(0, 0);
            int rowWidth = 0;
            int rowHeight = 0;
            for (Component component : target.getComponents()) {
                if (!component.isVisible()) continue;
                Dimension each = preferred ? component.getPreferredSize() : component.getMinimumSize();
                if (rowWidth + each.width > maximum && rowWidth > 0) {
                    addRow(size, rowWidth, rowHeight);
                    rowWidth = 0;
                    rowHeight = 0;
                }
                if (rowWidth != 0) rowWidth += getHgap();
                rowWidth += each.width;
                rowHeight = Math.max(rowHeight, each.height);
            }
            addRow(size, rowWidth, rowHeight);
            size.width += horizontalSpace;
            size.height += insets.top + insets.bottom + getVgap() * 2;
            // In a scroll pane the rows would never wrap; leave room so the pane can narrow the container.
            if (SwingUtilities.getAncestorOfClass(JScrollPane.class, target) != null && target.isValid()) {
                size.width -= getHgap() + 1;
            }
            return size;
        }
    }

    private void addRow(Dimension size, int rowWidth, int rowHeight) {
        size.width = Math.max(size.width, rowWidth);
        if (size.height > 0) size.height += getVgap();
        size.height += rowHeight;
    }
}
