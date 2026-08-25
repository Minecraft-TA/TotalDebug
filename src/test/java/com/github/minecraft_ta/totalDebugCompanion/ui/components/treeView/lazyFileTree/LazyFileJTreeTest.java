package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LazyFileJTreeTest {

    @Test
    void sortsDirectoriesFirstAndNamesWithoutCaseSurprises() {
        TreeItem file = new TreeItem("zeta.txt");
        DirectoryTreeItem directory = new DirectoryTreeItem("Zulu") {
            @Override
            public List<TreeItem> loadChildren() {
                return List.of();
            }
        };

        assertTrue(LazyFileJTree.compareTreeItems(directory, file) < 0);
        assertTrue(LazyFileJTree.compareTreeItems(new TreeItem("Alpha.txt"), new TreeItem("beta.txt")) < 0);
    }

    @Test
    void ignoresRefreshFromRetiredTopLevelItem() {
        LazyFileJTree tree = new LazyFileJTree();
        DirectoryTreeItem retiredRoot = emptyDirectory("retired");
        tree.setRootNodes(retiredRoot);
        tree.setRootNodes(emptyDirectory("current"));

        assertDoesNotThrow(() -> tree.loadItemsForTopLevelItem(retiredRoot));
    }

    @Test
    void disposesRetiredTopLevelItems() {
        LazyFileJTree tree = new LazyFileJTree();
        AtomicBoolean disposed = new AtomicBoolean();
        DirectoryTreeItem retiredRoot = new DirectoryTreeItem("retired") {
            @Override
            public List<TreeItem> loadChildren() {
                return List.of();
            }

            @Override
            public void dispose() {
                disposed.set(true);
            }
        };
        tree.setRootNodes(retiredRoot);

        tree.setRootNodes(emptyDirectory("current"));

        assertTrue(disposed.get());
    }

    @Test
    void ignoresDirectoryEntryDeletedWhileChildrenAreLoaded(@TempDir Path directory) throws IOException {
        LazyFileJTree tree = new LazyFileJTree();
        tree.setItemFactory(new FileTreeItemFactory() {
            @Override
            public FileSystemFileItem createFileSystemFileItem(Path path) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new RuntimeException(exception);
                }
                return super.createFileSystemFileItem(path);
            }
        });
        Files.createFile(directory.resolve(".decompiled-staged.tmp"));
        FileSystemDirectoryItem root = tree.getItemFactory().createFileSystemDirectoryItem(directory, false);

        assertTrue(assertDoesNotThrow(root::loadChildren).isEmpty());
    }

    @Test
    void revealsARequestedPackageInItsOwningContainer() throws Exception {
        LazyFileJTree tree = new LazyFileJTree();
        DirectoryTreeItem mods = directory("mods", List.of(
                directory("first.jar", List.of(directory("com", List.of(directory("example", List.of()))))),
                directory("second.jar", List.of(directory("com", List.of(
                        directory("example", List.of(new TreeItem("Sample.class")))
                )))),
                directory("other.jar", List.of(directory("org", List.of())))
        ));
        SwingUtilities.invokeAndWait(() -> tree.setRootNodes(mods));

        boolean revealed = tree.revealDirectoryPath(
                "mods",
                "second.jar",
                List.of("com", "example")
        ).get(3, TimeUnit.SECONDS);

        assertTrue(revealed);
        assertEquals(1, tree.getSelectionCount());
        var selection = (LazyTreeNode) tree.getSelectionPath().getLastPathComponent();
        assertEquals("example", selection.getUserObject().getName());
        assertEquals("second.jar", ((LazyTreeNode) selection.getParent().getParent()).getUserObject().getName());
        assertTrue(tree.isExpanded(tree.getSelectionPath()));
    }

    private static DirectoryTreeItem emptyDirectory(String name) {
        return new DirectoryTreeItem(name) {
            @Override
            public List<TreeItem> loadChildren() {
                return List.of();
            }
        };
    }

    private static DirectoryTreeItem directory(String name, List<TreeItem> children) {
        return new DirectoryTreeItem(name) {
            @Override
            public List<TreeItem> loadChildren() {
                return children;
            }
        };
    }
}
