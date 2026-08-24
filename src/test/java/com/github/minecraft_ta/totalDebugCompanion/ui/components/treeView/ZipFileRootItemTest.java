package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView;

import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.TreeItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZipFileRootItemTest {

    @TempDir
    Path directory;

    @Test
    void archiveLeavesRetainTheirPathSizeAndContentSource() throws Exception {
        Path archive = this.directory.resolve("sample.jar");
        byte[] contents = "enabled = true\n".getBytes(StandardCharsets.UTF_8);
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new ZipEntry("config/defaults.toml"));
            output.write(contents);
            output.closeEntry();
        }

        ZipFileRootItem root = new ZipFileRootItem(archive);
        TreeItem config = itemNamed(root.loadChildren(), "config");
        ZipFileRootItem.Entry entry = (ZipFileRootItem.Entry) itemNamed(
                ((ZipFileRootItem.DirectoryEntry) config).loadChildren(),
                "defaults.toml"
        );

        assertEquals("config/defaults.toml", entry.getEntryPath());
        assertEquals(contents.length, entry.getSize());
        assertTrue(entry.getCompressedSize() > 0);
        assertArrayEquals(contents, entry.contentSource().read(1024));
    }

    private static TreeItem itemNamed(List<TreeItem> items, String name) {
        return items.stream().filter(item -> item.getName().equals(name)).findFirst().orElseThrow();
    }
}
