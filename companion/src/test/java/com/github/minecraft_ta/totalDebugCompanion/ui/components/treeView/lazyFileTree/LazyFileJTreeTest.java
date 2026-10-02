package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;

import com.github.minecraft_ta.totaldebug.storage.AtomicFiles;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;
import javax.swing.event.TreeModelEvent;
import javax.swing.event.TreeModelListener;
import javax.swing.tree.TreePath;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

class LazyFileJTreeTest {

    @Test void newEmptyFolderIsALeafFromItsFirstModelEvent(@TempDir Path directory) throws Exception {
        Path scripts = Files.createDirectory(directory.resolve("scripts"));
        Files.writeString(scripts.resolve("Existing.tdscript"), "");
        var tree = new LazyFileJTree();
        SwingUtilities.invokeAndWait(() -> tree.setRootNodes(tree.getItemFactory().createFileSystemDirectoryItem(scripts)));
        try {
            assertTrue(tree.revealItemPath("scripts", List.of("Existing.tdscript"), () -> true).get(3, TimeUnit.SECONDS));
            var observations = new ArrayList<Boolean>();
            SwingUtilities.invokeAndWait(() -> tree.getModel().addTreeModelListener(new TreeModelListener() {
                private void observe() {
                    var nodes = ((LazyTreeNode) tree.getModel().getRoot()).depthFirstEnumeration();
                    while (nodes.hasMoreElements()) {
                        if (nodes.nextElement() instanceof LazyTreeNode node && node.getUserObject().getName().equals("New"))
                            observations.add(node.isLeaf());
                    }
                }
                public void treeNodesChanged(TreeModelEvent event) { observe(); }
                public void treeNodesInserted(TreeModelEvent event) { observe(); }
                public void treeNodesRemoved(TreeModelEvent event) { observe(); }
                public void treeStructureChanged(TreeModelEvent event) { observe(); }
            }));
            Path folder = Files.createDirectory(scripts.resolve("New"));
            tree.refreshDirectory(scripts).get(3, TimeUnit.SECONDS);
            SwingUtilities.invokeAndWait(() -> {
                assertFalse(observations.isEmpty());
                assertTrue(observations.stream().allMatch(Boolean::booleanValue), "Empty folder briefly advertised children: " + observations);
            });
            Files.writeString(folder.resolve("First.tdscript"), "");
            tree.refreshDirectory(folder).get(3, TimeUnit.SECONDS);
            awaitOnEdt(() -> {
                var root = (LazyTreeNode) ((LazyTreeNode) tree.getModel().getRoot()).getChildAt(0);
                var child = (LazyTreeNode) root.getChildAt(0);
                return child.getChildCount() == 1 && child.getChildAt(0) instanceof LazyTreeNode;
            });
            assertTrue(tree.revealItemPath("scripts", List.of("New", "First.tdscript"), () -> true).get(3, TimeUnit.SECONDS));
        } finally { SwingUtilities.invokeAndWait(tree::setRootNodes); }
    }

    @Test
    void unchangedLargeDirectoriesDoNotEmitRowUpdates() throws Exception {
        var eventCounts = new ArrayList<Integer>();
        for (int size : new int[]{1000, 5000, 10000}) {
            var items = new ArrayList<>(IntStream.range(0, size)
                    .mapToObj(index -> new TreeItem("file" + index)).toList());
            LazyFileJTree tree = new LazyFileJTree();
            DirectoryTreeItem root = directory("scripts", items);
            SwingUtilities.invokeAndWait(() -> { tree.setRowHeight(24); tree.setRootNodes(root); });
            assertTrue(tree.revealItemPath("scripts", List.of("file0"), () -> true).get(10, TimeUnit.SECONDS));
            var events = new AtomicInteger();
            SwingUtilities.invokeAndWait(() -> tree.getModel().addTreeModelListener(new TreeModelListener() {
                @Override public void treeNodesChanged(TreeModelEvent e) { events.incrementAndGet(); }
                @Override public void treeNodesInserted(TreeModelEvent e) { events.incrementAndGet(); }
                @Override public void treeNodesRemoved(TreeModelEvent e) { events.incrementAndGet(); }
                @Override public void treeStructureChanged(TreeModelEvent e) { events.incrementAndGet(); }
            }));
            long started = System.nanoTime();
            tree.loadItemsForTopLevelItem(root);
            assertTrue(tree.revealItemPath("scripts", List.of("file0"), () -> true).get(10, TimeUnit.SECONDS));
            System.out.printf("Unchanged refresh: %,d entries, %.1f ms, %d model events%n",
                    size, (System.nanoTime() - started) / 1_000_000d, events.get());
            eventCounts.add(events.get());
            events.set(0);
            items.add(new TreeItem("added"));
            started = System.nanoTime();
            tree.loadItemsForTopLevelItem(root);
            assertTrue(tree.revealItemPath("scripts", List.of("file0"), () -> true).get(10, TimeUnit.SECONDS));
            System.out.printf("Single insertion: %,d entries, %.1f ms, %d model events%n",
                    size, (System.nanoTime() - started) / 1_000_000d, events.get());
            assertEquals(1, events.get(), "Adding one file should update only that row");
            events.set(0);
            items.removeIf(item -> !item.getName().equals("file0"));
            started = System.nanoTime();
            tree.loadItemsForTopLevelItem(root);
            assertTrue(tree.revealItemPath("scripts", List.of("file0"), () -> true).get(10, TimeUnit.SECONDS));
            System.out.printf("Bulk removal: %,d entries, %.1f ms, %d model events%n",
                    size, (System.nanoTime() - started) / 1_000_000d, events.get());
            assertEquals(1, events.get(), "Removing many files should publish one removal event");
        }
        assertTrue(eventCounts.stream().allMatch(count -> count == 0), "Unexpected row updates: " + eventCounts);
    }

    @Test
    void directoryRefreshKeepsExpandedChildrenAndSelection() throws Exception {
        LazyFileJTree tree = new LazyFileJTree();
        var contents = new AtomicReference<List<TreeItem>>(List.of(
                directory("nested", List.of(new TreeItem("selected.tdscript")))));
        DirectoryTreeItem scripts = new DirectoryTreeItem("scripts") {
            @Override public List<TreeItem> loadChildren() { return contents.get(); }
        };
        SwingUtilities.invokeAndWait(() -> tree.setRootNodes(scripts));
        assertTrue(tree.revealItemPath("scripts", List.of("nested", "selected.tdscript"), () -> true).get(3, TimeUnit.SECONDS));
        var selection = tree.getSelectionPath();
        contents.set(List.of(directory("another", List.of()),
                directory("nested", List.of(new TreeItem("selected.tdscript"))), new TreeItem("new.tdscript")));
        tree.loadItemsForTopLevelItem(scripts);
        awaitOnEdt(() -> ((LazyTreeNode) tree.getModel().getRoot()).getChildAt(0).getChildCount() == 3);
        SwingUtilities.invokeAndWait(() -> {
            assertTrue(tree.isExpanded(selection.getParentPath()), "Expanded folder collapsed during refresh");
            assertEquals(selection, tree.getSelectionPath(), "Refresh moved the selection to a different item");
        });
    }

    @Test
    void refreshPreservesASelectedPlaceholderInAnotherLoadingFolder() throws Exception {
        LazyFileJTree tree = new LazyFileJTree();
        var release = new CountDownLatch(1);
        DirectoryTreeItem slow = new DirectoryTreeItem("slow") {
            @Override public List<TreeItem> loadChildren() {
                try { release.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
                return List.of(new TreeItem("loaded"));
            }
        };
        var items = new ArrayList<TreeItem>(List.of(new TreeItem("original")));
        DirectoryTreeItem other = directory("other", items);
        SwingUtilities.invokeAndWait(() -> tree.setRootNodes(slow, other));
        assertTrue(tree.revealItemPath("other", List.of("original"), () -> true).get(3, TimeUnit.SECONDS));
        var root = (LazyTreeNode) tree.getModel().getRoot();
        var slowNode = (LazyTreeNode) root.getChildAt(0);
        var placeholder = new TreePath(slowNode.getPath()).pathByAddingChild(slowNode.getChildAt(0));
        try {
            SwingUtilities.invokeAndWait(() -> {
                tree.expandPath(placeholder.getParentPath());
                tree.setSelectionPath(placeholder);
            });
            items.add(new TreeItem("added"));
            tree.loadItemsForTopLevelItem(other);
            awaitOnEdt(() -> root.getChildAt(1).getChildCount() == 2);
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
        var root = tree.getItemFactory().createFileSystemDirectoryItem(directory);
        SwingUtilities.invokeAndWait(() -> tree.setRootNodes(root));
        assertTrue(tree.revealItemPath(root.getName(), List.of("nested", "Old.tdscript"), () -> true).get(3, TimeUnit.SECONDS));
        var folder = tree.getSelectionPath().getParentPath();
        Files.writeString(directory.resolve("nested/New.tdscript"), "");
        SwingUtilities.invokeAndWait(() -> {
            var again = tree.getItemFactory().createFileSystemDirectoryItem(directory);
            tree.updateRoots(List.of(again), Map.of(again.getName(), LazyFileJTree.Reload.BELOW));
        });
        awaitOnEdt(() -> ((LazyTreeNode) folder.getLastPathComponent()).getChildCount() == 2);
        SwingUtilities.invokeAndWait(() -> assertTrue(tree.isExpanded(folder)));
    }

    private static void awaitOnEdt(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        var ready = new AtomicBoolean();
        do {
            SwingUtilities.invokeAndWait(() -> ready.set(condition.getAsBoolean()));
            if (ready.get()) return;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        fail("Tree refresh did not finish");
    }

    @Test
    void refreshRequestedDuringAnActiveLoadIsNotLost() throws Exception {
        LazyFileJTree tree = new LazyFileJTree();
        var contents = new AtomicReference<List<TreeItem>>(List.of(new TreeItem("original")));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var loads = new AtomicInteger();
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
        assertTrue(tree.revealItemPath("scripts", List.of("original"), () -> true).get(3, TimeUnit.SECONDS));
        tree.loadItemsForTopLevelItem(scripts);
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        contents.set(List.of(new TreeItem("original"), new TreeItem("latest")));
        SwingUtilities.invokeAndWait(() -> tree.loadItemsForTopLevelItem(scripts));
        release.countDown();
        awaitOnEdt(() -> ((LazyTreeNode) tree.getModel().getRoot()).getChildAt(0).getChildCount() == 2);
    }

    @Test
    void deletingAFileKeepsExpandedSiblingFolders() throws Exception {
        LazyFileJTree tree = new LazyFileJTree();
        var contents = new AtomicReference<List<TreeItem>>();
        DirectoryTreeItem nested = directory("nested", List.of(new TreeItem("kept")));
        TreeItem removed = new TreeItem("remove.tdscript");
        contents.set(List.of(nested, removed));
        DirectoryTreeItem scripts = new DirectoryTreeItem("scripts") {
            @Override public List<TreeItem> loadChildren() { return contents.get(); }
        };
        SwingUtilities.invokeAndWait(() -> tree.setRootNodes(scripts));
        assertTrue(tree.revealItemPath("scripts", List.of("nested", "kept"), () -> true).get(3, TimeUnit.SECONDS));
        var expanded = tree.getSelectionPath().getParentPath();
        assertTrue(tree.revealItemPath("scripts", List.of("remove.tdscript"), () -> true).get(3, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> { contents.set(List.of(nested)); tree.loadItemsForTopLevelItem(scripts); });
        awaitOnEdt(() -> ((LazyTreeNode) tree.getModel().getRoot()).getChildAt(0).getChildCount() == 1);
        SwingUtilities.invokeAndWait(() -> assertTrue(tree.isExpanded(expanded)));
    }

    @Test
    void replacingRuntimeRootsRefreshesLoadedContentWithoutCollapsingIt() throws Exception {
        LazyFileJTree tree = new LazyFileJTree();
        SwingUtilities.invokeAndWait(() -> tree.setRootNodes(directory("runtime", List.of(
                directory("module", List.of(new TreeItem("Old.class")))))));
        assertTrue(tree.revealItemPath("runtime", List.of("module", "Old.class"), () -> true).get(3, TimeUnit.SECONDS));
        var modulePath = tree.getSelectionPath().getParentPath();
        SwingUtilities.invokeAndWait(() -> tree.updateRoots(List.of(directory("runtime", List.of(
                directory("module", List.of(new TreeItem("New.class")))))), Map.of("runtime", LazyFileJTree.Reload.BELOW)));
        awaitOnEdt(() -> ((LazyTreeNode) ((LazyTreeNode) modulePath.getLastPathComponent()).getChildAt(0))
                .getUserObject().getName().equals("New.class"));
        SwingUtilities.invokeAndWait(() -> {
            assertTrue(tree.isExpanded(modulePath));
            assertNull(tree.getSelectionPath(), "Removed file stayed selected");
        });
    }

    @Test
    void refreshingAfterAnAtomicSaveKeepsTheSelectedFileAndExpandedFolder(@TempDir Path directory) throws Exception {
        Files.createDirectories(directory.resolve("nested"));
        Files.writeString(directory.resolve("nested/Selected.tdscript"), "return 1;");
        Path saved = directory.resolve("Saved.tdscript");
        Files.writeString(saved, "return 1;");
        LazyFileJTree tree = new LazyFileJTree();
        var scans = new AtomicInteger();
        var nestedScans = new AtomicInteger();
        tree.setItemFactory(new FileTreeItemFactory() {
            @Override public FileSystemDirectoryItem createFileSystemDirectoryItem(Path path) {
                return new FileSystemDirectoryItem(tree, path) {
                    @Override public List<TreeItem> loadChildren() {
                        nestedScans.incrementAndGet();
                        return super.loadChildren();
                    }
                };
            }
        });
        var root = new FileSystemDirectoryItem(tree, directory) {
            @Override public List<TreeItem> loadChildren() {
                scans.incrementAndGet();
                return super.loadChildren();
            }
        };
        try {
            SwingUtilities.invokeAndWait(() -> tree.setRootNodes(root));
            assertTrue(tree.revealItemPath(root.getName(), List.of("nested", "Selected.tdscript"), () -> true).get(3, TimeUnit.SECONDS));
            var selected = tree.getSelectionPath();
            AtomicFiles.writeString(saved, "return 2;");
            tree.refreshDirectory(directory).get(3, TimeUnit.SECONDS);
            awaitOnEdt(() -> scans.get() > 1);
            assertTrue(tree.revealItemPath(root.getName(), List.of("nested", "Selected.tdscript"), () -> true).get(3, TimeUnit.SECONDS));
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

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void backgroundFailuresAreReportedOnlyForTheCurrentRuntime(boolean retired) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var errors = new ByteArrayOutputStream();
        var previousError = System.err;
        LazyFileJTree tree = new LazyFileJTree();
        DirectoryTreeItem oldRuntime = new DirectoryTreeItem("decompiled-files") {
            @Override public List<TreeItem> loadChildren() {
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { throw new AssertionError(failure); }
                throw new UncheckedIOException(new IOException("Decompiled cache belongs to a different runtime"));
            }
        };
        try (var capture = new PrintStream(errors, true, StandardCharsets.UTF_8)) {
            System.setErr(capture);
            SwingUtilities.invokeAndWait(() -> tree.setRootNodes(oldRuntime));
            var reveal = tree.revealItemPath("decompiled-files", List.of("Old.java"), () -> true);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            if (retired) SwingUtilities.invokeAndWait(() -> tree.setRootNodes(emptyDirectory("current-runtime")));
            release.countDown();
            assertThrows(ExecutionException.class, () -> reveal.get(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> { });
            SwingUtilities.invokeAndWait(() -> { });
            assertEquals(!retired, errors.toString(StandardCharsets.UTF_8).contains("Decompiled cache belongs to a different runtime"),
                    "A retired runtime's failed background load is not a current tree error");
        } finally {
            release.countDown();
            System.setErr(previousError);
            SwingUtilities.invokeAndWait(() -> tree.setRootNodes());
        }
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
        FileSystemDirectoryItem root = tree.getItemFactory().createFileSystemDirectoryItem(directory);

        assertTrue(assertDoesNotThrow(root::loadChildren).isEmpty());
    }

    @Test
    void revealsFilesInsideCollapsedDirectoriesWithoutTreatingFilesAsContainers() throws Exception {
        LazyFileJTree tree = new LazyFileJTree();
        SwingUtilities.invokeAndWait(() -> tree.setRootNodes(directory("scripts", List.of(
                directory("nested", List.of(new TreeItem("example.java")))))));
        assertTrue(tree.revealItemPath("scripts", List.of("nested", "example.java"), () -> true).get(3, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> {
            var node = (LazyTreeNode) tree.getSelectionPath().getLastPathComponent();
            assertEquals("example.java", node.getUserObject().getName());
            assertTrue(tree.isVisible(tree.getSelectionPath()));
        });
        assertFalse(tree.revealItemPath(
                "scripts", List.of("nested", "example.java", "missing"), () -> true).get(3, TimeUnit.SECONDS));
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
        , () -> true).get(3, TimeUnit.SECONDS);

        assertTrue(revealed);
        assertEquals(1, tree.getSelectionCount());
        var selection = (LazyTreeNode) tree.getSelectionPath().getLastPathComponent();
        assertEquals("example", selection.getUserObject().getName());
        assertEquals("second.jar", selection.getParent().getParent().getUserObject().getName());
        assertTrue(tree.isExpanded(tree.getSelectionPath()));
    }

    @Test void aRefreshUpdatesACollapsedButLoadedNestedFolder(@TempDir Path directory) throws Exception {
        Path scripts = Files.createDirectories(directory.resolve("scripts"));
        Path nested = Files.createDirectories(scripts.resolve("nested"));
        Files.writeString(nested.resolve("First.tdscript"), "");
        var tree = new LazyFileJTree();
        SwingUtilities.invokeAndWait(() -> tree.setRootNodes(tree.getItemFactory().createFileSystemDirectoryItem(scripts)));
        try {
            assertTrue(tree.revealItemPath("scripts", List.of("nested", "First.tdscript"), () -> true).get(3, TimeUnit.SECONDS));
            TreePath folder = tree.getSelectionPath().getParentPath();
            SwingUtilities.invokeAndWait(() -> tree.collapsePath(folder));
            Files.writeString(nested.resolve("Second.tdscript"), "");
            tree.refreshDirectory(nested).get(3, TimeUnit.SECONDS);
            awaitOnEdt(() -> ((LazyTreeNode) folder.getLastPathComponent()).getChildCount() == 2);
            SwingUtilities.invokeAndWait(() -> assertFalse(tree.isExpanded(folder)));
            Files.delete(nested.resolve("First.tdscript"));
            tree.refreshDirectory(nested).get(3, TimeUnit.SECONDS);
            awaitOnEdt(() -> ((LazyTreeNode) folder.getLastPathComponent()).getChildCount() == 1);
        } finally { SwingUtilities.invokeAndWait(tree::setRootNodes); }
    }

    @Test void failedEnumerationDisposesAlreadyConstructedChildren(@TempDir Path directory) throws Exception {
        Files.createDirectories(directory.resolve("a")); Files.createDirectories(directory.resolve("b"));
        var tree = new LazyFileJTree();
        var count = new AtomicInteger(); var disposed = new AtomicInteger();
        tree.setItemFactory(new FileTreeItemFactory() {
            @Override public FileSystemDirectoryItem createFileSystemDirectoryItem(Path path) {
                if (count.incrementAndGet() == 2) throw new IllegalStateException("Simulated creation failure");
                return new FileSystemDirectoryItem(tree, path) {
                    @Override public void dispose() { super.dispose(); disposed.incrementAndGet(); }
                };
            }
        });
        var root = new FileSystemDirectoryItem(tree, directory);
        try {
            assertThrows(IllegalStateException.class, root::loadChildren);
            assertEquals(1, disposed.get());
        } finally { root.dispose(); }
    }

    @Test void failedRefreshDisposesCachedDescendants() throws Exception {
        var tree = new LazyFileJTree(); var disposed = new AtomicInteger(); var fail = new AtomicBoolean();
        var child = new TreeItem("child") { @Override public void dispose() { disposed.incrementAndGet(); } };
        var root = new DirectoryTreeItem("scripts") {
            @Override public List<TreeItem> loadChildren() {
                if (fail.get()) throw new IllegalStateException("Simulated load failure");
                return List.of(child);
            }
        };
        SwingUtilities.invokeAndWait(() -> tree.setRootNodes(root));
        tree.revealItemPath("scripts", List.of("child"), () -> true).get(3, TimeUnit.SECONDS);
        fail.set(true);
        SwingUtilities.invokeAndWait(() -> tree.loadItemsForTopLevelItem(root));
        awaitOnEdt(() -> disposed.get() == 1);
        SwingUtilities.invokeAndWait(tree::setRootNodes);
    }

    private static DirectoryTreeItem emptyDirectory(String name) {
        return new DirectoryTreeItem(name) {
            @Override
            public List<TreeItem> loadChildren() {
                return List.of();
            }
        };
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aRevealKeepsItsSelectionOverARestorationFinishingLater(boolean revealFirst, @TempDir Path directory) throws Exception {
        LazyFileJTree tree = new LazyFileJTree();
        Files.createDirectories(directory.resolve("a/b"));
        Files.writeString(directory.resolve("a/b/x.tdscript"), "");
        var rootGate = new AtomicReference<>(new CountDownLatch(0));
        var folderGate = new AtomicReference<>(new CountDownLatch(0));
        DirectoryTreeItem scripts = new DirectoryTreeItem("scripts") {
            @Override public List<TreeItem> loadChildren() {
                await(rootGate.get());
                return List.of(new FileSystemDirectoryItem(tree, directory.resolve("a")) {
                    @Override public List<TreeItem> loadChildren() {
                        await(folderGate.get());
                        return super.loadChildren();
                    }
                });
            }
        };
        var revealGate = new CountDownLatch(1);
        DirectoryTreeItem other = directory("other", List.of(new DirectoryTreeItem("slow") {
            @Override public List<TreeItem> loadChildren() {
                await(revealGate);
                return List.of(new TreeItem("row"));
            }
        }));
        SwingUtilities.invokeAndWait(() -> tree.setRootNodes(scripts, other));
        // The single-folder chain a/b shows as one row; the script in it is selected.
        assertTrue(tree.revealItemPath("scripts", List.of("a", "b", "x.tdscript"), () -> true).get(3, TimeUnit.SECONDS));

        // Another folder beside b ends the chain. Just before or while Scripts loads again, the user is taken to a row
        // that loads itself; it is selected after the rows regrouped and before their restoration, which waits for a.
        Files.createDirectories(directory.resolve("a/c"));
        var rootHeld = new CountDownLatch(1);
        var folderHeld = new CountDownLatch(1);
        rootGate.set(rootHeld);
        folderGate.set(folderHeld);
        CompletableFuture<Boolean> revealed;
        try {
            if (!revealFirst) tree.loadItemsForTopLevelItem(scripts);
            SwingUtilities.invokeAndWait(() -> { });
            revealed = tree.revealItemPath("other", List.of("slow", "row"), () -> true);
            SwingUtilities.invokeAndWait(() -> { });
            if (revealFirst) tree.loadItemsForTopLevelItem(scripts);
            SwingUtilities.invokeAndWait(() -> { });
            rootHeld.countDown();
            var root = (LazyTreeNode) tree.getModel().getRoot();
            awaitOnEdt(() -> ((LazyTreeNode) root.getChildAt(0)).getChildAt(0) instanceof LazyTreeNode a
                    && DirectoryChain.segments(a.getUserObject()).size() == 1);
            revealGate.countDown();
            assertTrue(revealed.get(3, TimeUnit.SECONDS));
        } finally {
            rootHeld.countDown();
            revealGate.countDown();
            folderHeld.countDown();
        }
        awaitOnEdt(() -> ((LazyTreeNode) ((LazyTreeNode) tree.getModel().getRoot()).getChildAt(0)).getChildAt(0).getChildCount() == 2);
        Thread.sleep(200);
        SwingUtilities.invokeAndWait(() -> {
            assertEquals(1, tree.getSelectionCount());
            assertEquals("row", ((LazyTreeNode) tree.getSelectionPath().getLastPathComponent()).getUserObject().getName(),
                    "the restoration finishing later leaves the reveal's selection");
        });
    }

    private static void await(CountDownLatch gate) {
        try { gate.await(5, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { throw new IllegalStateException(interrupted); }
    }

    @Test
    void aRestorationNoLongerWantedSelectsAndExpandsNothing() throws Exception {
        LazyFileJTree tree = new LazyFileJTree();
        var held = new CountDownLatch(1);
        DirectoryTreeItem moved = new DirectoryTreeItem("moved") {
            @Override public List<TreeItem> loadChildren() {
                try { held.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { throw new IllegalStateException(interrupted); }
                return List.of(directory("inner", List.of()), new TreeItem("script.tdscript"));
            }
        };
        DirectoryTreeItem scripts = directory("scripts", List.of(directory("destination", List.of(moved))));
        DirectoryTreeItem other = directory("other", List.of(new TreeItem("row")));
        SwingUtilities.invokeAndWait(() -> tree.setRootNodes(scripts, other));
        assertTrue(tree.revealItemPath("other", List.of("row"), () -> true).get(3, TimeUnit.SECONDS));

        // A script action puts back the rows it moved, while the folder they are in still loads; its project is replaced.
        var wanted = new AtomicBoolean(true);
        CompletableFuture<Void> selected = tree.restoreItemPath("scripts", List.of("destination", "moved", "script.tdscript"), true, wanted::get);
        CompletableFuture<Void> expanded = tree.restoreItemPath("scripts", List.of("destination", "moved", "inner"), false, wanted::get);
        SwingUtilities.invokeAndWait(() -> wanted.set(false));
        held.countDown();
        assertThrows(CancellationException.class, () -> {
            try { selected.get(3, TimeUnit.SECONDS); } catch (ExecutionException failure) { throw failure.getCause(); }
        });
        assertThrows(CancellationException.class, () -> {
            try { expanded.get(3, TimeUnit.SECONDS); } catch (ExecutionException failure) { throw failure.getCause(); }
        });
        SwingUtilities.invokeAndWait(() -> {
            assertEquals("row", ((LazyTreeNode) tree.getSelectionPath().getLastPathComponent()).getUserObject().getName(),
                    "the selection stays");
            var movedNode = (LazyTreeNode) ((LazyTreeNode) tree.getModel().getRoot()).getChildAt(0).getChildAt(0).getChildAt(0);
            assertEquals(2, movedNode.getChildCount(), "the folder's rows loaded");
            assertFalse(tree.isExpanded(new TreePath(((LazyTreeNode) movedNode.getChildAt(0)).getPath())), "nothing is expanded");
        });
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
