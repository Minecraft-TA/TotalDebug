package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree;

import java.util.List;

public abstract class DirectoryTreeItem extends TreeItem {

    protected DirectoryTreeItem(String name) {
        super(name, false);
    }

    public abstract List<TreeItem> loadChildren();

    /** An empty snapshot lets a new node omit the lazy-loading placeholder. */
    boolean isInitiallyEmpty() { return false; }

    @Override
    public final boolean isDirectory() {
        return true;
    }
}
