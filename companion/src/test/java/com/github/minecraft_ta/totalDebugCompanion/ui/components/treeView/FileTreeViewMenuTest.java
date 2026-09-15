package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.LazyFileJTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class FileTreeViewMenuTest {
    @TempDir Path directory;

    @Test
    void classFileFolderAndArchiveMenusCopyTheirActualIdentity() throws Exception {
        Path file = Files.writeString(directory.resolve("Test.java"), "class Test {}");
        Path archive = directory.resolve("classes.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("Test.class"));
            zip.write(new byte[]{0});
            zip.closeEntry();
        }
        SwingUtilities.invokeAndWait(() -> {
            List<NavigationTarget> opened = new ArrayList<>();
            var view = new FileTreeView(() -> null, opened::add);
            var tree = (LazyFileJTree) view.getViewport().getView();
            var sourceMenu = view.createContextMenu(new DecompiledSourcesTreeItem.SourceItem("example.Test"), opened::add);
            assertEquals("example.Test", item(sourceMenu, "Copy reference").getActionCommand());
            assertSame(Icons.JUMP_TO_SOURCE, item(sourceMenu, "Open source").getIcon());
            item(sourceMenu, "Open source").doClick(0);
            assertEquals(List.of(new NavigationTarget.RuntimeClass("example.Test")), opened);
            var fileMenu = view.createContextMenu(tree.getItemFactory().createFileSystemFileItem(file), opened::add);
            assertEquals(file.toString(), item(fileMenu, "Copy path").getActionCommand());
            assertNotNull(item(fileMenu, "Delete file"));
            var folderMenu = view.createContextMenu(tree.getItemFactory().createFileSystemDirectoryItem(directory, false), opened::add);
            assertEquals(1, folderMenu.getComponentCount());
            assertEquals(directory.toString(), item(folderMenu, "Copy path").getActionCommand());
            var root = new ZipFileRootItem(archive);
            assertEquals(archive.toString(), item(view.createContextMenu(root, opened::add), "Copy path").getActionCommand());
            var entryMenu = view.createContextMenu(root.loadChildren().getFirst(), opened::add);
            assertEquals("Test", item(entryMenu, "Copy reference").getActionCommand());
            assertEquals(archive + "!/Test.class", item(entryMenu, "Copy path").getActionCommand());
            assertEquals(0, view.createContextMenu(null, opened::add).getComponentCount());
        });
    }

    private static JMenuItem item(JPopupMenu menu, String label) {
        for (var component : menu.getComponents()) {
            if (component instanceof JMenuItem item && label.equals(item.getText())) return item;
        }
        throw new AssertionError("Missing " + label);
    }
}
