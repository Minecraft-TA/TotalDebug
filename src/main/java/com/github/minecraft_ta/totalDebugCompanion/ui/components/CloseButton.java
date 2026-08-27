package com.github.minecraft_ta.totalDebugCompanion.ui.components;

import com.github.minecraft_ta.totalDebugCompanion.Icons;

import javax.swing.*;
import java.awt.*;

public class CloseButton extends JButton {

    public CloseButton() {
        int size = 16;
        setPreferredSize(new Dimension(size, size));
        setBorder(BorderFactory.createEmptyBorder());
        setMargin(new Insets(0, 0, 0, 0));
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusable(false);
        setRolloverEnabled(true);
        setIcon(Icons.CLOSE_ICON);
        setRolloverIcon(Icons.CLOSE_HOVERED_ICON);
        setPressedIcon(Icons.CLOSE_HOVERED_ICON);
    }
}
