package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.catalog.KeyBindings;

import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a key or mouse press in Companion into the key Minecraft names it by. Minecraft names keys by their place on a
 * US keyboard, so a printable key is found by the name the game shows for it on the player's layout: the key labeled Y
 * on a German keyboard is {@code key.keyboard.z}.
 */
final class KeyPress {
    private KeyPress() {
    }

    /** Whether the key is Shift, Ctrl, Alt or AltGr, which may still be held for another key. */
    static boolean isModifier(KeyEvent event) {
        return switch (event.getKeyCode()) {
            case KeyEvent.VK_SHIFT, KeyEvent.VK_CONTROL, KeyEvent.VK_ALT, KeyEvent.VK_ALT_GRAPH -> true;
            default -> false;
        };
    }

    /** The modifier held with a key, as NeoForge names it; NeoForge knows one per binding and prefers Ctrl. */
    static String modifier(KeyEvent event) {
        if (isModifier(event)) return "NONE";
        if (event.isControlDown()) return "CONTROL";
        if (event.isShiftDown()) return "SHIFT";
        if (event.isAltDown()) return "ALT";
        return "NONE";
    }

    /** The assignment a key press makes, or null for a key Minecraft has no name for. */
    static KeyBindings.Assignment of(KeyEvent event, Map<String, String> names) {
        String key = key(event, names);
        return key == null ? null : new KeyBindings.Assignment(key, modifier(event));
    }

    /** The assignment of a mouse button: middle, 4 or 5; the left and right buttons pick and focus instead. */
    static KeyBindings.Assignment of(MouseEvent event) {
        return switch (event.getButton()) {
            case MouseEvent.BUTTON2 -> new KeyBindings.Assignment("key.mouse.middle", "NONE");
            case 4 -> new KeyBindings.Assignment("key.mouse.4", "NONE");
            case 5 -> new KeyBindings.Assignment("key.mouse.5", "NONE");
            default -> null;
        };
    }

    static String key(KeyEvent event, Map<String, String> names) {
        boolean right = event.getKeyLocation() == KeyEvent.KEY_LOCATION_RIGHT;
        boolean numpad = event.getKeyLocation() == KeyEvent.KEY_LOCATION_NUMPAD;
        int code = event.getKeyCode();
        String named = switch (code) {
            case KeyEvent.VK_SHIFT -> right ? "right.shift" : "left.shift";
            case KeyEvent.VK_CONTROL -> right ? "right.control" : "left.control";
            case KeyEvent.VK_ALT -> right ? "right.alt" : "left.alt";
            case KeyEvent.VK_ALT_GRAPH -> "right.alt";
            case KeyEvent.VK_WINDOWS, KeyEvent.VK_META -> right ? "right.win" : "left.win";
            case KeyEvent.VK_SPACE -> "space";
            case KeyEvent.VK_TAB -> "tab";
            case KeyEvent.VK_ENTER -> numpad ? "keypad.enter" : "enter";
            case KeyEvent.VK_ESCAPE -> "escape";
            case KeyEvent.VK_BACK_SPACE -> "backspace";
            case KeyEvent.VK_INSERT -> "insert";
            case KeyEvent.VK_DELETE -> "delete";
            case KeyEvent.VK_HOME -> "home";
            case KeyEvent.VK_END -> "end";
            case KeyEvent.VK_PAGE_UP -> "page.up";
            case KeyEvent.VK_PAGE_DOWN -> "page.down";
            case KeyEvent.VK_UP -> "up";
            case KeyEvent.VK_DOWN -> "down";
            case KeyEvent.VK_LEFT -> "left";
            case KeyEvent.VK_RIGHT -> "right";
            case KeyEvent.VK_CAPS_LOCK -> "caps.lock";
            case KeyEvent.VK_SCROLL_LOCK -> "scroll.lock";
            case KeyEvent.VK_NUM_LOCK -> "num.lock";
            case KeyEvent.VK_PRINTSCREEN -> "print.screen";
            case KeyEvent.VK_PAUSE -> "pause";
            case KeyEvent.VK_CONTEXT_MENU -> "menu";
            case KeyEvent.VK_ADD -> "keypad.add";
            case KeyEvent.VK_SUBTRACT -> "keypad.subtract";
            case KeyEvent.VK_MULTIPLY -> "keypad.multiply";
            case KeyEvent.VK_DIVIDE -> "keypad.divide";
            case KeyEvent.VK_DECIMAL -> "keypad.decimal";
            default -> null;
        };
        if (named != null) return "key.keyboard." + named;
        if (code >= KeyEvent.VK_NUMPAD0 && code <= KeyEvent.VK_NUMPAD9) return "key.keyboard.keypad." + (code - KeyEvent.VK_NUMPAD0);
        if (code >= KeyEvent.VK_F1 && code <= KeyEvent.VK_F12) return "key.keyboard.f" + (code - KeyEvent.VK_F1 + 1);
        if (code >= KeyEvent.VK_F13 && code <= KeyEvent.VK_F24) return "key.keyboard.f" + (code - KeyEvent.VK_F13 + 13);
        String label = label(event);
        if (label == null) return null;
        for (Map.Entry<String, String> name : names.entrySet()) {
            if (name.getKey().startsWith("key.keyboard.") && name.getValue().equalsIgnoreCase(label)) return name.getKey();
        }
        // Without the game's names, a letter or digit is taken at its US place.
        return label.length() == 1 && Character.isLetterOrDigit(label.charAt(0))
                ? "key.keyboard." + label.toLowerCase(Locale.ROOT) : null;
    }

    /**
     * What the key is labeled with: the character it types, or its name while Ctrl or Alt changes that character.
     * Shift types another character on the same key, such as ! on 1, so a shifted key is named by its own character.
     */
    private static String label(KeyEvent event) {
        char typed = event.getKeyChar();
        boolean printable = typed != KeyEvent.CHAR_UNDEFINED && !Character.isISOControl(typed) && !Character.isWhitespace(typed);
        if (event.isShiftDown()) {
            String unshifted = unshifted(event.getKeyCode());
            if (unshifted != null) return unshifted;
        }
        if (printable) return String.valueOf(typed).toUpperCase(Locale.ROOT);
        String text = KeyEvent.getKeyText(event.getKeyCode());
        return text.length() == 1 ? text.toUpperCase(Locale.ROOT) : null;
    }

    /** The character a key types without Shift, as its key code tells it, or null when the code does not. */
    private static String unshifted(int code) {
        if (code >= KeyEvent.VK_0 && code <= KeyEvent.VK_9 || code >= KeyEvent.VK_A && code <= KeyEvent.VK_Z) {
            return String.valueOf((char) code);
        }
        return switch (code) {
            case KeyEvent.VK_MINUS -> "-";
            case KeyEvent.VK_EQUALS -> "=";
            case KeyEvent.VK_PLUS -> "+";
            case KeyEvent.VK_COMMA -> ",";
            case KeyEvent.VK_PERIOD -> ".";
            case KeyEvent.VK_SLASH -> "/";
            case KeyEvent.VK_BACK_SLASH -> "\\";
            case KeyEvent.VK_SEMICOLON -> ";";
            case KeyEvent.VK_QUOTE -> "'";
            case KeyEvent.VK_BACK_QUOTE -> "`";
            case KeyEvent.VK_OPEN_BRACKET -> "[";
            case KeyEvent.VK_CLOSE_BRACKET -> "]";
            case KeyEvent.VK_NUMBER_SIGN -> "#";
            case KeyEvent.VK_LESS -> "<";
            default -> null;
        };
    }
}
