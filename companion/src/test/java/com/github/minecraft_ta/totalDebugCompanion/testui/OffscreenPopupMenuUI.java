package com.github.minecraft_ta.totalDebugCompanion.testui;

import com.formdev.flatlaf.ui.FlatPopupMenuUI;

import javax.swing.JComponent;
import javax.swing.JPopupMenu;
import javax.swing.Popup;
import javax.swing.PopupFactory;
import javax.swing.plaf.ComponentUI;

/** Keep FlatLaf painting/layout, but leave placement to the offscreen fixture instead of a monitor. */
public final class OffscreenPopupMenuUI extends FlatPopupMenuUI {
    public static ComponentUI createUI(JComponent component) {
        return new OffscreenPopupMenuUI();
    }

    @Override public Popup getPopup(JPopupMenu popup, int x, int y) {
        return PopupFactory.getSharedInstance().getPopup(popup.getInvoker(), popup, x, y);
    }
}
