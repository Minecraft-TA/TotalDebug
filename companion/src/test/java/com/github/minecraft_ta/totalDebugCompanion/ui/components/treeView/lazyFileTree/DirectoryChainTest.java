package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree;

import com.github.minecraft_ta.totalDebugCompanion.testui.UiTest;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.CompanionTheme;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager;
import com.github.minecraft_ta.totalDebugCompanion.util.FileUtils;
import com.github.minecraft_ta.totalDebugCompanion.ui.ContextMenus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.SwingUtilities;
import javax.swing.KeyStroke;
import javax.swing.JPopupMenu;
import javax.swing.tree.TreePath;
import java.awt.Point;
import java.awt.Cursor;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

@UiTest
class DirectoryChainTest {
    @TempDir Path directory;
    private final List<LazyFileJTree> trees = new ArrayList<>();

    @AfterEach void close() throws Exception { edt(() -> { trees.forEach(LazyFileJTree::setRootNodes); return null; }); }

    private LazyFileJTree tree(boolean watch) throws Exception {
        Path root = Files.createDirectories(directory.resolve("scripts"));
        return edt(() -> {
            ThemeManager.installTheme(CompanionTheme.ISLANDS_DARK);
            var tree = new LazyFileJTree();
            tree.setRowHeight(24);
            tree.setSize(900, 600);
            tree.setRootNodes(tree.getItemFactory().createFileSystemDirectoryItem(root, watch));
            trees.add(tree);
            return tree;
        });
    }

    private Path file() throws Exception {
        Path folder = Files.createDirectories(directory.resolve("scripts/modules/client/items"));
        return Files.writeString(folder.resolve("Test.tdscript"), "return 1;");
    }

    @Test void chainRetainsRealSegmentsAndRevealCanStopInTheMiddle() throws Exception {
        Path file = file();
        var tree = tree(false);
        reveal(tree, "modules", "client", "items", "Test.tdscript");
        var row = edt(() -> (LazyTreeNode) tree.getSelectionPath().getParentPath().getLastPathComponent());
        assertEquals("modules/client/items", row.getUserObject().getPresentation().primary());
        assertEquals(3, DirectoryChain.segments(row.getUserObject()).size());
        reveal(tree, "modules", "client");
        assertSame(row, edt(() -> tree.getSelectionPath().getLastPathComponent()));
        assertEquals(file.getParent().getParent(), edt(() -> selected(tree)));
        edt(() -> {
            tree.collapsePath(tree.getSelectionPath());
            key(tree, "LEFT");
            assertEquals(directory.resolve("scripts/modules"), selected(tree));
            key(tree, "RIGHT"); key(tree, "RIGHT");
            assertEquals(file.getParent(), selected(tree));
            key(tree, "RIGHT");
            assertTrue(tree.isExpanded(tree.getSelectionPath()));
            return null;
        });
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2})
    void splittingAndJoiningAtEveryPositionPreservesTheSelectedFile(int position) throws Exception {
        Path file = file();
        var tree = tree(false);
        reveal(tree, "modules", "client", "items", "Test.tdscript");
        var original = edt(() -> firstFolder(tree));
        Path changed = directory.resolve("scripts").resolve(List.of("modules", "modules/client", "modules/client/items").get(position));
        Path sibling = Files.createDirectory(changed.resolve("Other"));
        tree.refreshDirectory(changed).get(5, TimeUnit.SECONDS);
        assertEquals(file, edt(() -> selected(tree)));
        assertSame(original, edt(() -> firstFolder(tree)), "The head row retains its identity across compaction changes");
        assertTrue(edt(() -> tree.isExpanded(tree.getSelectionPath().getParentPath())));
        Files.delete(sibling);
        tree.refreshDirectory(changed).get(5, TimeUnit.SECONDS);
        assertEquals(file, edt(() -> selected(tree)));
        assertEquals("modules/client/items", edt(() -> firstFolder(tree).getUserObject().getPresentation().primary()));
    }

    @Test void selectedIntermediateDirectorySurvivesRegrouping() throws Exception {
        file();
        var tree = tree(false);
        reveal(tree, "modules", "client");
        Path target = directory.resolve("scripts/modules/client");
        Files.writeString(directory.resolve("scripts/modules/marker"), "");
        tree.refreshDirectory(directory.resolve("scripts/modules")).get(5, TimeUnit.SECONDS);
        assertEquals(target, edt(() -> selected(tree)));
        Files.delete(directory.resolve("scripts/modules/marker"));
        tree.refreshDirectory(directory.resolve("scripts/modules")).get(5, TimeUnit.SECONDS);
        assertEquals(target, edt(() -> selected(tree)));
    }

    @Test void aCollapsedChainStillExpandsAfterItsEndpointChanges() throws Exception {
        file();
        Files.writeString(directory.resolve("scripts/Other.tdscript"), "");
        var tree = tree(false);
        reveal(tree, "Other.tdscript");
        assertFalse(edt(() -> firstFolder(tree).areChildrenLoaded()));
        Path middle = directory.resolve("scripts/modules/client");
        Files.writeString(middle.resolve("marker"), "");
        tree.refreshDirectory(middle).get(5, TimeUnit.SECONDS);
        assertFalse(edt(() -> firstFolder(tree).isLeaf()), "An unloaded regrouped directory must retain its expand affordance");
        reveal(tree, "modules", "client", "items", "Test.tdscript");
    }

    @Test void popupTriggerOnReleaseUsesTheClickedSegmentOfAnUnselectedRow() throws Exception {
        file();
        var tree = tree(false);
        reveal(tree, "modules", "client", "items", "Test.tdscript");
        var menuTarget = new AtomicReference<TreeItem>();
        edt(() -> {
            tree.setDragEnabled(true);
            ContextMenus.installTree(tree, path -> {
                menuTarget.set(((LazyTreeNode) path.getLastPathComponent()).selectedItem());
                return new JPopupMenu(); // Exercise menu resolution without opening a desktop popup.
            });
            var row = tree.getSelectionPath().getParentPath();
            Point point = segmentPoint(tree, row, 1);
            tree.dispatchEvent(new MouseEvent(tree, MouseEvent.MOUSE_PRESSED, 0, 0, point.x, point.y, 1, false, MouseEvent.BUTTON3));
            tree.dispatchEvent(new MouseEvent(tree, MouseEvent.MOUSE_RELEASED, 1, 0, point.x, point.y, 1, true, MouseEvent.BUTTON3));
            assertEquals(directory.resolve("scripts/modules/client"), ((FileSystemDirectoryItem) menuTarget.get()).getPath());
            return null;
        });
    }

    @Test void ctrlClickWithMinorMouseMovementKeepsThePressedSegment() throws Exception {
        file();
        var tree = tree(false);
        reveal(tree, "modules", "client", "items", "Test.tdscript");
        edt(() -> {
            tree.setDragEnabled(true);
            var row = tree.getSelectionPath().getParentPath();
            Point point = segmentPoint(tree, row, 1);
            int modifiers = MouseEvent.CTRL_DOWN_MASK | MouseEvent.BUTTON1_DOWN_MASK;
            tree.dispatchEvent(new MouseEvent(tree, MouseEvent.MOUSE_PRESSED, 0, modifiers, point.x, point.y, 1, false, MouseEvent.BUTTON1));
            tree.dispatchEvent(new MouseEvent(tree, MouseEvent.MOUSE_DRAGGED, 1, modifiers, point.x + 1, point.y, 0, false, MouseEvent.NOBUTTON));
            tree.dispatchEvent(new MouseEvent(tree, MouseEvent.MOUSE_RELEASED, 2, MouseEvent.CTRL_DOWN_MASK, point.x + 1, point.y, 1, false, MouseEvent.BUTTON1));
            assertTrue(tree.isPathSelected(row));
            assertEquals(directory.resolve("scripts/modules/client"), ((FileSystemDirectoryItem) ((LazyTreeNode) row.getLastPathComponent()).selectedItem()).getPath());
            return null;
        });
    }

    @Test void regroupingDiscardsAnObsoleteHoveredSegment() throws Exception {
        file();
        var tree = tree(false);
        reveal(tree, "modules", "client", "items");
        edt(() -> {
            Point point = segmentPoint(tree, tree.getSelectionPath(), 2);
            tree.dispatchEvent(new MouseEvent(tree, MouseEvent.MOUSE_MOVED, 0, 0, point.x, point.y, 0, false));
            return null;
        });
        Path middle = directory.resolve("scripts/modules/client");
        Files.writeString(middle.resolve("marker"), "");
        tree.refreshDirectory(middle).get(5, TimeUnit.SECONDS);
        edt(() -> {
            var image = new BufferedImage(900, 600, BufferedImage.TYPE_INT_ARGB);
            var graphics = image.createGraphics();
            try { assertDoesNotThrow(() -> tree.printAll(graphics)); } finally { graphics.dispose(); }
            return null;
        });
    }

    @Test void aChangeBeforeDiscoveryPublishesIsReprobedEvenIfItsWatcherFoundNoRow() throws Exception {
        Path file = file();
        Path middle = file.getParent().getParent();
        var probed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var callback = new CountDownLatch(1);
        var holdOnce = new AtomicBoolean(true);
        LazyFileJTree tree = edt(() -> {
            var result = new LazyFileJTree() {
                @Override public CompletableFuture<Void> refreshDirectory(Path path) {
                    var refreshed = super.refreshDirectory(path);
                    if (path.equals(middle)) callback.countDown();
                    return refreshed;
                }
            };
            result.setItemFactory(new FileTreeItemFactory() {
                @Override public FileSystemDirectoryItem createFileSystemDirectoryItem(Path path, boolean watch) {
                    return new FileSystemDirectoryItem(result, path, watch) {
                        @Override public DirectoryTreeItem singleDirectoryChild() throws IOException {
                            var child = super.singleDirectoryChild();
                            if (path.equals(middle) && holdOnce.getAndSet(false)) {
                                probed.countDown();
                                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Probe timed out"); }
                                catch (InterruptedException failure) { throw new IOException(failure); }
                            }
                            return child;
                        }
                    };
                }
            });
            result.setRootNodes(result.getItemFactory().createFileSystemDirectoryItem(directory.resolve("scripts"), true));
            trees.add(result);
            return result;
        });
        var revealing = tree.revealItemPath("scripts", List.of("modules", "client", "items", "Test.tdscript"));
        try {
            assertTrue(probed.await(5, TimeUnit.SECONDS));
            Files.writeString(middle.resolve("marker"), "");
            assertTrue(callback.await(5, TimeUnit.SECONDS));
            release.countDown();
            assertTrue(revealing.get(5, TimeUnit.SECONDS));
            reveal(tree, "modules", "client", "marker");
        } finally { release.countDown(); }
    }

    @Test void aDescendantReadFailureDoesNotEraseHealthySiblings() throws Exception {
        var broken = new AtomicBoolean();
        DirectoryTreeItem bad = new DirectoryTreeItem("bad") {
            @Override public List<TreeItem> loadChildren() {
                if (broken.get()) throw new IllegalStateException("Cannot read bad folder");
                return List.of(new TreeItem("old"));
            }
        };
        DirectoryTreeItem good = new DirectoryTreeItem("good") {
            @Override public List<TreeItem> loadChildren() { return List.of(new TreeItem("healthy")); }
        };
        DirectoryTreeItem root = new DirectoryTreeItem("scripts") {
            @Override public List<TreeItem> loadChildren() { return List.of(bad, good); }
        };
        var tree = edt(() -> { var result = new LazyFileJTree(); result.setRootNodes(root); trees.add(result); return result; });
        reveal(tree, "bad", "old");
        reveal(tree, "good", "healthy");
        broken.set(true);
        tree.loadItemsForTopLevelItem(root);
        await(() -> firstFolder(tree).getChildCount() == 1 && firstFolder(tree).getChildAt(0) instanceof LazyTreeNode error
                && error.getUserObject().getName().equals("Cannot read bad folder"));
        reveal(tree, "good", "healthy");
        assertEquals(2, edt(() -> ((LazyTreeNode) tree.getModel().getRoot()).getChildAt(0).getChildCount()));
    }

    @Test void regroupingWithAFailedDescendantStillRestoresOnTheEventThread() throws Exception {
        Path file = file();
        var failReads = new AtomicBoolean();
        var tree = edt(() -> {
            var result = new LazyFileJTree();
            result.setItemFactory(new FileTreeItemFactory() {
                @Override public FileSystemDirectoryItem createFileSystemDirectoryItem(Path path, boolean watch) {
                    return new FileSystemDirectoryItem(result, path, false) {
                        @Override public List<TreeItem> loadChildren() {
                            if (failReads.get() && path.equals(file.getParent())) throw new IllegalStateException("Unreadable items");
                            return super.loadChildren();
                        }
                    };
                }
            });
            result.setRootNodes(result.getItemFactory().createFileSystemDirectoryItem(directory.resolve("scripts"), false));
            trees.add(result);
            return result;
        });
        reveal(tree, "modules", "client", "items", "Test.tdscript");
        failReads.set(true);
        Files.writeString(file.getParent().getParent().resolve("marker"), "");
        tree.refreshDirectory(file.getParent().getParent()).get(5, TimeUnit.SECONDS);
        reveal(tree, "modules", "client", "marker");
        assertEquals(file.getParent().getParent().resolve("marker"), edt(() -> selected(tree)));
    }

    @Test void userNavigationWinsOverADelayedRegroupRestoration() throws Exception {
        Path file = file();
        Path middle = file.getParent().getParent();
        Path other = Files.writeString(directory.resolve("scripts/Other.tdscript"), "");
        var hold = new AtomicBoolean();
        var loading = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var tree = edt(() -> {
            var result = new LazyFileJTree();
            result.setItemFactory(new FileTreeItemFactory() {
                @Override public FileSystemDirectoryItem createFileSystemDirectoryItem(Path path, boolean watch) {
                    return new FileSystemDirectoryItem(result, path, false) {
                        @Override public List<TreeItem> loadChildren() {
                            if (path.equals(middle) && hold.getAndSet(false)) {
                                loading.countDown();
                                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out"); }
                                catch (InterruptedException failure) { throw new IllegalStateException(failure); }
                            }
                            return super.loadChildren();
                        }
                    };
                }
            });
            result.setRootNodes(result.getItemFactory().createFileSystemDirectoryItem(directory.resolve("scripts"), false));
            trees.add(result);
            return result;
        });
        reveal(tree, "modules", "client", "items", "Test.tdscript");
        hold.set(true);
        Files.writeString(middle.resolve("marker"), "");
        var refreshing = tree.refreshDirectory(middle);
        try {
            assertTrue(loading.await(5, TimeUnit.SECONDS));
            reveal(tree, "Other.tdscript");
            release.countDown();
            refreshing.get(5, TimeUnit.SECONDS);
            assertEquals(other, edt(() -> selected(tree)));
        } finally { release.countDown(); }
    }

    @Test void revealThroughANamedContainerConsumesItsCompactedSegments() throws Exception {
        Path file = file();
        var tree = tree(false);
        assertTrue(tree.revealItemPath("scripts", "modules", List.of("client", "items", "Test.tdscript")).get(5, TimeUnit.SECONDS));
        assertEquals(file, edt(() -> selected(tree)));
    }

    private static Point segmentPoint(LazyFileJTree tree, TreePath path, int segment) {
        var node = (LazyTreeNode) path.getLastPathComponent();
        var row = tree.getPathBounds(path);
        var label = new DirectoryChainRenderer();
        label.configure(tree, node, false, tree.getForeground(), tree.getBackground(), -1, -1);
        label.setSize(row.width, row.height);
        var bounds = label.segmentBounds(segment);
        return new Point(row.x + bounds.x + bounds.width / 2, row.y + row.height / 2);
    }

    @Test void emptyTerminalAndHiddenFileDoNotDisappear() throws Exception {
        Path leaf = Files.createDirectories(directory.resolve("scripts/folder.with.dots/space folder/empty"));
        var tree = tree(false);
        reveal(tree, "folder.with.dots", "space folder", "empty");
        assertTrue(edt(() -> ((LazyTreeNode) tree.getSelectionPath().getLastPathComponent()).isLeaf()));
        Files.writeString(leaf.getParent().resolve(".hidden"), "");
        tree.refreshDirectory(leaf.getParent()).get(5, TimeUnit.SECONDS);
        assertEquals(leaf, edt(() -> selected(tree)));
        reveal(tree, "folder.with.dots", "space folder", ".hidden");
        assertEquals(leaf.getParent().resolve(".hidden"), edt(() -> selected(tree)));
    }

    @Test void segmentPaintingAndMouseTargetsAgreeInBothThemes() throws Exception {
        file();
        var tree = tree(false);
        reveal(tree, "modules", "client", "items");
        edt(() -> {
            for (var theme : CompanionTheme.available()) {
                ThemeManager.installTheme(theme);
                SwingUtilities.updateComponentTreeUI(tree);
                TreePath path = tree.getSelectionPath();
                LazyTreeNode node = (LazyTreeNode) path.getLastPathComponent();
                var row = tree.getPathBounds(path);
                var label = new DirectoryChainRenderer();
                label.configure(tree, node, true, tree.getForeground(), tree.getBackground(), -1, -1);
                label.setSize(row.width, row.height);
                for (int i = 0; i < 3; i++) {
                    var bounds = label.segmentBounds(i);
                    Point point = new Point(row.x + bounds.x + bounds.width / 2, row.y + row.height / 2);
                    TreeItem target = DirectoryChain.segments(node.getUserObject()).get(i);
                    assertSame(target, tree.itemAt(point));
                    tree.dispatchEvent(new MouseEvent(tree, MouseEvent.MOUSE_MOVED, 0, 0, point.x, point.y, 0, false));
                    assertEquals(Cursor.DEFAULT_CURSOR, tree.getCursor().getType());
                    tree.dispatchEvent(new MouseEvent(tree, MouseEvent.MOUSE_PRESSED, 0, 0, point.x, point.y, 1, false, MouseEvent.BUTTON1));
                    assertSame(target, node.selectedItem());
                }
                assertSame(DirectoryChain.last(node.getUserObject()), tree.itemAt(new Point(850, row.y + 5)));
            }
            return null;
        });
    }

    @Test void watchersCoverMiddleSegmentsAndAreReleasedAfterRefresh() throws Exception {
        file();
        int baseline = subscriptions();
        var tree = tree(true);
        reveal(tree, "modules", "client", "items", "Test.tdscript");
        await(() -> subscriptions() == baseline + 4);
        Path middle = directory.resolve("scripts/modules/client");
        Files.writeString(middle.resolve("marker"), "");
        await(() -> firstFolder(tree).getUserObject().getPresentation().primary().equals("modules/client"));
        Files.delete(middle.resolve("marker"));
        await(() -> firstFolder(tree).getUserObject().getPresentation().primary().equals("modules/client/items"));
        for (int i = 0; i < 8; i++) tree.refreshDirectory(middle).get(5, TimeUnit.SECONDS);
        await(() -> subscriptions() == baseline + 4);
        edt(() -> { tree.setRootNodes(); return null; });
        await(() -> subscriptions() == baseline);
    }

    @Test void repeatedDirectoryIdentityStopsDiscoveryAndDisposesEveryDescriptorOnce() {
        var created = new AtomicInteger();
        var disposed = new AtomicInteger();
        class Cycle extends DirectoryTreeItem {
            Cycle() { super("folder"); created.incrementAndGet(); }
            @Override public List<TreeItem> loadChildren() { return List.of(); }
            @Override public String compactSeparator() { return "/"; }
            @Override public DirectoryTreeItem singleDirectoryChild() { return new Cycle(); }
            @Override public Object compactIdentity() { return "same-directory"; }
            @Override public void dispose() { disposed.incrementAndGet(); }
        }
        DirectoryChain.compactChildren(List.of(new Cycle())).forEach(TreeItem::dispose);
        assertEquals(2, created.get());
        assertEquals(created.get(), disposed.get());
    }

    private static int subscriptions() {
        synchronized (FileUtils.class) {
            try {
                var field = FileUtils.class.getDeclaredField("registrations"); field.setAccessible(true);
                int count = 0;
                for (Object value : ((Map<?, ?>) field.get(null)).values()) {
                    var listeners = value.getClass().getDeclaredField("listeners"); listeners.setAccessible(true);
                    count += ((Set<?>) listeners.get(value)).size();
                }
                return count;
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        }
    }

    private static LazyTreeNode firstFolder(LazyFileJTree tree) {
        return (LazyTreeNode) ((LazyTreeNode) tree.getModel().getRoot()).getChildAt(0).getChildAt(0);
    }

    private static Path selected(LazyFileJTree tree) {
        TreeItem item = ((LazyTreeNode) tree.getSelectionPath().getLastPathComponent()).selectedItem();
        return item instanceof FileSystemDirectoryItem folder ? folder.getPath() : ((FileSystemFileItem) item).getPath();
    }

    private static void key(LazyFileJTree tree, String key) {
        tree.getActionMap().get(tree.getInputMap().get(KeyStroke.getKeyStroke(key))).actionPerformed(new ActionEvent(tree, 0, ""));
    }

    private static void reveal(LazyFileJTree tree, String... path) throws Exception {
        assertTrue(tree.revealItemPath("scripts", List.of(path)).get(5, TimeUnit.SECONDS));
    }

    private static <T> T edt(Callable<T> work) throws Exception {
        var result = new AtomicReference<T>();
        SwingUtilities.invokeAndWait(() -> { try { result.set(work.call()); } catch (Exception failure) { throw new RuntimeException(failure); } });
        return result.get();
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!edt(condition::getAsBoolean)) {
            if (System.nanoTime() > deadline) fail("Tree did not reach the expected state");
            Thread.sleep(10);
        }
    }
}
