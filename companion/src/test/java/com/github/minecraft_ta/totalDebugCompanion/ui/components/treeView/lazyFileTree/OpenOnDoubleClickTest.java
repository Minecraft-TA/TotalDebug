package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree;

import com.github.minecraft_ta.totalDebugCompanion.ui.theme.CompanionTheme;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import javax.swing.tree.TreePath;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenOnDoubleClickTest {
    @Test
    void doubleClickingARowThatOpensSomethingOpensItWithoutExpandingIt() throws Exception {
        List<TreeItem> opened = new CopyOnWriteArrayList<>();
        DirectoryTreeItem page = new DirectoryTreeItem("Page") {
            @Override
            public boolean isActivatable() {
                return true;
            }

            @Override
            public List<TreeItem> loadChildren() {
                return List.of(new TreeItem("child") {
                });
            }
        };
        AtomicReference<LazyFileJTree> created = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            ThemeManager.installTheme(CompanionTheme.DEFAULT);
            LazyFileJTree tree = new LazyFileJTree();
            tree.setSize(400, 200);
            // The project tree moves scripts by dragging; Swing then handles clicks on a selected row on release.
            tree.setDragEnabled(true);
            tree.setRootNodes(page);
            tree.addMouseDoubleClickListener((node, item) -> opened.add(item));
            created.set(tree);
        });
        LazyFileJTree tree = created.get();
        try {
            assertTrue(tree.revealItemPath("Page", List.of()).get(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                TreePath row = tree.getSelectionPath();
                tree.collapsePath(row);
                Rectangle bounds = tree.getPathBounds(row);
                Point point = new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);

                click(tree, point, 2);

                assertFalse(tree.isExpanded(row), "Double-click opens the page without expanding its row");
                assertEquals(List.of(page), opened);
                tree.expandPath(row);
                assertTrue(tree.isExpanded(row), "The arrow and keys still expand it");
            });
        } finally {
            SwingUtilities.invokeAndWait(tree::setRootNodes);
        }
    }

    private static void click(LazyFileJTree tree, Point point, int count) {
        for (int id : new int[]{MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED}) {
            tree.dispatchEvent(new MouseEvent(tree, id, System.currentTimeMillis(), 0, point.x, point.y, count, false,
                    MouseEvent.BUTTON1));
        }
    }
}
