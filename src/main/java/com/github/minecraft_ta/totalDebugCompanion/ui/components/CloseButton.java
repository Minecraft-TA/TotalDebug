package com.github.minecraft_ta.totalDebugCompanion.ui.components;

import com.github.minecraft_ta.totalDebugCompanion.Icons;

import javax.swing.*;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

public class CloseButton extends JButton {

    private boolean hovered;

    public CloseButton() {
        int size = 16;
        setPreferredSize(new Dimension(size, size));
        setBorder(BorderFactory.createEmptyBorder());
        setMargin(new Insets(0, 0, 0, 0));
        setContentAreaFilled(false);
        setFocusable(false);
        addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) {
                hovered = true;
            }

            public void mouseExited(MouseEvent e) {
                hovered = false;
            }
        });

        addAncestorListener(new AncestorListener() {
            @Override
            public void ancestorAdded(AncestorEvent event) {
            }

            @Override
            public void ancestorRemoved(AncestorEvent event) {
                hovered = false;
            }

            @Override
            public void ancestorMoved(AncestorEvent event) {
            }
        });
    }

    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();

        if (this.hovered) {
            Icons.CLOSE_HOVERED_ICON.paintIcon(this, g2, 0, 0);
        } else {
            Icons.CLOSE_ICON.paintIcon(this, g2, 0, 0);
        }

        g2.dispose();
    }
}
