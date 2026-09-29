package com.github.minecraft_ta.totalDebugCompanion.ui.components;

import javax.swing.JPanel;
import java.awt.event.HierarchyEvent;

/** A page that is shown and hidden without a window, as a tab being selected and left. Swing thread only. */
final class ShowablePage extends JPanel {
    private boolean shown;

    @Override
    public boolean isShowing() {
        return this.shown;
    }

    void setShown(boolean shown) {
        this.shown = shown;
        dispatchEvent(new HierarchyEvent(this, HierarchyEvent.HIERARCHY_CHANGED, this, getParent(), HierarchyEvent.SHOWING_CHANGED));
    }
}
