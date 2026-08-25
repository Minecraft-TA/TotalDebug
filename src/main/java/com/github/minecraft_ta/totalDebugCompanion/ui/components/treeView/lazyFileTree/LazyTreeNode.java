package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree;

import javax.swing.tree.DefaultMutableTreeNode;

public class LazyTreeNode extends DefaultMutableTreeNode {

    private boolean childrenLoaded;

    LazyTreeNode(TreeItem treeItem) {
        super(treeItem);

        this.childrenLoaded = !treeItem.isDirectory() || treeItem.isHiddenRoot();
        if (!this.childrenLoaded)
            add(new DefaultMutableTreeNode("Loading..."));
    }

    boolean areChildrenLoaded() {
        return this.childrenLoaded;
    }

    void markChildrenStale() {
        this.childrenLoaded = false;
    }

    void replaceChildren(java.util.List<TreeItem> items) {
        removeAllChildren();
        items.forEach(item -> add(new LazyTreeNode(item)));
        this.childrenLoaded = true;
    }

    @Override
    public LazyTreeNode getParent() {
        return (LazyTreeNode) super.getParent();
    }

    @Override
    public TreeItem getUserObject() {
        return (TreeItem) super.getUserObject();
    }
}
