package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree;

import java.nio.file.Path;

public class FileTreeItemFactory {

    protected LazyFileJTree tree;

    public TreeItem createHiddenRoot() {
        return new TreeItem("", true);
    }

    public FileSystemDirectoryItem createFileSystemDirectoryItem(Path path, boolean watch) {
        return new FileSystemDirectoryItem(tree, path, watch);
    }

    public FileSystemFileItem createFileSystemFileItem(Path path) {
        return new FileSystemFileItem(path);
    }

    public void setTree(LazyFileJTree tree) {
        this.tree = tree;
    }
}
