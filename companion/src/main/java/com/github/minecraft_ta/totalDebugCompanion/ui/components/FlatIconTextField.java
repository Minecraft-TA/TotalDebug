package com.github.minecraft_ta.totalDebugCompanion.ui.components;

import com.formdev.flatlaf.extras.FlatSVGIcon;

import javax.swing.*;

public class FlatIconTextField extends JTextField {

    public FlatIconTextField(FlatSVGIcon icon) {
        putClientProperty("JTextField.leadingIcon", icon);
        setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 5));
    }
}
