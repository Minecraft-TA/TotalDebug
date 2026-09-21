package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree;

import javax.swing.tree.DefaultMutableTreeNode;
import java.util.List;

public class LazyTreeNode extends DefaultMutableTreeNode {

    private boolean childrenLoaded;
    private int revision;
    private boolean refreshDescendants;
    private int selectedSegment = -1;

    public TreeItem selectedItem() {
        var parts = DirectoryChain.segments(getUserObject());
        return parts.get(selectedSegment < 0 ? parts.size() - 1 : Math.min(selectedSegment, parts.size() - 1));
    }

    int selectedSegment() {
        return selectedSegment < 0 ? DirectoryChain.segments(getUserObject()).size() - 1 : selectedSegment;
    }

    void selectSegment(int index) {
        selectedSegment = getUserObject() instanceof DirectoryChain chain && chain.supportsSegmentSelection() ? index : -1;
    }

    void resetChildren() {
        removeAllChildren();
        childrenLoaded = getUserObject() instanceof DirectoryTreeItem directory && directory.isInitiallyEmpty();
        if (!childrenLoaded) add(new DefaultMutableTreeNode("Loading..."));
        revision++;
    }

    LazyTreeNode(TreeItem treeItem) {
        super(treeItem);

        this.childrenLoaded = !treeItem.isDirectory() || treeItem.isHiddenRoot()
                || treeItem instanceof DirectoryTreeItem directory && directory.isInitiallyEmpty();
        if (!this.childrenLoaded)
            add(new DefaultMutableTreeNode("Loading..."));
    }

    boolean areChildrenLoaded() {
        return this.childrenLoaded;
    }

    void markChildrenStale() {
        markChildrenStale(false);
    }

    void markChildrenStale(boolean refreshDescendants) {
        this.childrenLoaded = false;
        this.refreshDescendants |= refreshDescendants;
        this.revision++;
    }

    int revision() { return this.revision; }

    boolean refreshDescendants() { return this.refreshDescendants; }

    void markChildrenLoaded() {
        this.childrenLoaded = true;
        this.refreshDescendants = false;
    }

    void replaceChildren(List<? extends TreeItem> items) {
        removeAllChildren();
        items.forEach(item -> add(new LazyTreeNode(item)));
        markChildrenLoaded();
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
