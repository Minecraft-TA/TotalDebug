package com.github.minecraft_ta.totalDebugCompanion.ui.theme;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.util.UIScale;
import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.ApplicationStatusBar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JTabbedPane;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Rectangle;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Islands themes are shipped pre-flattened by {@code tools/flatten_intellij_theme.py}, because
 * FlatLaf does not resolve {@code parentTheme}. If that flattening ever regresses the symptom is a
 * half-styled UI rather than an exception, so these tests assert the themes both load and carry
 * enough resolved colour to be the real thing.
 */
class ThemeLoadingTest {

    static List<CompanionTheme> themes() {
        return CompanionTheme.available();
    }

    @ParameterizedTest
    @MethodSource("themes")
    void themeInstallsAndYieldsAConsistentPalette(CompanionTheme theme) {
        ThemeManager.installTheme(theme);

        assertEquals(theme.dark(), FlatLaf.isLafDark(), "theme darkness must match the installed LaF");

        Color panelBackground = UIManager.getColor("Panel.background");
        Color panelForeground = UIManager.getColor("Panel.foreground");
        assertNotNull(panelBackground, "Panel.background");
        assertNotNull(panelForeground, "Panel.foreground");

        // A flattening regression shows up as chrome that disagrees with the declared darkness.
        assertEquals(theme.dark(), luminance(panelBackground) < 0.5,
                "Panel.background " + hex(panelBackground) + " disagrees with dark=" + theme.dark());
        assertTrue(Math.abs(luminance(panelBackground) - luminance(panelForeground)) > 0.3,
                "foreground and background are not readable against each other");
    }

    @ParameterizedTest
    @MethodSource("themes")
    void themeResolvesKeysThatOnlyExistInTheParentChain(CompanionTheme theme) {
        ThemeManager.installTheme(theme);

        // These come from the expUI parents, not from the Islands files themselves, so they are the
        // canary for the parentTheme flattening.
        for (String key : List.of("TabbedPane.background", "MenuBar.background", "ToolTip.background",
                                  "ScrollBar.thumb", "Table.background", "TextField.background")) {
            assertNotNull(UIManager.getColor(key), key + " unresolved for " + theme.id());
        }
    }

    @ParameterizedTest
    @MethodSource("themes")
    void themeAppliesTheApplicationDensityContract(CompanionTheme theme) throws Exception {
        ThemeManager.installTheme(theme);

        AtomicReference<RuntimeMetrics> result = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            JTree tree = new JTree();
            JTabbedPane tabs = new JTabbedPane();
            tabs.addTab("One", new JPanel());
            tabs.setSize(400, 200);
            tabs.doLayout();
            Rectangle tabBounds = tabs.getBoundsAt(0);

            ApplicationStatusBar statusBar = new ApplicationStatusBar();
            JScrollBar scrollBar = new JScrollBar();
            result.set(new RuntimeMetrics(
                    tree.getRowHeight(),
                    tabBounds.height,
                    statusBar.getPreferredSize().height,
                    scrollBar.getBackground()
            ));
        });

        RuntimeMetrics metrics = result.get();
        assertEquals(UiMetrics.TREE_ROW_HEIGHT, UIManager.get("Tree.rowHeight"), theme.id());
        assertEquals(UiMetrics.TAB_HEIGHT, UIManager.get("TabbedPane.tabHeight"), theme.id());
        assertEquals(UIScale.scale(UiMetrics.TREE_ROW_HEIGHT), metrics.treeRowHeight(), theme.id());
        assertEquals(UIScale.scale(UiMetrics.TAB_HEIGHT), metrics.tabHeight(), theme.id());
        assertEquals(UiMetrics.STATUS_BAR_HEIGHT, metrics.statusBarHeight(), theme.id());
        assertEquals(UIManager.getColor("Panel.background"), metrics.scrollBarBackground(), theme.id());
        assertEquals(UIManager.getColor("Panel.background"), UIManager.getColor("ScrollBar.track"), theme.id());
    }

    @Test
    void switchingBackAndForthKeepsCompanionDefaults() {
        ThemeManager.installTheme(CompanionTheme.ISLANDS_DARK);
        ThemeManager.installTheme(CompanionTheme.ISLANDS_LIGHT);
        ThemeManager.installTheme(CompanionTheme.ISLANDS_DARK);

        assertEquals("plain", UIManager.get("SplitPaneDivider.style"));
        assertEquals(UiMetrics.TREE_ROW_HEIGHT, UIManager.get("Tree.rowHeight"));
        assertEquals(UiMetrics.TAB_HEIGHT, UIManager.get("TabbedPane.tabHeight"));
    }

    @Test
    void editorPaletteMatchesThemeDarkness() {
        for (CompanionTheme theme : CompanionTheme.available()) {
            EditorPalette palette = theme.editor();
            assertEquals(theme.dark(), luminance(palette.background()) < 0.5, theme.id() + " editor background");
            assertTrue(Math.abs(luminance(palette.background()) - luminance(palette.foreground())) > 0.4,
                    theme.id() + " editor text is not readable on its background");
        }
    }

    @Test
    void unknownThemeIdFallsBackToDefault() {
        assertEquals(CompanionTheme.DEFAULT, CompanionTheme.byId("no-such-theme"));
        assertEquals(CompanionTheme.DEFAULT, CompanionTheme.byId(null));
        assertEquals(CompanionTheme.ISLANDS_LIGHT, CompanionTheme.byId("islands-light"));
    }

    private static double luminance(Color color) {
        return (0.2126 * color.getRed() + 0.7152 * color.getGreen() + 0.0722 * color.getBlue()) / 255d;
    }

    private static String hex(Color color) {
        return String.format("#%06X", color.getRGB() & 0xFFFFFF);
    }

    private record RuntimeMetrics(
            int treeRowHeight,
            int tabHeight,
            int statusBarHeight,
            Color scrollBarBackground
    ) {
    }
}
