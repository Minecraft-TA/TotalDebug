package com.github.minecraft_ta.totalDebugCompanion.ui.components;

import com.formdev.flatlaf.util.UIScale;
import com.github.minecraft_ta.totalDebugCompanion.GlobalConfig;
import com.github.minecraft_ta.totalDebugCompanion.testui.UiTest;
import com.github.minecraft_ta.totalDebugCompanion.testui.UiTestScope;
import org.junit.jupiter.api.Test;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicSplitPaneDivider;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@UiTest
class SidebarTest {
    @Test
    void onlyADragThatMovesTheDividerIsRemembered() throws Exception {
        String kind = "sidebar-test-" + UUID.randomUUID();
        SwingUtilities.invokeAndWait(() -> {
            Sidebar sidebar = new Sidebar(kind, 200, new JPanel(), new JPanel());
            JFrame window = new JFrame();
            window.setContentPane(sidebar);
            window.setSize(800, 400);
            try {
                UiTestScope.show(window);
                window.validate();
                JSplitPane split = (JSplitPane) sidebar.getComponent(0);
                assertEquals(UIScale.scale(200), split.getDividerLocation());

                drag(divider(split), 0);
                assertNull(GlobalConfig.getInstance().sidebarWidth(kind), "a click that leaves the divider in place is not a drag");

                drag(divider(split), 40);
                assertEquals(UIScale.unscale(split.getDividerLocation()), GlobalConfig.getInstance().sidebarWidth(kind));

                split.setDividerLocation(0);
                split.updateUI();
                assertEquals(UIScale.scale(GlobalConfig.getInstance().sidebarWidth(kind)), split.getDividerLocation(),
                        "a new look lays the sidebar out again at its width");
                drag(divider(split), 30);
                assertEquals(UIScale.unscale(split.getDividerLocation()), GlobalConfig.getInstance().sidebarWidth(kind),
                        "the new look's divider is followed too");
            } finally {
                window.dispose();
            }
        });
    }

    private static BasicSplitPaneDivider divider(JSplitPane split) {
        return ((BasicSplitPaneUI) split.getUI()).getDivider();
    }

    private static void drag(BasicSplitPaneDivider divider, int distance) {
        divider.dispatchEvent(new MouseEvent(divider, MouseEvent.MOUSE_PRESSED, 0,
                InputEvent.BUTTON1_DOWN_MASK, 0, 50, 1, false, MouseEvent.BUTTON1));
        if (distance != 0) {
            divider.dispatchEvent(new MouseEvent(divider, MouseEvent.MOUSE_DRAGGED, 1,
                    InputEvent.BUTTON1_DOWN_MASK, distance, 50, 0, false, MouseEvent.NOBUTTON));
        }
        divider.getParent().doLayout();
        divider.dispatchEvent(new MouseEvent(divider, MouseEvent.MOUSE_RELEASED, 2,
                0, distance, 50, 1, false, MouseEvent.BUTTON1));
    }
}
