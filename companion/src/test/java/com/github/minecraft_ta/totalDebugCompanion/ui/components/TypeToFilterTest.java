package com.github.minecraft_ta.totalDebugCompanion.ui.components;

import org.junit.jupiter.api.Test;

import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TypeToFilterTest {
    @Test
    void typingInTheTableFillsTheFilterAndEditingKeysChangeIt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTable table = new JTable(3, 2);
            JTextField filter = new JTextField();
            TypeToFilter.forwardTyping(table, () -> filter);

            type(table, 'w');
            type(table, 'i');
            assertEquals("wi", filter.getText());

            press(table, KeyEvent.VK_BACK_SPACE, 0);
            assertEquals("w", filter.getText());

            type(table, 'c', InputEvent.CTRL_DOWN_MASK);
            assertEquals("w", filter.getText(), "Shortcuts stay with the table");

            press(table, KeyEvent.VK_ESCAPE, 0);
            assertEquals("", filter.getText());
        });
    }

    private static void type(Component target, char character) {
        type(target, character, 0);
    }

    /** Headless components never hold focus, so key events go straight to the listeners the helper installed. */
    private static void type(Component target, char character, int modifiers) {
        KeyEvent event = new KeyEvent(target, KeyEvent.KEY_TYPED, 0, modifiers, KeyEvent.VK_UNDEFINED, character);
        for (KeyListener listener : target.getKeyListeners()) listener.keyTyped(event);
    }

    private static void press(Component target, int key, int modifiers) {
        KeyEvent event = new KeyEvent(target, KeyEvent.KEY_PRESSED, 0, modifiers, key, KeyEvent.CHAR_UNDEFINED);
        for (KeyListener listener : target.getKeyListeners()) listener.keyPressed(event);
    }
}
