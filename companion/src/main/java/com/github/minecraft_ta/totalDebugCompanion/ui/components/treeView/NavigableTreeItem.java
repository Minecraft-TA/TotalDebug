package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView;

import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;

/** A tree node that opens a page when activated, whether or not it also has children. */
interface NavigableTreeItem {
    NavigationTarget navigationTarget();
}
