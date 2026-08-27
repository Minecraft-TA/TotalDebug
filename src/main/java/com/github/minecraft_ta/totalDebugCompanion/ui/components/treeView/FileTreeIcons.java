package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.resource.FileTypeResolver;

import javax.swing.Icon;
import java.util.Locale;

/** Assigns stable, theme-aware icons to workspace and archive tree entries. */
public final class FileTreeIcons {

    private FileTreeIcons() {
    }

    public static Icon forFileName(String fileName) {
        return FileTypeResolver.resolve(fileName).icon();
    }

    public static Icon forArchiveDirectory(String directoryName, boolean resourceTree, boolean packageTree) {
        if (directoryName.equalsIgnoreCase("assets") || directoryName.equalsIgnoreCase("data")) {
            return Icons.RESOURCES_ROOT;
        }
        if (directoryName.equalsIgnoreCase("META-INF") || resourceTree) {
            return Icons.FOLDER;
        }
        return packageTree ? Icons.PACKAGE : Icons.FOLDER;
    }

    public static Icon forRootDirectory(String directoryName) {
        return switch (directoryName.toLowerCase(Locale.ROOT)) {
            case "scripts", "decompiled-files" -> Icons.SOURCE_ROOT;
            case "runtime" -> Icons.LIBRARY;
            default -> Icons.FOLDER;
        };
    }
}
