package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree;

import java.nio.file.Path;

public class FileTreeItemFactory {

    protected LazyFileJTree tree;

    public TreeItem createHiddenRoot() {
        return new TreeItem("", true);
    }

    public FileSystemDirectoryItem createFileSystemDirectoryItem(Path path) {
        return new FileSystemDirectoryItem(tree, path);
    }

    /**
     * The root of a folder a reading found, as the Scripts folder, built without checking the folder again, so on the
     * Swing thread; a folder removed meanwhile shows the failure of its first load.
     */
    public FileSystemDirectoryItem createRootDirectoryItem(Path path) {
        return new FileSystemDirectoryItem(tree, path, false);
    }

    public FileSystemFileItem createFileSystemFileItem(Path path) {
        return new FileSystemFileItem(path);
    }

    public void setTree(LazyFileJTree tree) {
        this.tree = tree;
    }
}
