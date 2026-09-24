package com.github.minecraft_ta.totalDebugCompanion.ui.speedsearch;

import javax.swing.JComponent;

/**
 * What a speed search moves through: entries by index, which need not all be visible. Selecting an entry is expected
 * to reveal it, for example by expanding the rows above it.
 */
public interface SpeedSearchTarget {
    JComponent component();

    int size();

    String textAt(int index);

    int selectedIndex();

    void select(int index);

    void installContentListener(Runnable listener);

    void dispose();
}
