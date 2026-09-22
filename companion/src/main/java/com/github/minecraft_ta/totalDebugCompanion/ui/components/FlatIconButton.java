package com.github.minecraft_ta.totalDebugCompanion.ui.components;

import javax.swing.*;
import java.awt.Insets;

/** Compact toolbar buttons using the look and feel's normal hover, focus, and selected states. */
public class FlatIconButton extends JButton {
    public FlatIconButton(Icon icon, boolean toggleable) {
        super(icon);
        if (toggleable) setModel(new JToggleButton.ToggleButtonModel());
        configure(this);
    }

    public static void configure(AbstractButton button) {
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        button.setMargin(new Insets(6, 6, 6, 6));
        // Mouse commands keep the editor's focus; Tab and Space still operate the button.
        button.setRequestFocusEnabled(false);
        InputMap keys = button.getInputMap(WHEN_FOCUSED);
        keys.put(KeyStroke.getKeyStroke("pressed ENTER"), keys.get(KeyStroke.getKeyStroke("pressed SPACE")));
        keys.put(KeyStroke.getKeyStroke("released ENTER"), keys.get(KeyStroke.getKeyStroke("released SPACE")));
    }
}
