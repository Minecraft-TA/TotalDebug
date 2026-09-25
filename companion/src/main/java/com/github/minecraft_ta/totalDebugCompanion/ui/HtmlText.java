package com.github.minecraft_ta.totalDebugCompanion.ui;

import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;

import java.awt.Color;
import java.util.Locale;

/** Small pieces of Swing HTML: escaped text, theme colors, and a name followed by its value. */
public final class HtmlText {
    private HtmlText() {
    }

    public static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public static String hex(Color color) {
        return String.format(Locale.ROOT, "#%06X", color.getRGB() & 0xFFFFFF);
    }

    /**
     * A name in secondary text followed by its value, such as a status bar widget showing Game and its state. Call
     * again after a theme change; the color is resolved now.
     */
    public static String nameAndValue(String name, String value) {
        return "<html><font color='" + hex(ThemeColors.secondaryText()) + "'>" + escape(name) + "</font>&nbsp;&nbsp;"
                + escape(value) + "</html>";
    }
}
