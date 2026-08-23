package com.github.minecraft_ta.totalDebugCompanion.ui.theme;

import javax.swing.UIManager;
import java.awt.Color;

/**
 * Named roles for the chrome colours this app paints itself.
 *
 * <p>Every accessor resolves through {@link UIManager} on each call, so callers that read them at
 * paint time follow theme changes without needing to subscribe to anything. Each has a literal
 * fallback because a theme is not obliged to define every key.
 */
public final class ThemeColors {

    private ThemeColors() {
    }

    /** Borders and rules between regions. */
    public static Color border() {
        return first(new Color(0x40434A), "Component.borderColor", "Separator.foreground");
    }

    /** Low-contrast separators inside the main workspace. */
    public static Color separator() {
        return first(
                new Color(0x34363A),
                "OnePixelDivider.background",
                "TabbedPane.contentAreaColor",
                "Separator.foreground"
        );
    }

    /** De-emphasised text: secondary labels, hints, counters. */
    public static Color mutedText() {
        return first(new Color(0x9FA2A8), "Label.disabledForeground", "TextField.placeholderForeground");
    }

    /** Primary label text. */
    public static Color text() {
        return first(new Color(0xD1D3D9), "Label.foreground");
    }

    /** The theme's accent, used for toggled toolbar buttons and search highlights. */
    public static Color accent() {
        return first(new Color(0x3871E1), "Component.accentColor", "ProgressBar.foreground");
    }

    /** Error / invalid-input red. */
    public static Color error() {
        return first(new Color(0xDB5860), "Actions.Red");
    }

    /** Background of a secondary surface such as a header strip. */
    public static Color headerBackground() {
        return first(new Color(0x2B2D30), "ToolBar.background", "Panel.background");
    }

    /** Hover background for flat toolbar buttons. */
    public static Color hoverBackground() {
        return first(new Color(0x393B40), "Button.toolbar.hoverBackground", "MenuItem.selectionBackground");
    }

    /** Background for a flat toolbar button in the toggled-on state. */
    public static Color toggledBackground() {
        return first(new Color(0x43454A), "Button.toolbar.selectedBackground", "Button.toolbar.hoverBackground");
    }

    private static Color first(Color fallback, String... keys) {
        for (String key : keys) {
            Color color = UIManager.getColor(key);
            if (color != null) {
                return color;
            }
        }
        return fallback;
    }
}
