package com.github.minecraft_ta.totalDebugCompanion.ui.components.inspection;

import javax.swing.UIManager;
import java.awt.Color;

/** The one color marking a value that changed since the previous read. */
final class ChangeMarks {
    private ChangeMarks() {
    }

    static Color color() {
        Color accent = UIManager.getColor("Component.accentColor");
        return accent == null ? new Color(0xE0A020) : accent;
    }

    /** A translucent tint of the change color for row backgrounds. */
    static Color tint() {
        Color color = color();
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), 60);
    }
}
