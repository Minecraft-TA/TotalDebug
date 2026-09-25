package com.github.minecraft_ta.totalDebugCompanion.ui.theme;

import javax.swing.JComponent;
import javax.swing.UIManager;
import java.awt.Color;
import java.util.function.Supplier;

/**
 * Named roles for the chrome colours this app paints itself.
 *
 * <p>Every accessor resolves through {@link UIManager} on each call, so callers that read them at
 * paint time follow theme changes without needing to subscribe to anything. Each has a literal
 * fallback because a theme is not obliged to define every key.
 */
public final class ThemeColors {

    private static final String FOREGROUND_ROLE = "ThemeColors.foregroundRole";

    private ThemeColors() {
    }

    /**
     * Gives a component the foreground of a color role and keeps it when the look and feel restyles the component.
     * Theme colors are UI resources, which a restyle would otherwise replace with the default text color.
     */
    public static void keepForeground(JComponent component, Supplier<Color> role) {
        boolean installed = component.getClientProperty(FOREGROUND_ROLE) != null;
        component.putClientProperty(FOREGROUND_ROLE, role);
        component.setForeground(role.get());
        if (installed) return;
        component.addPropertyChangeListener("UI", event -> {
            if (component.getClientProperty(FOREGROUND_ROLE) instanceof Supplier<?> current) {
                component.setForeground((Color) current.get());
            }
        });
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

    /** Supporting information that remains readable, rather than disabled control text. */
    public static Color secondaryText() {
        return first(new Color(0x80858F), "Label.infoForeground", "Component.infoForeground");
    }

    /** Primary label text. */
    public static Color text() {
        return first(new Color(0xD1D3D9), "Label.foreground");
    }

    /** The theme's accent, used for toggled toolbar buttons and search highlights. */
    public static Color accent() {
        return first(new Color(0x3871E1), "Component.accentColor", "ProgressBar.foreground");
    }

    public static Color link() {
        return first(new Color(0x589DF6), "Link.activeForeground", "Component.linkColor", "Component.accentColor");
    }

    /** Error / invalid-input red. */
    public static Color error() {
        return first(new Color(0xDB5860), "Actions.Red");
    }

    /** Available / successful state green. */
    public static Color success() {
        return first(new Color(0x59A869), "Actions.Green");
    }

    /** Pending state amber. */
    public static Color warning() {
        return first(new Color(0xD9A343), "Actions.Yellow");
    }

    /** Opaque marker behind text matched by Speed Search. */
    public static Color searchMatch() {
        Color fallback = ThemeManager.current().dark() ? new Color(0xBA9752) : new Color(0xFEE6B1);
        return first(fallback, "SearchMatch.startBackground");
    }

    /** Background of a secondary surface such as a header strip. */
    public static Color headerBackground() {
        return first(new Color(0x2B2D30), "ToolBar.background", "Panel.background");
    }

    /** Hover background for flat toolbar buttons. */
    public static Color hoverBackground() {
        return first(new Color(0x393B40), "Button.toolbar.hoverBackground", "MenuItem.selectionBackground");
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
