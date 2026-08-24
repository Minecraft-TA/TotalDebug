package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.util.FileUtils;

import javax.swing.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public class FileSystemDirectoryItem extends DirectoryTreeItem {

    private final LazyFileJTree tree;
    private final Path path;
    private final Runnable stopWatching;

    FileSystemDirectoryItem(LazyFileJTree lazyFileJTree, Path path, boolean watch) {
        super(path.getFileName().toString());
        this.tree = lazyFileJTree;
        if (!Files.isDirectory(path))
            throw new IllegalArgumentException("Not a directory");

        this.path = path;
        setIcon(Icons.FOLDER);

        this.stopWatching = watch
                ? FileUtils.startNewDirectoryWatcher(path, () -> SwingUtilities.invokeLater(() -> lazyFileJTree.loadItemsForTopLevelItem(this)))
                : () -> {};
    }

    @Override
    public List<TreeItem> loadChildren() {
        try (var paths = Files.list(this.path)) {
            return paths.map(this::createChildIfPresent)
                    .filter(Objects::nonNull)
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private TreeItem createChildIfPresent(Path child) {
        try {
            if (Files.isDirectory(child))
                return tree.getItemFactory().createFileSystemDirectoryItem(child, false);
            if (!Files.exists(child))
                return null;
            return tree.getItemFactory().createFileSystemFileItem(child);
        } catch (IllegalArgumentException exception) {
            if (!Files.exists(child))
                return null;
            throw exception;
        }
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
