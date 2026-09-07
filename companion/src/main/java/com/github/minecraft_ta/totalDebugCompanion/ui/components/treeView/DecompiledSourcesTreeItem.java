package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.decompile.CompanionDecompilationService;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.*;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryText;
import com.github.minecraft_ta.totalDebugCompanion.util.FileUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/** Shows cached source units, not their storage metadata or generation directories. */
final class DecompiledSourcesTreeItem extends DirectoryTreeItem {
    private final CompanionDecompilationService service;
    private final Runnable stopWatching;

    DecompiledSourcesTreeItem(LazyFileJTree tree, CompanionDecompilationService service) {
        super("decompiled-files");
        this.service = service;
        setIcon(Icons.FOLDER);
        this.stopWatching = FileUtils.startNewDirectoryWatcher(service.cacheDirectory(),
                () -> tree.loadItemsForTopLevelItem(this));
    }

    @Override
    public List<TreeItem> loadChildren() {
        try {
            return this.service.cachedClasses().stream().<TreeItem>map(SourceItem::new).toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @Override
    public void dispose() {
        this.stopWatching.run();
    }

    static final class SourceItem extends TreeItem {
        private final String binaryName;

        SourceItem(String binaryName) {
            super(binaryName + ".java");
            this.binaryName = binaryName;
            int split = binaryName.lastIndexOf('.');
            setPresentation(new PrimarySecondaryText(binaryName.substring(split + 1) + ".java",
                    split < 0 ? "" : binaryName.substring(0, split)));
            setIcon(Icons.JAVA_CLASS);
        }

        String binaryName() {
            return this.binaryName;
        }
    }
}
