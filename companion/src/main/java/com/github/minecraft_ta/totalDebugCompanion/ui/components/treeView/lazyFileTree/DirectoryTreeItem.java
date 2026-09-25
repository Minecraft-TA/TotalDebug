package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree;

import java.util.List;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;

public abstract class DirectoryTreeItem extends TreeItem {

    protected DirectoryTreeItem(String name) {
        super(name, false);
    }

    public abstract List<TreeItem> loadChildren();

    /** An empty snapshot lets a new node omit the lazy-loading placeholder. */
    protected boolean isInitiallyEmpty() { return false; }

    /** Null marks a structural container that must keep its own row. */
    public String compactSeparator() { return null; }

    /** Called only for eligible directories, on the loading worker. */
    public DirectoryTreeItem singleDirectoryChild() throws IOException { return null; }
    public Object compactIdentity() throws IOException {
        String location = location();
        return location != null ? location : getName();
    }

    protected static boolean ordinaryDirectory(Path path) throws IOException {
        var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return attributes.isDirectory() && !attributes.isSymbolicLink() && !attributes.isOther();
    }

    protected static List<Path> firstChildren(Path path) throws IOException {
        var result = new ArrayList<Path>(2);
        try (var entries = Files.newDirectoryStream(path)) {
            var iterator = entries.iterator();
            while (result.size() < 2 && iterator.hasNext()) result.add(iterator.next());
        }
        return result;
    }

    @Override
    public final boolean isDirectory() {
        return true;
    }
}
