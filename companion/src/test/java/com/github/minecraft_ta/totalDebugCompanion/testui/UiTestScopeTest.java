package com.github.minecraft_ta.totalDebugCompanion.testui;

import com.github.minecraft_ta.totalDebugCompanion.ui.theme.CompanionTheme;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager;
import com.github.minecraft_ta.totalDebugCompanion.util.UIUtils;

import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.JWindow;
import javax.swing.PopupFactory;

import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.ArrayList;
import java.util.List;

import static com.github.minecraft_ta.totalDebugCompanion.testui.UiTestScope.onEdt;
import static org.junit.jupiter.api.Assertions.*;

@UiTest
class UiTestScopeTest {
    @Test void centersAnIndependentWindowWithinItsOffscreenOwnerBeforeShowing() throws Exception {
        onEdt(() -> {
            var owner = new JFrame();
            owner.setSize(600, 400);
            UiTestScope.show(owner);
            var child = new JFrame();
            child.setSize(200, 100);
            UIUtils.centerJFrame(child, owner);
            Point expected = new Point(owner.getX() + 200, owner.getY() + 150);
            assertEquals(expected, child.getLocation());
            child.setVisible(true);
            assertEquals(expected, child.getLocationOnScreen());
            UiTestScope.assertOffscreen(child);
            child.dispose();
            owner.dispose();
        });
    }

    @Test void keepsFramesAndNativeChildWindowsOutsideEveryMonitor() throws Exception {
        onEdt(() -> {
            var frame = new JFrame();
            frame.setSize(600, 400);
            UiTestScope.show(frame);
            var child = new JWindow(frame);
            child.setSize(160, 80);
            UiTestScope.place(child, frame, 40, 60);
            child.setVisible(true);
            assertEquals(new Point(frame.getX() + 40, frame.getY() + 60), child.getLocationOnScreen());
            for (var window : List.<Window>of(frame, child)) {
                assertTrue(window.isShowing());
                assertFalse(window.isAutoRequestFocus());
                assertFalse(window.getFocusableWindowState());
                UiTestScope.assertOffscreen(window);
            }
            child.dispose();
            frame.dispose();
        });
    }

    @Test void preservesRequestedPopupCoordinatesAcrossThemeChanges() throws Exception {
        onEdt(() -> {
            for (var theme : CompanionTheme.available()) {
                ThemeManager.installTheme(theme);
                assertInstanceOf(OffscreenPopupFactory.class, PopupFactory.getSharedInstance());
                var frame = new JFrame();
                var anchor = new JButton("Open");
                frame.add(anchor);
                frame.setSize(500, 300);
                UiTestScope.show(frame);
                var menu = new JPopupMenu();
                menu.add(new JMenuItem("Action"));
                menu.show(anchor, 20, 30);
                Point expected = anchor.getLocationOnScreen();
                expected.translate(20, 30);
                assertEquals(expected, menu.getLocationOnScreen(), theme.id());
                assertSame(menu, OffscreenPopupFactory.showingMenu());
                assertSame(frame.getLayeredPane(), menu.getParent());
                menu.setVisible(false);
                assertNull(OffscreenPopupFactory.showingMenuOrNull());
                frame.dispose();
            }
        });
    }

    @Test void syntheticFocusDeliversComponentEventsWithoutActivatingTheWindow() throws Exception {
        onEdt(() -> {
            var frame = new JFrame();
            var panel = new JPanel();
            var first = new JTextField(10);
            var second = new JTextField(10);
            var events = new ArrayList<String>();
            first.addFocusListener(new FocusAdapter() {
                @Override public void focusLost(FocusEvent event) { events.add("lost"); }
            });
            second.addFocusListener(new FocusAdapter() {
                @Override public void focusGained(FocusEvent event) { events.add("gained"); }
            });
            panel.add(first);
            panel.add(second);
            frame.add(panel);
            frame.pack();
            UiTestScope.show(frame);
            UiTestScope.focus(first);
            assertTrue(first.isFocusOwner());
            UiTestScope.focus(second);
            assertFalse(first.isFocusOwner());
            assertTrue(second.isFocusOwner());
            assertEquals(List.of("lost", "gained"), events);
            assertFalse(frame.isFocused());
            frame.dispose();
        });
    }

    @Test void guardRejectsEachMonitorWithoutShowingTheInvalidWindow() throws Exception {
        onEdt(() -> {
            var frame = new JFrame();
            for (var device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                var screen = device.getDefaultConfiguration().getBounds();
                frame.setBounds(screen.x + 10, screen.y + 10, 100, 100);
                assertThrows(AssertionError.class, () -> UiTestScope.assertOffscreen(frame));
            }
            UiTestScope.prepare(frame);
            UiTestScope.assertOffscreen(frame);
            frame.dispose();
        });
    }
}
