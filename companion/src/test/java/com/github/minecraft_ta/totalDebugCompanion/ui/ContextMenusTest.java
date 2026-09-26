package com.github.minecraft_ta.totalDebugCompanion.ui;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.event.ActionEvent;
import java.awt.Component;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.event.PopupMenuEvent;
import static org.junit.jupiter.api.Assertions.*;

class ContextMenusTest {
    @Test
    void outputMenuPreservesSelectionAndOffersTheCompleteOutput() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextPane output = new JTextPane();
            output.setText("first\nsecond");
            output.select(6, 12);
            ContextMenus.installOutput(output, "Copy output");
            JPopupMenu menu = output.getComponentPopupMenu();
            for (var listener : menu.getPopupMenuListeners()) {
                listener.popupMenuWillBecomeVisible(new PopupMenuEvent(menu));
            }
            assertEquals("second", output.getSelectedText());
            assertEquals("second", ((JMenuItem) menu.getComponent(0)).getActionCommand());
            assertEquals(output.getText(), ((JMenuItem) menu.getComponent(1)).getActionCommand());
        });
    }

    @Test
    void keyboardAndRightClickUseTheSelectedRowAndIgnoreBlankSpace() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var root = new DefaultMutableTreeNode("root");
            root.add(new DefaultMutableTreeNode("first"));
            root.add(new DefaultMutableTreeNode("second"));
            JTree tree = new JTree(root);
            tree.setRootVisible(false);
            List<String> copied = new ArrayList<>();
            List<JPopupMenu> shown = new ArrayList<>();
            ContextMenus.installTree(tree, path -> {
                JPopupMenu menu = new JPopupMenu() {
                    @Override public void show(Component invoker, int x, int y) { shown.add(this); }
                };
                if (path != null) menu.add(ContextMenus.defaultCopy(ContextMenus.action("Copy value", null, null,
                        () -> copied.add(path.getLastPathComponent().toString()))));
                return menu;
            });
            tree.setSize(400, 300);
            tree.setSelectionRow(0);
            tree.getActionMap().get("copyRow").actionPerformed(new ActionEvent(tree, 0, ""));
            assertEquals(List.of("first"), copied);
            var bounds = tree.getRowBounds(1);
            // The painted row extends beyond the renderer's icon/text bounds.
            assertTrue(bounds.x + bounds.width < 390);
            tree.dispatchEvent(new MouseEvent(tree, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(),
                    0, 390, bounds.y + bounds.height / 2, 1, true, MouseEvent.BUTTON3));
            assertEquals(1, shown.size());
            ((JMenuItem) shown.getFirst().getComponent(0)).doClick(0);
            assertEquals(List.of("first", "second"), copied);
            tree.addSelectionRow(0);
            tree.dispatchEvent(new MouseEvent(tree, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(),
                    0, 390, bounds.y, 1, true, MouseEvent.BUTTON3));
            assertEquals(2, tree.getSelectionCount(), "Right click must preserve the selected group");
            assertEquals(2, shown.size());
            tree.dispatchEvent(new MouseEvent(tree, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(),
                    0, 20, 250, 1, true, MouseEvent.BUTTON3));
            assertEquals(2, shown.size(), "Empty space below the rows is not a row");
            tree.dispatchEvent(new MouseEvent(tree, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(),
                    0, 401, bounds.y, 1, true, MouseEvent.BUTTON3));
            assertEquals(2, shown.size());
        });
    }

    @Test
    void rightClickInsideATableSelectionKeepsItAndOutsideSelectsTheRow() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTable table = new JTable(new Object[][]{{"first"}, {"second"}, {"third"}}, new Object[]{"Name"});
            table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
            table.setSize(400, table.getRowHeight() * 3);
            List<Integer> selectedCounts = new ArrayList<>();
            ContextMenus.installTable(table, row -> {
                selectedCounts.add(table.getSelectedRowCount());
                return new JPopupMenu() {
                    @Override public void show(Component invoker, int x, int y) { }
                };
            });
            table.setRowSelectionInterval(0, 1);
            rightClick(table, 1);
            assertEquals(List.of(2), selectedCounts);
            assertArrayEquals(new int[]{0, 1}, table.getSelectedRows());
            rightClick(table, 2);
            assertArrayEquals(new int[]{2}, table.getSelectedRows());
        });
    }

    private static void rightClick(JTable table, int row) {
        var bounds = table.getCellRect(row, 0, true);
        table.dispatchEvent(new MouseEvent(table, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(),
                0, bounds.x + 5, bounds.y + bounds.height / 2, 1, true, MouseEvent.BUTTON3));
    }

    @Test
    void defaultCopyUsesItsActionEvenAfterRenamingAndRespectsEnabledState() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTree tree = new JTree();
            int[] copies = {0};
            Action copy = ContextMenus.defaultCopy(ContextMenus.action("Copy value", null, null, () -> copies[0]++));
            ContextMenus.installTree(tree, path -> {
                JPopupMenu menu = new JPopupMenu();
                menu.add(ContextMenus.action("Copy value", null, null, () -> fail("Dispatched by label")));
                menu.add(copy);
                return menu;
            });
            copy.putValue(Action.NAME, "Renamed command");
            Object key = tree.getInputMap().get(KeyStroke.getKeyStroke("ctrl C"));
            Action shortcut = tree.getActionMap().get(key);
            shortcut.actionPerformed(new ActionEvent(tree, 0, ""));
            assertEquals(1, copies[0]);
            copy.setEnabled(false);
            shortcut.actionPerformed(new ActionEvent(tree, 0, ""));
            assertEquals(1, copies[0]);
        });
    }
}
