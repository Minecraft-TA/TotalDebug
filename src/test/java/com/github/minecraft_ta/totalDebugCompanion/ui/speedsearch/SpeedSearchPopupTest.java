package com.github.minecraft_ta.totalDebugCompanion.ui.speedsearch;

import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconTextField;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import java.awt.Rectangle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
            target.setBounds(20, 30, 460, 250);
            root.getContentPane().setLayout(null);
            root.getContentPane().add(target);

            SpeedSearchPopup popup = new SpeedSearchPopup();
            popup.showFor(target, "needle", false);

            assertSame(root.getLayeredPane(), popup.getParent());
            assertTrue(root.getLayeredPane().getBounds().contains(popup.getBounds()));
            FlatIconTextField field = (FlatIconTextField) popup.getComponent(0);
            assertEquals("needle", field.getText());
            assertEquals(ThemeColors.error(), field.getForeground());
            assertTrue(!field.isFocusable());

            popup.hidePopup();
            assertNull(popup.getParent());
        });
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
