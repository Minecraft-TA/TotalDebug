package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.util.FileUtils;

import javax.swing.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.DirectoryIteratorException;
import java.util.List;
import java.util.ArrayList;

public class FileSystemDirectoryItem extends DirectoryTreeItem {

    private final LazyFileJTree tree;
    private final Path path;
    private final Runnable stopWatching;
    private final boolean watch;
    private boolean initiallyEmpty;
    private volatile boolean changed;
    public Path getPath() { return path; }
    boolean changedDuringDiscovery() { return changed; }

    FileSystemDirectoryItem(LazyFileJTree lazyFileJTree, Path path, boolean watch) {
        super(path.getFileName().toString());
        this.tree = lazyFileJTree;
        this.watch = watch;
        if (!Files.isDirectory(path))
            throw new IllegalArgumentException("Not a directory");

        this.path = path;
        setIcon(Icons.FOLDER);

        this.stopWatching = watch
                ? FileUtils.startNewDirectoryWatcher(path, () -> {
                    changed = true;
                    SwingUtilities.invokeLater(() -> lazyFileJTree.refreshDirectory(path));
                })
                : () -> {};
    }

    @Override
    public List<TreeItem> loadChildren() {
        var children = new ArrayList<TreeItem>();
        try (var paths = Files.list(this.path)) {
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                var child = createChildIfPresent(iterator.next());
                if (child != null) children.add(child);
            }
            return children;
        } catch (IOException | RuntimeException failure) {
            children.forEach(TreeItem::dispose);
            throw new IllegalStateException("Unable to read " + path, failure);
        }
    }

    private TreeItem createChildIfPresent(Path child) {
        try {
            if (Files.isDirectory(child)) {
                var item = tree.getItemFactory().createFileSystemDirectoryItem(child, watch);
                // This runs in the parent's background scan. Peek once, without loading descendants.
                try (var entries = Files.newDirectoryStream(child)) {
                    item.initiallyEmpty = !entries.iterator().hasNext();
                } catch (IOException | DirectoryIteratorException ignored) {
                    // Unknown contents stay lazy; opening the folder reports any read failure.
                }
                return item;
            }
            return tree.getItemFactory().createFileSystemFileItem(child);
        } catch (IllegalArgumentException exception) {
            if (!Files.exists(child))
                return null;
            throw exception;
        }
    }

    @Override
    protected boolean isInitiallyEmpty() {
        // A watcher notification before attachment invalidates the initial snapshot too.
        return initiallyEmpty && !changed;
    }

    @Override public String compactSeparator() { return "/"; }
    @Override public Object compactIdentity() throws IOException { return path.toRealPath(); }

    @Override public DirectoryTreeItem singleDirectoryChild() throws IOException {
        if (!ordinaryDirectory(path)) return null;
        var entries = firstChildren(path);
        initiallyEmpty = entries.isEmpty();
        if (entries.size() != 1 || !ordinaryDirectory(entries.getFirst())) return null;
        TreeItem child = createChildIfPresent(entries.getFirst());
        if (child instanceof DirectoryTreeItem directory) return directory;
        if (child != null) child.dispose();
        return null;
    }

    @Override
    public void dispose() {
        this.stopWatching.run();
    }

    @Override
    public String getTooltip() {
        return this.path.toString();
    }
}
