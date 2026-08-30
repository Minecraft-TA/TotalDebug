package com.github.minecraft_ta.totalDebugCompanion.ui.speedsearch;

import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconTextField;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.CompanionTheme;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager;
import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Rectangle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeedSearchPopupTest {
    @Test
    void usesTheOwningRootLayerWithoutTakingFocus() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JRootPane root = new JRootPane();
            root.setSize(500, 300);
            root.getLayeredPane().setSize(root.getSize());
            ShowingList target = new ShowingList();
            target.setBounds(20, 60, 460, 220);
            root.getContentPane().setLayout(null);
            root.getContentPane().add(target);

            SpeedSearchPopup popup = new SpeedSearchPopup();
            popup.showFor(target, "needle", false);

            assertSame(root.getLayeredPane(), popup.getParent());
            assertTrue(root.getLayeredPane().getBounds().contains(popup.getBounds()));
            assertEquals(26, popup.getX(), "Speed search belongs at the target's top-left edge");
            assertTrue(
                    popup.getY() + popup.getHeight() <= target.getY(),
                    "Speed search must not cover the target's first visible row"
            );
            FlatIconTextField field = (FlatIconTextField) popup.getComponent(0);
            assertEquals("needle", field.getText());
            assertEquals(ThemeColors.error(), field.getForeground());
            assertTrue(!field.isFocusable());

            popup.hidePopup();
            assertNull(popup.getParent());
        });
    }

    @Test
    void refreshesAReusedPopupWhenTheThemeChanges() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                ThemeManager.installTheme(CompanionTheme.ISLANDS_DARK);
                ShowingList target = targetInRoot();
                SpeedSearchPopup popup = new SpeedSearchPopup();
                popup.showFor(target, "needle", true);
                FlatIconTextField field = (FlatIconTextField) popup.getComponent(0);
                Color darkBackground = field.getBackground();
                assertEquals(UIManager.getColor("TextField.background"), darkBackground);

                ThemeManager.installTheme(CompanionTheme.ISLANDS_LIGHT);
                popup.showFor(target, "needle", true);
                assertEquals(UIManager.getColor("TextField.background"), field.getBackground());
                assertNotEquals(darkBackground, field.getBackground());
            } finally {
                ThemeManager.installTheme(CompanionTheme.DEFAULT);
            }
        });
    }

    private static ShowingList targetInRoot() {
        JRootPane root = new JRootPane();
        root.setSize(500, 300);
        root.getLayeredPane().setSize(root.getSize());
        ShowingList target = new ShowingList();
        target.setBounds(20, 60, 460, 220);
        root.getContentPane().setLayout(null);
        root.getContentPane().add(target);
        return target;
    }

    private static final class ShowingList extends JList<String> {
        private ShowingList() {
            super(new String[]{"Alpha"});
        }

        @Override
        public boolean isShowing() {
            return true;
        }

        @Override
        public Rectangle getVisibleRect() {
            return new Rectangle(0, 0, getWidth(), getHeight());
        }
    }
}
