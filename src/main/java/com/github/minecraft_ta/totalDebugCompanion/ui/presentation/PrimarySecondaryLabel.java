package com.github.minecraft_ta.totalDebugCompanion.ui.presentation;

import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.util.Objects;

/** Renders structured row text with colors resolved from the active theme at paint time. */
public final class PrimarySecondaryLabel extends JPanel {
    private final JLabel primary = new JLabel();
    private final JLabel secondary = new JLabel();
    private final Component gap = Box.createHorizontalStrut(8);
    private boolean selected;
    private Color selectionForeground;

    public PrimarySecondaryLabel() {
        setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
        setOpaque(false);
        this.primary.setOpaque(false);
        this.secondary.setOpaque(false);
        add(this.primary);
        add(this.gap);
        add(this.secondary);
    }

    public void configure(
            PrimarySecondaryText text,
            Icon icon,
            Font font,
            boolean selected,
            Color selectionForeground
    ) {
        configure(text, icon, font, selected, selectionForeground, null);
    }

    public void configure(
            PrimarySecondaryText text,
            Icon icon,
            Font font,
            boolean selected,
            Color selectionForeground,
            Color selectionBackground
    ) {
        PrimarySecondaryText presentation = Objects.requireNonNull(text, "text");
        this.primary.setText(presentation.primary());
        this.primary.setIcon(icon);
        this.primary.setIconTextGap(5);
        this.primary.setFont(font);
        this.secondary.setText(presentation.secondary());
        this.secondary.setFont(font);
        boolean hasSecondaryText = !presentation.secondary().isBlank();
        this.gap.setVisible(hasSecondaryText);
        this.secondary.setVisible(hasSecondaryText);
        this.selected = selected;
        this.selectionForeground = selectionForeground;
        setOpaque(selected && selectionBackground != null);
        if (isOpaque()) {
            setBackground(selectionBackground);
        }
    }

    @Override
    public void setToolTipText(String text) {
        super.setToolTipText(text);
        if (this.primary != null) {
            this.primary.setToolTipText(text);
            this.secondary.setToolTipText(text);
        }
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Color primaryColor = this.selected && this.selectionForeground != null
                ? this.selectionForeground
                : ThemeColors.text();
        this.primary.setForeground(primaryColor);
        this.secondary.setForeground(this.selected ? primaryColor : ThemeColors.mutedText());
        super.paintComponent(graphics);
    }
}
