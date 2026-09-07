package com.github.minecraft_ta.totalDebugCompanion.ui.presentation;

import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import com.github.minecraft_ta.totalDebugCompanion.ui.speedsearch.SpeedSearch;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.util.Objects;

/** Renders structured row text with colors resolved from the active theme at paint time. */
public final class PrimarySecondaryLabel extends JPanel {
    private final SearchHighlightLabel primary = new SearchHighlightLabel();
    private final SearchHighlightLabel secondary = new SearchHighlightLabel();
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
        configure(text, icon, font, selected, selectionForeground, selectionBackground, null);
    }

    public void configure(
            PrimarySecondaryText text,
            Icon icon,
            Font font,
            boolean selected,
            Color selectionForeground,
            Color selectionBackground,
            JComponent speedSearchOwner
    ) {
        PrimarySecondaryText presentation = Objects.requireNonNull(text, "text");
        this.primary.setText(presentation.primary());
        this.primary.setSpeedSearchOwner(speedSearchOwner);
        this.primary.setIcon(icon);
        this.primary.setIconTextGap(5);
        this.primary.setFont(font);
        this.secondary.setText(presentation.secondary());
        this.secondary.setSpeedSearchOwner(speedSearchOwner);
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

    private static final class SearchHighlightLabel extends JLabel {
        private JComponent speedSearchOwner;

        void setSpeedSearchOwner(JComponent speedSearchOwner) {
            this.speedSearchOwner = speedSearchOwner;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            paintMatches(graphics);
            super.paintComponent(graphics);
        }

        private void paintMatches(Graphics graphics) {
            String text = getText();
            if (this.speedSearchOwner == null || text == null || text.isEmpty()) {
                return;
            }
            var ranges = SpeedSearch.matchingRanges(this.speedSearchOwner, text);
            if (ranges.isEmpty()) {
                return;
            }

            Insets insets = getInsets();
            Rectangle view = new Rectangle(
                    insets.left,
                    insets.top,
                    Math.max(0, getWidth() - insets.left - insets.right),
                    Math.max(0, getHeight() - insets.top - insets.bottom)
            );
            Rectangle icon = new Rectangle();
            Rectangle textBounds = new Rectangle();
            FontMetrics metrics = getFontMetrics(getFont());
            SwingUtilities.layoutCompoundLabel(
                    this,
                    metrics,
                    text,
                    getIcon(),
                    getVerticalAlignment(),
                    getHorizontalAlignment(),
                    getVerticalTextPosition(),
                    getHorizontalTextPosition(),
                    view,
                    icon,
                    textBounds,
                    getIconTextGap()
            );

            Graphics2D copy = (Graphics2D) graphics.create();
            copy.setColor(ThemeColors.searchMatch());
            for (SpeedSearch.MatchRange range : ranges) {
                int start = Math.min(range.start(), text.length());
                int end = Math.min(range.end(), text.length());
                int x = textBounds.x + metrics.stringWidth(text.substring(0, start));
                int width = Math.max(2, metrics.stringWidth(text.substring(start, end)));
                copy.fillRoundRect(x - 1, textBounds.y, width + 2, textBounds.height, 3, 3);
            }
            copy.dispose();
        }
    }
}
