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
    void unchangedLargeDirectoriesDoNotEmitRowUpdates() throws Exception {
        var eventCounts = new java.util.ArrayList<Integer>();
        for (int size : new int[]{1000, 5000, 10000}) {
            var items = new java.util.ArrayList<>(java.util.stream.IntStream.range(0, size)
                    .mapToObj(index -> new TreeItem("file" + index)).toList());
            LazyFileJTree tree = new LazyFileJTree();
            DirectoryTreeItem root = directory("scripts", items);
            SwingUtilities.invokeAndWait(() -> { tree.setRowHeight(24); tree.setRootNodes(root); });
            assertTrue(tree.revealItemPath("scripts", List.of("file0")).get(10, TimeUnit.SECONDS));
            var events = new java.util.concurrent.atomic.AtomicInteger();
            SwingUtilities.invokeAndWait(() -> tree.getModel().addTreeModelListener(new javax.swing.event.TreeModelListener() {
                @Override public void treeNodesChanged(javax.swing.event.TreeModelEvent e) { events.incrementAndGet(); }
                @Override public void treeNodesInserted(javax.swing.event.TreeModelEvent e) { events.incrementAndGet(); }
                @Override public void treeNodesRemoved(javax.swing.event.TreeModelEvent e) { events.incrementAndGet(); }
                @Override public void treeStructureChanged(javax.swing.event.TreeModelEvent e) { events.incrementAndGet(); }
            }));
            long started = System.nanoTime();
            tree.loadItemsForTopLevelItem(root);
            assertTrue(tree.revealItemPath("scripts", List.of("file0")).get(10, TimeUnit.SECONDS));
            System.out.printf("Unchanged refresh: %,d entries, %.1f ms, %d model events%n",
                    size, (System.nanoTime() - started) / 1_000_000d, events.get());
            eventCounts.add(events.get());
            events.set(0);
            items.add(new TreeItem("added"));
            started = System.nanoTime();
            tree.loadItemsForTopLevelItem(root);
            assertTrue(tree.revealItemPath("scripts", List.of("file0")).get(10, TimeUnit.SECONDS));
            System.out.printf("Single insertion: %,d entries, %.1f ms, %d model events%n",
                    size, (System.nanoTime() - started) / 1_000_000d, events.get());
            assertEquals(1, events.get(), "Adding one file should update only that row");
            events.set(0);
            items.removeIf(item -> !item.getName().equals("file0"));
            started = System.nanoTime();
            tree.loadItemsForTopLevelItem(root);
            assertTrue(tree.revealItemPath("scripts", List.of("file0")).get(10, TimeUnit.SECONDS));
            System.out.printf("Bulk removal: %,d entries, %.1f ms, %d model events%n",
                    size, (System.nanoTime() - started) / 1_000_000d, events.get());
            assertEquals(1, events.get(), "Removing many files should publish one removal event");
        }
        assertTrue(eventCounts.stream().allMatch(count -> count == 0), "Unexpected row updates: " + eventCounts);
    }

    @Test
    void directoryRefreshKeepsExpandedChildrenAndSelection() throws Exception {
        LazyFileJTree tree = new LazyFileJTree();
        var contents = new java.util.concurrent.atomic.AtomicReference<List<TreeItem>>(List.of(
                directory("nested", List.of(new TreeItem("selected.tdscript")))));
        DirectoryTreeItem scripts = new DirectoryTreeItem("scripts") {
            @Override public List<TreeItem> loadChildren() { return contents.get(); }
        };
        SwingUtilities.invokeAndWait(() -> tree.setRootNodes(scripts));
        assertTrue(tree.revealItemPath("scripts", List.of("nested", "selected.tdscript")).get(3, TimeUnit.SECONDS));
        var selection = tree.getSelectionPath();
        contents.set(List.of(directory("another", List.of()),
                directory("nested", List.of(new TreeItem("selected.tdscript"))), new TreeItem("new.tdscript")));
        tree.loadItemsForTopLevelItem(scripts);
        awaitOnEdt(() -> ((LazyTreeNode) ((LazyTreeNode) tree.getModel().getRoot()).getChildAt(0)).getChildCount() == 3);
        SwingUtilities.invokeAndWait(() -> {
            assertTrue(tree.isExpanded(selection.getParentPath()), "Expanded folder collapsed during refresh");
            assertEquals(selection, tree.getSelectionPath(), "Refresh moved the selection to a different item");
        });
    }

    @Test
    void refreshPreservesASelectedPlaceholderInAnotherLoadingFolder() throws Exception {
        LazyFileJTree tree = new LazyFileJTree();
        var release = new java.util.concurrent.CountDownLatch(1);
        DirectoryTreeItem slow = new DirectoryTreeItem("slow") {
            @Override public List<TreeItem> loadChildren() {
                try { release.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
                return List.of(new TreeItem("loaded"));
            }
        };
        var items = new java.util.ArrayList<TreeItem>(List.of(new TreeItem("original")));
        DirectoryTreeItem other = directory("other", items);
        SwingUtilities.invokeAndWait(() -> tree.setRootNodes(slow, other));
        assertTrue(tree.revealItemPath("other", List.of("original")).get(3, TimeUnit.SECONDS));
        var root = (LazyTreeNode) tree.getModel().getRoot();
        var slowNode = (LazyTreeNode) root.getChildAt(0);
        var placeholder = new javax.swing.tree.TreePath(slowNode.getPath()).pathByAddingChild(slowNode.getChildAt(0));
        try {
            SwingUtilities.invokeAndWait(() -> {
                tree.expandPath(placeholder.getParentPath());
                tree.setSelectionPath(placeholder);
            });
            items.add(new TreeItem("added"));
            tree.loadItemsForTopLevelItem(other);
            awaitOnEdt(() -> ((LazyTreeNode) root.getChildAt(1)).getChildCount() == 2);
            SwingUtilities.invokeAndWait(() -> assertEquals(placeholder, tree.getSelectionPath()));
        } finally {
            release.countDown();
        }
    }

    @Test
    void explicitRootRefreshUpdatesAlreadyLoadedFilesystemSubfolders(@TempDir Path directory) throws Exception {
        Files.createDirectories(directory.resolve("nested"));
        Files.writeString(directory.resolve("nested/Old.tdscript"), "");
        LazyFileJTree tree = new LazyFileJTree();
        var root = tree.getItemFactory().createFileSystemDirectoryItem(directory, false);
        SwingUtilities.invokeAndWait(() -> tree.setRootNodes(root));
        assertTrue(tree.revealItemPath(root.getName(), List.of("nested", "Old.tdscript")).get(3, TimeUnit.SECONDS));
        var folder = tree.getSelectionPath().getParentPath();
        Files.writeString(directory.resolve("nested/New.tdscript"), "");
        SwingUtilities.invokeAndWait(() -> tree.refreshRootNodes(
                tree.getItemFactory().createFileSystemDirectoryItem(directory, false)));
        awaitOnEdt(() -> ((LazyTreeNode) folder.getLastPathComponent()).getChildCount() == 2);
        SwingUtilities.invokeAndWait(() -> assertTrue(tree.isExpanded(folder)));
    }

    private static void awaitOnEdt(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        var ready = new AtomicBoolean();
        do {
            SwingUtilities.invokeAndWait(() -> ready.set(condition.getAsBoolean()));
            if (ready.get()) return;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        org.junit.jupiter.api.Assertions.fail("Tree refresh did not finish");
    }

    @Test
    void refreshRequestedDuringAnActiveLoadIsNotLost() throws Exception {
        LazyFileJTree tree = new LazyFileJTree();
        var contents = new java.util.concurrent.atomic.AtomicReference<List<TreeItem>>(List.of(new TreeItem("original")));
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var loads = new java.util.concurrent.atomic.AtomicInteger();
        DirectoryTreeItem scripts = new DirectoryTreeItem("scripts") {
            @Override public List<TreeItem> loadChildren() {
                var snapshot = contents.get();
                if (loads.incrementAndGet() == 2) {
                    entered.countDown();
                    try { assertTrue(release.await(3, TimeUnit.SECONDS)); }
                    catch (InterruptedException e) { throw new RuntimeException(e); }
                }
                return snapshot;
            }
        };
        SwingUtilities.invokeAndWait(() -> tree.setRootNodes(scripts));
        assertTrue(tree.revealItemPath("scripts", List.of("original")).get(3, TimeUnit.SECONDS));
        tree.loadItemsForTopLevelItem(scripts);
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        contents.set(List.of(new TreeItem("original"), new TreeItem("latest")));
        SwingUtilities.invokeAndWait(() -> tree.loadItemsForTopLevelItem(scripts));
        release.countDown();
        awaitOnEdt(() -> ((LazyTreeNode) ((LazyTreeNode) tree.getModel().getRoot()).getChildAt(0)).getChildCount() == 2);
    }

    @Test
    void deletingAFileKeepsExpandedSiblingFolders() throws Exception {
        LazyFileJTree tree = new LazyFileJTree();
        var contents = new java.util.concurrent.atomic.AtomicReference<List<TreeItem>>();
        DirectoryTreeItem nested = directory("nested", List.of(new TreeItem("kept")));
        TreeItem removed = new TreeItem("remove.tdscript") {
            @Override public void delete() { contents.set(List.of(nested)); }
        };
        contents.set(List.of(nested, removed));
        DirectoryTreeItem scripts = new DirectoryTreeItem("scripts") {
            @Override public List<TreeItem> loadChildren() { return contents.get(); }
        };
        SwingUtilities.invokeAndWait(() -> tree.setRootNodes(scripts));
        assertTrue(tree.revealItemPath("scripts", List.of("nested", "kept")).get(3, TimeUnit.SECONDS));
        var expanded = tree.getSelectionPath().getParentPath();
        assertTrue(tree.revealItemPath("scripts", List.of("remove.tdscript")).get(3, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(tree::deleteSelectedItems);
        awaitOnEdt(() -> ((LazyTreeNode) ((LazyTreeNode) tree.getModel().getRoot()).getChildAt(0)).getChildCount() == 1);
        SwingUtilities.invokeAndWait(() -> assertTrue(tree.isExpanded(expanded)));
    }

    @Test
    void replacingRuntimeRootsRefreshesLoadedContentWithoutCollapsingIt() throws Exception {
        LazyFileJTree tree = new LazyFileJTree();
        SwingUtilities.invokeAndWait(() -> tree.setRootNodes(directory("runtime", List.of(
                directory("module", List.of(new TreeItem("Old.class")))))));
        assertTrue(tree.revealItemPath("runtime", List.of("module", "Old.class")).get(3, TimeUnit.SECONDS));
        var modulePath = tree.getSelectionPath().getParentPath();
        SwingUtilities.invokeAndWait(() -> tree.refreshRootNodes(directory("runtime", List.of(
                directory("module", List.of(new TreeItem("New.class")))))));
        awaitOnEdt(() -> ((LazyTreeNode) ((LazyTreeNode) modulePath.getLastPathComponent()).getChildAt(0))
                .getUserObject().getName().equals("New.class"));
        SwingUtilities.invokeAndWait(() -> {
            assertTrue(tree.isExpanded(modulePath));
            org.junit.jupiter.api.Assertions.assertNull(tree.getSelectionPath(), "Removed file stayed selected");
        });
    }

    @Test
    void atomicSaveWatcherKeepsTheSelectedFileAndExpandedFolder(@TempDir Path directory) throws Exception {
        Files.createDirectories(directory.resolve("nested"));
        Files.writeString(directory.resolve("nested/Selected.tdscript"), "return 1;");
        Path saved = directory.resolve("Saved.tdscript");
        Files.writeString(saved, "return 1;");
        LazyFileJTree tree = new LazyFileJTree();
        var scans = new java.util.concurrent.atomic.AtomicInteger();
        var nestedScans = new java.util.concurrent.atomic.AtomicInteger();
        tree.setItemFactory(new FileTreeItemFactory() {
            @Override public FileSystemDirectoryItem createFileSystemDirectoryItem(Path path, boolean watch) {
                return new FileSystemDirectoryItem(tree, path, watch) {
                    @Override public List<TreeItem> loadChildren() {
                        nestedScans.incrementAndGet();
                        return super.loadChildren();
                    }
                };
            }
        });
        var root = new FileSystemDirectoryItem(tree, directory, true) {
            @Override public List<TreeItem> loadChildren() {
                scans.incrementAndGet();
                return super.loadChildren();
            }
        };
        try {
            SwingUtilities.invokeAndWait(() -> tree.setRootNodes(root));
            assertTrue(tree.revealItemPath(root.getName(), List.of("nested", "Selected.tdscript")).get(3, TimeUnit.SECONDS));
            var selected = tree.getSelectionPath();
            com.github.minecraft_ta.totaldebug.storage.AtomicFiles.writeString(saved, "return 2;");
            awaitOnEdt(() -> scans.get() > 1);
            assertTrue(tree.revealItemPath(root.getName(), List.of("nested", "Selected.tdscript")).get(3, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(tree.isExpanded(selected.getParentPath()));
                assertEquals(selected, tree.getSelectionPath());
                assertEquals(1, nestedScans.get(), "Saving a file rescanned an unrelated nested folder");
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> tree.setRootNodes());
        }
    }

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
    void honorsExplicitPriorityBeforeAlphabeticalOrdering() {
        DirectoryTreeItem javaRuntime = emptyDirectory("Java Runtime");
        javaRuntime.setSortPriority(30);
        DirectoryTreeItem libraries = emptyDirectory("Libraries");
        libraries.setSortPriority(20);

        assertTrue(LazyFileJTree.compareTreeItems(libraries, javaRuntime) < 0);
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
    void revealsFilesInsideCollapsedDirectoriesWithoutTreatingFilesAsContainers() throws Exception {
        LazyFileJTree tree = new LazyFileJTree();
        SwingUtilities.invokeAndWait(() -> tree.setRootNodes(directory("scripts", List.of(
                directory("nested", List.of(new TreeItem("example.java")))))));
        assertTrue(tree.revealItemPath("scripts", List.of("nested", "example.java")).get(3, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> {
            var node = (LazyTreeNode) tree.getSelectionPath().getLastPathComponent();
            assertEquals("example.java", node.getUserObject().getName());
            assertTrue(tree.isVisible(tree.getSelectionPath()));
        });
        org.junit.jupiter.api.Assertions.assertFalse(tree.revealItemPath(
                "scripts", List.of("nested", "example.java", "missing")).get(3, TimeUnit.SECONDS));
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

        boolean revealed = tree.revealItemPath(
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
