package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView;

import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.DirectoryTreeItem;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.DirectoryChain;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.LazyFileJTree;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.LazyTreeNode;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.TreeItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.SwingUtilities;
import javax.swing.KeyStroke;
import java.awt.Cursor;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryLabel;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.CompanionTheme;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Callable;
import static org.junit.jupiter.api.Assertions.*;

class CompactRuntimeTreeTest {
    @TempDir Path directory;

    @Test void archivePackagesResourcesAndEmptyDirectoriesKeepTheirTypesAndPaths() throws Exception {
        Path jar = directory.resolve("example.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (String path : List.of("com/example/mod/A.class", "com/example/mod/client/B.class",
                    "com/", "com/example/", "empty/", "assets/example/lang/en_us.json")) {
                zip.putNextEntry(new ZipEntry(path));
                if (!path.endsWith("/")) zip.write(new byte[]{1});
                zip.closeEntry();
            }
        }
        var tree = new LazyFileJTree();
        edt(() -> { tree.setRootNodes(new ZipFileRootItem(jar)); return null; });
        try {
            assertTrue(tree.revealItemPath("example.jar", List.of("com", "example", "mod", "A.class")).get(5, TimeUnit.SECONDS));
            edt(() -> {
                var file = (LazyTreeNode) tree.getSelectionPath().getLastPathComponent();
                assertEquals("com.example.mod", file.getParent().getUserObject().getPresentation().primary());
                assertEquals("com/example/mod/A.class", ((ZipFileRootItem.Entry) file.selectedItem()).getEntryPath());
                return null;
            });
            assertTrue(tree.revealItemPath("example.jar", List.of("com", "example")).get(5, TimeUnit.SECONDS));
            assertEquals(jar + "!/com/example/mod/", edt(() -> selected(tree).getTooltip()));
            assertTrue(tree.revealItemPath("example.jar", List.of("assets", "example", "lang", "en_us.json")).get(5, TimeUnit.SECONDS));
            assertEquals("assets/example/lang", edt(() -> ((LazyTreeNode) tree.getSelectionPath().getParentPath().getLastPathComponent()).getUserObject().getPresentation().primary()));
            assertTrue(tree.revealItemPath("example.jar", List.of("empty")).get(5, TimeUnit.SECONDS));
            assertTrue(edt(() -> ((LazyTreeNode) tree.getSelectionPath().getLastPathComponent()).isLeaf()));
            assertTrue(edt(() -> selected(tree).isDirectory()));
            var jarNode = edt(() -> (LazyTreeNode) ((LazyTreeNode) tree.getModel().getRoot()).getChildAt(0));
            assertEquals(3, edt(jarNode::getChildCount), "Explicit archive directory entries must not create duplicates");
        } finally { edt(() -> { tree.setRootNodes(); return null; }); }
    }

    @Test void readOnlyPackageChainsBehaveLikeOrdinaryFolderRowsInBothThemes() throws Exception {
        Path jar = directory.resolve("packages.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("com/example/mod/A.class"));
            zip.write(0);
            zip.closeEntry();
        }
        for (var theme : CompanionTheme.available()) {
            var tree = edt(() -> {
                ThemeManager.installTheme(theme);
                var result = new LazyFileJTree();
                result.setSize(600, 300);
                result.setRootNodes(new ZipFileRootItem(jar));
                return result;
            });
            try {
                assertTrue(tree.revealItemPath("packages.jar", List.of("com", "example", "mod", "A.class")).get(5, TimeUnit.SECONDS));
                assertTrue(tree.revealItemPath("packages.jar", List.of("com")).get(5, TimeUnit.SECONDS));
                edt(() -> {
                    var row = tree.getSelectionPath();
                    var node = (LazyTreeNode) row.getLastPathComponent();
                    var bounds = tree.getPathBounds(row);
                    var point = new Point(bounds.x + 30, bounds.y + bounds.height / 2);
                    tree.dispatchEvent(new MouseEvent(tree, MouseEvent.MOUSE_MOVED, 0, 0, point.x, point.y, 0, false));
                    assertEquals(Cursor.DEFAULT_CURSOR, tree.getCursor().getType());
                    tree.dispatchEvent(new MouseEvent(tree, MouseEvent.MOUSE_PRESSED, 1, 0, point.x, point.y, 1, false, MouseEvent.BUTTON1));
                    tree.dispatchEvent(new MouseEvent(tree, MouseEvent.MOUSE_RELEASED, 2, 0, point.x, point.y, 1, false, MouseEvent.BUTTON1));
                    assertSame(DirectoryChain.last(node.getUserObject()), node.selectedItem());
                    assertSame(node.selectedItem(), tree.itemAt(point));
                    assertInstanceOf(PrimarySecondaryLabel.class, tree.getCellRenderer().getTreeCellRendererComponent(tree, node, true, false, false, tree.getRowForPath(row), true));
                    tree.collapsePath(row);
                    key(tree, "RIGHT");
                    assertTrue(tree.isExpanded(row));
                    key(tree, "LEFT");
                    assertFalse(tree.isExpanded(row));
                    key(tree, "LEFT");
                    assertEquals(row.getParentPath(), tree.getSelectionPath());
                    tree.setSelectionPath(row);
                    tree.dispatchEvent(new MouseEvent(tree, MouseEvent.MOUSE_PRESSED, 3, 0, point.x, point.y, 2, false, MouseEvent.BUTTON1));
                    tree.dispatchEvent(new MouseEvent(tree, MouseEvent.MOUSE_RELEASED, 4, 0, point.x, point.y, 2, false, MouseEvent.BUTTON1));
                    assertTrue(tree.isExpanded(row), "Double-click expands the package row");
                    return null;
                });
            } finally { edt(() -> { tree.setRootNodes(); return null; }); }
        }
    }

    private static void key(LazyFileJTree tree, String key) {
        tree.getActionMap().get(tree.getInputMap().get(KeyStroke.getKeyStroke(key))).actionPerformed(new ActionEvent(tree, 0, ""));
    }

    @Test void runtimeDirectoryPackagesDoNotSwallowTheModuleRoot() throws Exception {
        Path module = Files.createDirectories(directory.resolve("java.base"));
        Files.write(Files.createDirectories(module.resolve("java/lang")).resolve("Object.class"), new byte[]{1});
        var tree = new LazyFileJTree();
        edt(() -> {
            tree.setRootNodes(new DirectoryTreeItem("Runtime") {
                @Override public List<TreeItem> loadChildren() { return List.of(new RuntimeSourceTreeItem.RuntimeDirectoryEntry(module, module)); }
            });
            return null;
        });
        try {
            assertTrue(tree.revealItemPath("Runtime", List.of("java.base", "java", "lang", "Object.class")).get(5, TimeUnit.SECONDS));
            edt(() -> {
                LazyTreeNode packages = ((LazyTreeNode) tree.getSelectionPath().getLastPathComponent()).getParent();
                assertEquals("java.lang", packages.getUserObject().getPresentation().primary());
                assertEquals("java.base", packages.getParent().getUserObject().getName());
                assertFalse(packages.getParent().getUserObject() instanceof DirectoryChain);
                assertEquals("java.lang.Object", ((RuntimeSourceTreeItem.RuntimeFileEntry) selected(tree)).binaryName());
                return null;
            });
        } finally { edt(() -> { tree.setRootNodes(); return null; }); }
    }

    private static TreeItem selected(LazyFileJTree tree) { return ((LazyTreeNode) tree.getSelectionPath().getLastPathComponent()).selectedItem(); }
    private static <T> T edt(Callable<T> action) throws Exception {
        var result = new AtomicReference<T>();
        SwingUtilities.invokeAndWait(() -> { try { result.set(action.call()); } catch (Exception failure) { throw new RuntimeException(failure); } });
        return result.get();
    }
}
