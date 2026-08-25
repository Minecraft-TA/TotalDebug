package com.github.minecraft_ta.totalDebugCompanion.ui.theme;

import com.formdev.flatlaf.FlatDefaultsAddon;
import com.github.minecraft_ta.totalDebugCompanion.GlobalConfig;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.SimpleMenuBarBorder;

import javax.swing.BorderFactory;
import javax.swing.LookAndFeel;
import javax.swing.UIDefaults;
import javax.swing.plaf.BorderUIResource;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;
import java.awt.Color;
import java.awt.Font;
import java.awt.Insets;

/**
 * Companion specific tweaks on top of whatever FlatLaf theme is active.
 *
 * <p>These used to be a block of {@code UIManager.put(...)} calls in {@code CompanionApp.startUi()}.
 * That only worked because the look and feel was installed exactly once: every LaF install rebuilds
 * {@link UIDefaults} from scratch and would silently drop them. FlatLaf invokes this addon on every
 * install, so the tweaks survive theme switches without anyone having to remember to reapply them.
 *
 * <p>Registered through {@code META-INF/services/com.formdev.flatlaf.FlatDefaultsAddon}.
 */
public class CompanionDefaultsAddon extends FlatDefaultsAddon {

    @Override
    public void afterDefaultsLoading(LookAndFeel lookAndFeel, UIDefaults defaults) {
        ColorUIResource transparent = new ColorUIResource(new Color(0, 0, 0, 0));

        defaults.put("SplitPaneDivider.style", "plain");
        // The focus ring is suppressed app wide; focus is conveyed by the selection colour instead.
        defaults.put("Component.focusColor", transparent);
        defaults.put("Slider.focusedColor", transparent);

        defaults.put("TabbedPane.tabInsets", new Insets(0, 10, 0, 10));
        defaults.put("TabbedPane.tabHeight", 25);
        defaults.put("TabbedPane.tabSelectionHeight", 2);
        defaults.put("TabbedPane.contentSeparatorHeight", 1);

        applySelectionControlColors(defaults);

        defaults.put(
                "Table.focusSelectedCellHighlightBorder",
                new BorderUIResource(BorderFactory.createEmptyBorder(0, 5, 0, 0))
        );
        defaults.put(
                "Table.focusCellHighlightBorder",
                new BorderUIResource(BorderFactory.createEmptyBorder(0, 3, 0, 0))
        );

        defaults.put("TitlePane.unifiedBackground", false);
        defaults.put("MenuBar.border", new SimpleMenuBarBorder());

        applyUiFontSize(defaults);
    }

    private static void applySelectionControlColors(UIDefaults defaults) {
        Color accent = defaults.getColor("Component.accentColor");
        if (accent == null) {
            accent = new Color(0x3871E1);
        }
        Color selectedForeground = defaults.getColor("List.selectionForeground");
        if (selectedForeground == null) {
            selectedForeground = Color.WHITE;
        }

        defaults.put("CheckBox.icon.style", "filled");
        defaults.put("RadioButton.icon.style", "filled");
        defaults.put("CheckBox.icon[filled].selectedBorderColor", new ColorUIResource(accent));
        defaults.put("CheckBox.icon[filled].selectedBackground", new ColorUIResource(accent));
        defaults.put("CheckBox.icon[filled].checkmarkColor", new ColorUIResource(selectedForeground));
    }

    /**
     * Scales the LaF's base font to the configured UI font size. Applied here rather than through a
     * one-off {@code UIManager.put} so it survives theme switches, which rebuild UIDefaults.
     */
    private static void applyUiFontSize(UIDefaults defaults) {
        float size = GlobalConfig.getInstance().uiFontSize();
        Font base = defaults.getFont("defaultFont");
        if (base == null) {
            return;
        }
        if (Math.abs(base.getSize2D() - size) < 0.01f) {
            return;
        }
        defaults.put("defaultFont", new FontUIResource(base.deriveFont(size)));
    }

    @Override
    public int getPriority() {
        // Runs after FlatLaf's own addons so these overrides win.
        return 20000;
    }
}
