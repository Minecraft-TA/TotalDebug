package com.github.minecraft_ta.totalDebugCompanion.ui.components;

import javax.swing.Icon;
import java.awt.Component;
import java.awt.Graphics;

/** Paints an icon centered in a square, so a row keeps its layout while a larger preview is loading or missing. */
public record CenteredIcon(Icon icon, int size) implements Icon {
    @Override
    public void paintIcon(Component component, Graphics graphics, int x, int y) {
        this.icon.paintIcon(component, graphics,
                x + (this.size - this.icon.getIconWidth()) / 2, y + (this.size - this.icon.getIconHeight()) / 2);
    }

    @Override
    public int getIconWidth() {
        return this.size;
    }

    @Override
    public int getIconHeight() {
        return this.size;
    }
}
