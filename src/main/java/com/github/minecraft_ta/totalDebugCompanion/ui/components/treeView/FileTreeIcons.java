package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView;

import com.github.minecraft_ta.totalDebugCompanion.Icons;

import javax.swing.Icon;
import java.util.Locale;
import java.util.Set;

/** Assigns stable, theme-aware icons to workspace and archive tree entries. */
public final class FileTreeIcons {

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "webp", "bmp", "ico");
    private static final Set<String> CONFIG_EXTENSIONS = Set.of("toml", "cfg", "conf", "config", "ini");
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "txt", "log", "csv", "tsv", "glsl", "fsh", "vsh", "accesswidener", "at"
    );
    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            "bin", "dat", "nbt", "ogg", "wav", "mp3", "dll", "so", "dylib"
    );

    private FileTreeIcons() {
    }

    public static Icon forFileName(String fileName) {
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        if (lowerName.equals("package-info.class")) {
            return Icons.PACKAGE;
        }
        if (lowerName.equals("module-info.class")) {
            return Icons.MODULE;
        }

        String extension = extension(lowerName);
        if (IMAGE_EXTENSIONS.contains(extension)) {
            return Icons.IMAGE_FILE;
        }
        if (CONFIG_EXTENSIONS.contains(extension)) {
            return Icons.CONFIG_FILE;
        }
        if (TEXT_EXTENSIONS.contains(extension)) {
            return Icons.TEXT_FILE;
        }
        if (BINARY_EXTENSIONS.contains(extension)) {
            return Icons.BINARY_FILE;
        }
        return switch (extension) {
            case "class" -> Icons.JAVA_CLASS;
            case "java" -> Icons.JAVA_FILE;
            case "jar", "zip" -> Icons.JAR_FILE;
            case "json", "mcmeta" -> Icons.JSON_FILE;
            case "xml" -> Icons.XML_FILE;
            case "yaml", "yml" -> Icons.YAML_FILE;
            case "properties" -> Icons.PROPERTIES_FILE;
            case "md", "markdown" -> Icons.MARKDOWN_FILE;
            case "mf" -> Icons.MANIFEST_FILE;
            case "lang" -> Icons.RESOURCE_BUNDLE;
            case "html", "htm", "xhtml" -> Icons.HTML_FILE;
            case "css" -> Icons.CSS_FILE;
            case "js", "mjs", "cjs" -> Icons.JAVASCRIPT_FILE;
            case "ttf", "otf", "woff", "woff2" -> Icons.FONT_FILE;
            default -> Icons.TEXT_FILE;
        };
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
            case "mods" -> Icons.LIBRARY;
            default -> Icons.FOLDER;
        };
    }

    private static String extension(String fileName) {
        int separator = fileName.lastIndexOf('.');
        return separator == -1 ? "" : fileName.substring(separator + 1);
    }
}
