package com.github.minecraft_ta.totalDebugCompanion.ui.theme;

import javax.swing.border.AbstractBorder;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Insets;
import java.util.function.Supplier;

/**
 * A matte border that resolves its colour when it paints rather than when it is constructed.
 *
 * <p>{@link javax.swing.BorderFactory#createMatteBorder} captures the colour up front, which means
 * a border installed once during construction keeps the old theme's colour forever - and these are
 * explicit {@code setBorder} calls, so {@code FlatLaf.updateUI()} does not replace them either.
 */
public class DynamicMatteBorder extends AbstractBorder {

    private final Insets insets;
    private final Supplier<Color> color;

    public DynamicMatteBorder(int top, int left, int bottom, int right, Supplier<Color> color) {
        this.insets = new Insets(top, left, bottom, right);
        this.color = color;
    }

    /** Convenience for the common case of a one pixel rule in {@link ThemeColors#border()}. */
    public static DynamicMatteBorder rule(int top, int left, int bottom, int right) {
        return new DynamicMatteBorder(top, left, bottom, right, ThemeColors::border);
    }

    @Override
    public void paintBorder(Component component, Graphics g, int x, int y, int width, int height) {
        Color previous = g.getColor();
        g.setColor(this.color.get());
        if (this.insets.top > 0) {
            g.fillRect(x, y, width, this.insets.top);
        }
        if (this.insets.bottom > 0) {
            g.fillRect(x, y + height - this.insets.bottom, width, this.insets.bottom);
        }
        if (this.insets.left > 0) {
            g.fillRect(x, y, this.insets.left, height);
        }
        if (this.insets.right > 0) {
            g.fillRect(x + width - this.insets.right, y, this.insets.right, height);
        }
        g.setColor(previous);
    }

    @Override
    public Insets getBorderInsets(Component component) {
        return (Insets) this.insets.clone();
    }

    @Override
    public Insets getBorderInsets(Component component, Insets target) {
        target.set(this.insets.top, this.insets.left, this.insets.bottom, this.insets.right);
        return target;
    }

    @Override
    public boolean isBorderOpaque() {
        return true;
    }
}
