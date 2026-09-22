package com.github.minecraft_ta.totalDebugCompanion.ui.components;

import com.github.minecraft_ta.totalDebugCompanion.testui.UiTest;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.ui.PopupElements;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.CompanionTheme;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.DefaultKeyboardFocusManager;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import java.util.concurrent.atomic.AtomicInteger;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

@UiTest
class FlatIconButtonTest {
    @Test void popupActionsPaintDistinctHoverAndPressedStates() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (var theme : CompanionTheme.available()) {
                ThemeManager.installTheme(theme);
                for (JButton button : new JButton[]{PopupElements.icon(Icons.DELETE, "Clear history", () -> {}),
                        PopupElements.link("Copy details", Icons.COPY, () -> {})}) {
                    int[] normal = pixels(button);
                    button.dispatchEvent(new MouseEvent(button, MouseEvent.MOUSE_ENTERED, 0, 0, 4, 4, 0, false));
                    assertTrue(button.getModel().isRollover(), "Mouse entry must update the model");
                    int[] hover = pixels(button);
                    assertFalse(Arrays.equals(normal, hover), theme.id() + " " + button.getToolTipText() + " hover");
                    button.getModel().setArmed(true);
                    button.getModel().setPressed(true);
                    assertFalse(Arrays.equals(hover, pixels(button)), theme.id() + " " + button.getToolTipText() + " press");
                    button.getModel().setArmed(false);
                    button.getModel().setPressed(false);
                }
            }
        });
    }

    @Test
    void bothThemesPaintDistinctKeyboardFocusAndSelectedStates() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var previous = KeyboardFocusManager.getCurrentKeyboardFocusManager();
            // Offscreen components have no ancestor window; unrelated UI tests may leave another window active.
            KeyboardFocusManager.setCurrentKeyboardFocusManager(new DefaultKeyboardFocusManager() {
                @Override public Window getActiveWindow() { return null; }
            });
            try {
                for (var theme : CompanionTheme.available()) {
                    ThemeManager.installTheme(theme);
                    FlatIconButton button = new FlatIconButton(Icons.MATCH_CASE, true);
                    int[] normal = pixels(button);
                    button.putClientProperty("JComponent.focusOwner", (Predicate<JComponent>) component -> true);
                    assertFalse(Arrays.equals(normal, pixels(button)), theme.id() + " keyboard focus must be visible");
                    button.putClientProperty("JComponent.focusOwner", null);
                    button.setSelected(true);
                    assertFalse(Arrays.equals(normal, pixels(button)), theme.id() + " selection must be visible without hover");
                    var toggle = new JToggleButton(Icons.MUTE_BREAKPOINTS);
                    FlatIconButton.configure(toggle);
                    int[] unselected = pixels(toggle);
                    toggle.setSelected(true);
                    assertFalse(Arrays.equals(unselected, pixels(toggle)), theme.id() + " native toggle selection must be visible");
                    var field = new JTextField("query", 10);
                    int[] unfocused = pixels(field);
                    field.putClientProperty("JComponent.focusOwner", (Predicate<JComponent>) component -> true);
                    assertFalse(Arrays.equals(unfocused, pixels(field)), theme.id() + " text-field focus must be visible");
                    assertTrue(UIManager.getColor("Component.focusColor").getAlpha() > 0);
                    assertTrue(UIManager.getColor("Slider.focusedColor").getAlpha() > 0);
                }
            } finally {
                KeyboardFocusManager.setCurrentKeyboardFocusManager(previous);
            }
        });
    }

    private static int[] pixels(JComponent button) {
        button.setSize(button.getPreferredSize());
        var image = new BufferedImage(button.getWidth(), button.getHeight(), BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        button.paint(graphics);
        graphics.dispose();
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }

    @Test
    void clickingAndSpaceToggleTheSameModelExactlyOnce() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FlatIconButton button = new FlatIconButton(Icons.MATCH_CASE, true);
            AtomicInteger actions = new AtomicInteger();
            button.addActionListener(event -> actions.incrementAndGet());
            button.doClick(0);
            assertTrue(button.isSelected());
            assertEquals(1, actions.get());
            press(button, "SPACE");
            assertFalse(button.isSelected());
            assertEquals(2, actions.get());
            press(button, "ENTER");
            assertTrue(button.isSelected());
            assertEquals(3, actions.get());
            button.setSelected(true);
            button.getModel().setRollover(true);
            button.getModel().setRollover(false);
            assertTrue(button.isSelected());
            button.setEnabled(false);
            press(button, "SPACE");
            button.dispatchEvent(new MouseEvent(button, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(),
                    0, 3, 3, 1, false, MouseEvent.BUTTON3));
            assertTrue(button.isSelected());
            assertEquals(3, actions.get());
        });
    }

    @Test
    void ordinaryIconButtonsDoNotToggleAndKeepMouseFocusInTheEditor() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FlatIconButton button = new FlatIconButton(Icons.RUN, false);
            button.doClick(0);
            assertFalse(button.isSelected());
            assertTrue(button.isFocusable());
            assertTrue(button.isFocusPainted());
            assertFalse(button.isRequestFocusEnabled());
        });
    }

    private static void press(AbstractButton button, String keyName) {
        for (String stroke : new String[]{"pressed " + keyName, "released " + keyName}) {
            var key = button.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(stroke));
            Action action = button.getActionMap().get(key);
            assertNotNull(action, stroke);
            action.actionPerformed(new ActionEvent(button, ActionEvent.ACTION_PERFORMED, stroke));
        }
    }
}
