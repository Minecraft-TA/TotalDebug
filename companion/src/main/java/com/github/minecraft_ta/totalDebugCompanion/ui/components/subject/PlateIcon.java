package com.github.minecraft_ta.totalDebugCompanion.ui.components.subject;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;

import javax.swing.Icon;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * A square tile with an icon at half its size, for a subject that has no preview of its own, as a mod without a logo
 * gets a tile with its initials. Colors follow the current theme.
 */
public final class PlateIcon implements Icon {
    private final Icon icon;
    private final int size;

    public PlateIcon(Icon icon, int size) {
        this.size = size;
        this.icon = icon instanceof FlatSVGIcon svg ? svg.derive(size / 2f / svg.getIconWidth()) : icon;
    }

    @Override
    public void paintIcon(Component component, Graphics graphics, int x, int y) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(ThemeColors.hoverBackground());
            int arc = this.size / 4;
            g.fillRoundRect(x, y, this.size, this.size, arc, arc);
            this.icon.paintIcon(component, g, x + (this.size - this.icon.getIconWidth()) / 2,
                    y + (this.size - this.icon.getIconHeight()) / 2);
        } finally {
            g.dispose();
        }
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
