package com.github.minecraft_ta.totalDebugCompanion.ui;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
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
                listener.popupMenuWillBecomeVisible(new javax.swing.event.PopupMenuEvent(menu));
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
            ContextMenus.installTree(tree, path -> {
                JPopupMenu menu = new JPopupMenu();
                if (path != null) menu.add(ContextMenus.defaultCopy(ContextMenus.action("Copy value", null, null,
                        () -> copied.add(path.getLastPathComponent().toString()))));
                return menu;
            });
            JFrame window = new JFrame();
            try {
                window.add(tree);
                window.setSize(400, 300);
                window.setVisible(true);
                tree.setSelectionRow(0);
                tree.getActionMap().get("copyRow").actionPerformed(new ActionEvent(tree, 0, ""));
                assertEquals(List.of("first"), copied);
                var bounds = tree.getRowBounds(1);
                tree.dispatchEvent(new MouseEvent(tree, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(),
                        0, bounds.x + 5, bounds.y + bounds.height / 2, 1, true, MouseEvent.BUTTON3));
                var menu = (JPopupMenu) MenuSelectionManager.defaultManager().getSelectedPath()[0];
                ((JMenuItem) menu.getComponent(0)).doClick(0);
                assertEquals(List.of("first", "second"), copied);
                MenuSelectionManager.defaultManager().clearSelectedPath();
                tree.dispatchEvent(new MouseEvent(tree, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(),
                        0, 20, 250, 1, true, MouseEvent.BUTTON3));
                assertEquals(0, MenuSelectionManager.defaultManager().getSelectedPath().length);
            } finally { window.dispose(); }
        });
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
