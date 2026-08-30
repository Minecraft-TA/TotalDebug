package com.github.minecraft_ta.totalDebugCompanion.ui.speedsearch;

import javax.swing.JComponent;

interface SpeedSearchTarget {
    JComponent component();

    int size();

    String textAt(int index);

    int selectedIndex();

    void select(int index);

    void installContentListener(Runnable listener);

    void dispose();
}
