package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree;

import com.github.minecraft_ta.totalDebugCompanion.ui.Tooltip;

import java.nio.file.Files;
import java.nio.file.Path;

public class FileSystemFileItem extends TreeItem {

    private final Path path;

    FileSystemFileItem(Path path) {
        super(path.getFileName().toString());
        if (!Files.exists(path))
            throw new IllegalArgumentException("File does not exist");

        this.path = path;
    }

    public Path getPath() {
        return path;
    }

    @Override
    public String getTooltip() {
        return Tooltip.of(Tooltip.shortPath(this.path)).html();
    }

    @Override
    public String location() {
        return this.path.toAbsolutePath().normalize().toString();
    }
}
