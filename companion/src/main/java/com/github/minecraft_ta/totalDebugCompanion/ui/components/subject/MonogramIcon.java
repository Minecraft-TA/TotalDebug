package com.github.minecraft_ta.totalDebugCompanion.ui.components.subject;

import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;

import javax.swing.Icon;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Locale;

/** A square tile with a name's initials, for a mod that ships no logo. Colors follow the current theme. */
public final class MonogramIcon implements Icon {
    private final String initials;
    private final int size;

    public MonogramIcon(String name, int size) {
        this.initials = initials(name);
        this.size = size;
    }

    /**
     * The first letters of up to two words; a single word adds its next capital, so {@code CBMicroblock} becomes
     * {@code CB} and {@code Sodium} becomes {@code S}.
     */
    static String initials(String name) {
        String[] words = name.strip().split("[\\s_\\-:.]+");
        StringBuilder initials = new StringBuilder();
        for (String word : words) {
            if (!word.isEmpty() && initials.length() < 2) initials.appendCodePoint(word.codePointAt(0));
        }
        if (initials.length() == 1 && words.length == 1) {
            String word = words[0];
            for (int index = 1; index < word.length(); index++) {
                if (Character.isUpperCase(word.charAt(index))) {
                    initials.append(word.charAt(index));
                    break;
                }
            }
        }
        return initials.isEmpty() ? "?" : initials.toString().toUpperCase(Locale.ROOT);
    }

    @Override
    public void paintIcon(Component component, Graphics graphics, int x, int y) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(ThemeColors.hoverBackground());
            int arc = this.size / 4;
            g.fillRoundRect(x, y, this.size, this.size, arc, arc);
            g.setColor(ThemeColors.secondaryText());
            g.setFont(component.getFont().deriveFont(Font.BOLD, this.size * 0.36f));
            FontMetrics metrics = g.getFontMetrics();
            int textX = x + (this.size - metrics.stringWidth(this.initials)) / 2;
            int textY = y + (this.size - metrics.getHeight()) / 2 + metrics.getAscent();
            g.drawString(this.initials, textX, textY);
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
