package com.github.minecraft_ta.totalDebugCompanion.ui.components;

import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import com.formdev.flatlaf.ui.FlatSplitPaneUI;
import com.formdev.flatlaf.util.UIScale;

import javax.swing.BorderFactory;
import javax.swing.JSplitPane;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicSplitPaneDivider;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;

/** A one-pixel separator whose mouse target extends over the adjacent pane edges. */
public class ThinSplitPane extends JSplitPane {
    public ThinSplitPane(Component left, Component right) {
        super(HORIZONTAL_SPLIT, left, right);
        setBorder(BorderFactory.createEmptyBorder());
        setDividerSize(1);
        setContinuousLayout(true);
        setOneTouchExpandable(false);
    }

    @Override public void updateUI() { setUI(new ResizeAreaUI()); }

    private static final class ResizeAreaUI extends FlatSplitPaneUI {
        @Override public void installUI(JComponent component) {
            super.installUI(component);
            // Hit-test the divider before the pane children it overlaps, without moving their bounds.
            splitPane.setComponentZOrder(divider, 0);
        }

        @Override public BasicSplitPaneDivider createDefaultDivider() {
            return new FlatSplitPaneDivider(this) {
                private int dragOffset;

                @Override public boolean contains(int x, int y) {
                    if (splitPane == null || !splitPane.isEnabled()) return super.contains(x, y);
                    int margin = UIScale.scale(3);
                    return x >= -margin && x < getWidth() + margin && y >= 0 && y < getHeight();
                }

                @Override protected void processMouseEvent(MouseEvent event) {
                    if (event.getID() == MouseEvent.MOUSE_PRESSED) {
                        if (!SwingUtilities.isLeftMouseButton(event)) return;
                        dragOffset = event.getX() - Math.clamp(event.getX(), 0, Math.max(0, getWidth() - 1));
                    }
                    if (event.getID() == MouseEvent.MOUSE_PRESSED || event.getID() == MouseEvent.MOUSE_RELEASED) {
                        // Swing validates the press against the layout width. Translate the whole
                        // gesture consistently so starting beside the line does not make it jump.
                        event.translatePoint(-dragOffset, 0);
                        try { super.processMouseEvent(event); }
                        finally { event.translatePoint(dragOffset, 0); }
                        if (event.getID() == MouseEvent.MOUSE_RELEASED) dragOffset = 0;
                    } else super.processMouseEvent(event);
                }

                @Override protected void processMouseMotionEvent(MouseEvent event) {
                    if (event.getID() == MouseEvent.MOUSE_DRAGGED) {
                        event.translatePoint(-dragOffset, 0);
                        try { super.processMouseMotionEvent(event); }
                        finally { event.translatePoint(dragOffset, 0); }
                    } else super.processMouseMotionEvent(event);
                }
            };
        }
    }

    @Override protected void paintChildren(Graphics graphics) {
        super.paintChildren(graphics);
        if (getDividerSize() > 0 && getUI() instanceof BasicSplitPaneUI ui) {
            Rectangle divider = ui.getDivider().getBounds();
            var previous = graphics.getColor();
            graphics.setColor(ThemeColors.separator());
            graphics.fillRect(divider.x + divider.width / 2, divider.y, 1, divider.height);
            graphics.setColor(previous);
        }
    }
}
