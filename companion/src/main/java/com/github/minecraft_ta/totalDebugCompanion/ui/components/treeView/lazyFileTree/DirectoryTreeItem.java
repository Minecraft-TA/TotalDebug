package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree;

import java.util.List;

public abstract class DirectoryTreeItem extends TreeItem {

    protected DirectoryTreeItem(String name) {
        super(name, false);
    }

    public abstract List<TreeItem> loadChildren();

    @Override
    public final boolean isDirectory() {
        return true;
    }
}
