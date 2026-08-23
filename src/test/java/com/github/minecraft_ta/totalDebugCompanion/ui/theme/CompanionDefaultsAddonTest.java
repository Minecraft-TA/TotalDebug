package com.github.minecraft_ta.totalDebugCompanion.ui.theme;

import com.formdev.flatlaf.FlatDarculaLaf;
import com.formdev.flatlaf.FlatLightLaf;
import org.junit.jupiter.api.Test;

import javax.swing.UIManager;
import java.awt.Color;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the ServiceLoader wiring. If {@code META-INF/services/com.formdev.flatlaf.FlatDefaultsAddon}
 * is missing or misspelled the addon silently never runs and the app quietly loses its styling, so
 * this asserts the defaults actually land - and keep landing across a look and feel switch.
 */
class CompanionDefaultsAddonTest {

    @Test
    void appliesCompanionDefaultsOnInstall() {
        FlatDarculaLaf.setup();

        assertEquals("plain", UIManager.get("SplitPaneDivider.style"));
        assertEquals(25, UIManager.get("TabbedPane.tabHeight"));
        assertEquals(2, UIManager.get("TabbedPane.tabSelectionHeight"));
        assertEquals(1, UIManager.get("TabbedPane.contentSeparatorHeight"));
        assertEquals(Boolean.FALSE, UIManager.get("TitlePane.unifiedBackground"));
        assertNotNull(UIManager.getBorder("MenuBar.border"));

        Color focus = UIManager.getColor("Component.focusColor");
        assertNotNull(focus);
        assertEquals(0, focus.getAlpha(), "focus ring is suppressed app wide");
    }

    @Test
    void reappliesDefaultsAfterASecondLookAndFeelInstall() {
        FlatDarculaLaf.setup();
        assertEquals("plain", UIManager.get("SplitPaneDivider.style"));

        // A theme switch rebuilds UIDefaults from scratch; the addon has to run again.
        FlatLightLaf.setup();

        assertEquals("plain", UIManager.get("SplitPaneDivider.style"));
        assertEquals(25, UIManager.get("TabbedPane.tabHeight"));
        assertNotNull(UIManager.getBorder("MenuBar.border"));
        assertTrue(UIManager.getInsets("TabbedPane.tabInsets").left == 10);
    }
}
