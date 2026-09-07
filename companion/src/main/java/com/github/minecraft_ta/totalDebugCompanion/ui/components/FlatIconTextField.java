package com.github.minecraft_ta.totalDebugCompanion.ui.components;

import com.formdev.flatlaf.extras.FlatSVGIcon;

import javax.swing.*;

public class FlatIconTextField extends JTextField {

    private final FlatSVGIcon icon;

    public FlatIconTextField(FlatSVGIcon icon) {
        this.icon = icon;
        putClientProperty("JTextField.leadingIcon", icon);
        setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 5));
    }

    public void setIconFilter(FlatSVGIcon.ColorFilter colorFilter) {
        this.icon.setColorFilter(colorFilter);
        repaint();
    }
}
