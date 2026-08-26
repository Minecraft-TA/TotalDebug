package com.github.minecraft_ta.totalDebugCompanion.ui.components.global;

import com.github.minecraft_ta.totalDebugCompanion.ui.components.CloseButton;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.GridBagLayout;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/** Editor tab label with a fixed close slot and theme-aware hover state. */
final class EditorTabHeader extends JPanel {
    private static final int CLOSE_SLOT_SIZE = 16;

    private final EditorTabs tabs;
    private final CloseButton closeButton = new CloseButton();
    private boolean hovered;

    EditorTabHeader(EditorTabs tabs, Icon icon) {
        this.tabs = tabs;
        setLayout(new BoxLayout(this, BoxLayout.LINE_AXIS));
        setBorder(BorderFactory.createEmptyBorder());
        setOpaque(false);

        JLabel label = new JLabel() {
            @Override
            public String getText() {
                int index = tabs.indexOfTabComponent(EditorTabHeader.this);
                return index < 0 ? null : tabs.getTitleAt(index);
            }
        };
        label.setIcon(icon);
        label.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 6));
        add(label);

        JPanel closeSlot = new JPanel(new GridBagLayout());
        closeSlot.setOpaque(false);
        Dimension closeSlotSize = new Dimension(CLOSE_SLOT_SIZE, CLOSE_SLOT_SIZE);
        closeSlot.setMinimumSize(closeSlotSize);
        closeSlot.setPreferredSize(closeSlotSize);
        closeSlot.setMaximumSize(closeSlotSize);
        closeSlot.add(this.closeButton);
        add(closeSlot);

        this.closeButton.addActionListener(event -> {
            int index = this.tabs.indexOfTabComponent(this);
            if (index >= 0) {
                this.tabs.removeTabAt(index);
            }
        });

        MouseAdapter hover = new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent event) {
                setHovered(true);
            }

            @Override
            public void mouseExited(MouseEvent event) {
                Point point = SwingUtilities.convertPoint(
                        event.getComponent(),
                        event.getPoint(),
                        EditorTabHeader.this
                );
                if (!contains(point)) {
                    setHovered(false);
                }
            }

            @Override
            public void mousePressed(MouseEvent event) {
                if (!SwingUtilities.isMiddleMouseButton(event)) {
                    return;
                }
                int index = EditorTabHeader.this.tabs.indexOfTabComponent(EditorTabHeader.this);
                if (index >= 0) {
                    EditorTabHeader.this.tabs.removeTabAt(index);
                    event.consume();
                }
            }
        };
        installHoverTracking(this, hover);
        refreshState();
    }

    void refreshState() {
        int index = this.tabs.indexOfTabComponent(this);
        this.closeButton.setVisible(index >= 0 && (index == this.tabs.getSelectedIndex() || this.hovered));
        repaint();
    }

    boolean isHovered() {
        return this.hovered;
    }

    private void setHovered(boolean hovered) {
        if (this.hovered == hovered) {
            return;
        }
        this.hovered = hovered;
        setOpaque(hovered);
        refreshState();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        if (this.hovered) {
            setBackground(ThemeColors.hoverBackground());
        }
        super.paintComponent(graphics);
    }

    private static void installHoverTracking(Component component, MouseAdapter hover) {
        component.addMouseListener(hover);
        if (component instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) {
                installHoverTracking(child, hover);
            }
        }
    }
}
