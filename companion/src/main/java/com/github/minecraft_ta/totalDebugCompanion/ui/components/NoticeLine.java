package com.github.minecraft_ta.totalDebugCompanion.ui.components;

import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;

import javax.swing.JLabel;

/** The line under a view's bar that says why something asked for failed, hidden while there is nothing to say. */
public final class NoticeLine extends JLabel {
    public NoticeLine() {
        ThemeColors.keepForeground(this, ThemeColors::error);
        setBorder(UiMetrics.noticePadding());
        setVisible(false);
    }

    /** Shows {@code text}, or hides the line for an empty one. */
    public void show(String text) {
        setText(text);
        setVisible(!text.isEmpty());
    }
}
