package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.catalog.KeyBindings;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import java.awt.event.KeyEvent;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KeyPressTest {
    private static KeyEvent press(int modifiers, int code, char typed) {
        return new KeyEvent(new JButton(), KeyEvent.KEY_PRESSED, 0, modifiers, code, typed);
    }

    @Test
    void aShiftedKeyIsNamedByItsOwnCharacterNotTheOneShiftTypes() {
        assertEquals(new KeyBindings.Assignment("key.keyboard.1", "SHIFT"),
                KeyPress.of(press(KeyEvent.SHIFT_DOWN_MASK, KeyEvent.VK_1, '!'), Map.of("key.keyboard.1", "1")));
        assertEquals(new KeyBindings.Assignment("key.keyboard.minus", "SHIFT"),
                KeyPress.of(press(KeyEvent.SHIFT_DOWN_MASK, KeyEvent.VK_MINUS, '_'), Map.of("key.keyboard.minus", "-")));
    }

    @Test
    void anUnshiftedKeyFollowsTheLayoutNames() {
        // On a German layout the key at the US Y place types z.
        assertEquals(new KeyBindings.Assignment("key.keyboard.y", "NONE"),
                KeyPress.of(press(0, KeyEvent.VK_Z, 'z'), Map.of("key.keyboard.y", "Z", "key.keyboard.z", "Y")));
    }
}
