package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

class FileTreeIconsTest {

    @Test
    void assignsSpecificIconsToCommonModArchiveFiles() {
        assertSame(Icons.JAVA_CLASS, FileTreeIcons.forFileName("Example.class"));
        assertSame(Icons.JAVA_CLASS, FileTreeIcons.forFileName("package-info.class"));
        assertSame(Icons.MODULE, FileTreeIcons.forFileName("module-info.class"));
        assertSame(Icons.IMAGE_FILE, FileTreeIcons.forFileName("debug.PNG"));
        assertSame(Icons.JSON_FILE, FileTreeIcons.forFileName("pack.mcmeta"));
        assertSame(Icons.CONFIG_FILE, FileTreeIcons.forFileName("neoforge.mods.toml"));
        assertSame(Icons.PROPERTIES_FILE, FileTreeIcons.forFileName("gradle.properties"));
        assertSame(Icons.MANIFEST_FILE, FileTreeIcons.forFileName("MANIFEST.MF"));
        assertSame(Icons.RESOURCE_BUNDLE, FileTreeIcons.forFileName("en_us.lang"));
        assertSame(Icons.BINARY_FILE, FileTreeIcons.forFileName("level.dat"));
    }

    @Test
    void distinguishesPackagesResourcesAndWorkspaceRoots() {
        assertSame(Icons.PACKAGE, FileTreeIcons.forArchiveDirectory("minecraft", false, true));
        assertSame(Icons.FOLDER, FileTreeIcons.forArchiveDirectory("docs", false, false));
        assertSame(Icons.RESOURCES_ROOT, FileTreeIcons.forArchiveDirectory("assets", true, false));
        assertSame(Icons.RESOURCES_ROOT, FileTreeIcons.forArchiveDirectory("data", true, false));
        assertSame(Icons.FOLDER, FileTreeIcons.forArchiveDirectory("textures", true, false));
        assertSame(Icons.SOURCE_ROOT, FileTreeIcons.forRootDirectory("scripts"));
        assertSame(Icons.SOURCE_ROOT, FileTreeIcons.forRootDirectory("decompiled-files"));
        assertSame(Icons.LIBRARY, FileTreeIcons.forRootDirectory("runtime"));
    }
}
