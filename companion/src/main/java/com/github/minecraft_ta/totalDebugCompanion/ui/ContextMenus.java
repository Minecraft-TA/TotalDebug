package com.github.minecraft_ta.totalDebugCompanion.ui;

import com.github.minecraft_ta.totalDebugCompanion.Icons;

import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.text.JTextComponent;
import javax.swing.tree.TreePath;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Function;
import java.util.function.IntFunction;

/** Context commands shared by trees and read-only output. */
public final class ContextMenus {
    private ContextMenus() {}

    public static JMenuItem copyItem(String name, String text) {
        JMenuItem item = new JMenuItem(name, Icons.COPY);
        item.setActionCommand(text);
        item.addActionListener(event -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(event.getActionCommand()), null));
        return item;
    }

    public static void installTree(JTree tree, Function<TreePath, JPopupMenu> menu, String defaultCopy) {
        tree.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) { popup(event); }
            @Override public void mouseReleased(MouseEvent event) { popup(event); }
            private void popup(MouseEvent event) {
                if (!event.isPopupTrigger()) return;
                TreePath path = tree.getPathForLocation(event.getX(), event.getY());
                if (path == null) return;
                if (!tree.isPathSelected(path)) tree.setSelectionPath(path);
                show(menu.apply(path), tree, event.getX(), event.getY(), defaultCopy);
            }
        });
        bind(tree, "ctrl C", "copyRow", () -> invoke(menu.apply(tree.getSelectionPath()), defaultCopy));
        Runnable keyboardMenu = () -> {
            TreePath path = tree.getSelectionPath();
            if (path == null) return;
            var bounds = tree.getPathBounds(path);
            if (bounds != null) show(menu.apply(path), tree, bounds.x, bounds.y + bounds.height, defaultCopy);
        };
        bind(tree, "shift F10", "rowMenu", keyboardMenu);
        bind(tree, "CONTEXT_MENU", "rowMenu", keyboardMenu);
    }

    public static void installList(JList<?> list, IntFunction<JPopupMenu> menu, String defaultCopy) {
        list.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) { popup(event); }
            @Override public void mouseReleased(MouseEvent event) { popup(event); }
            private void popup(MouseEvent event) {
                if (!event.isPopupTrigger()) return;
                int row = list.locationToIndex(event.getPoint());
                if (row < 0 || !list.getCellBounds(row, row).contains(event.getPoint())) return;
                list.setSelectedIndex(row);
                show(menu.apply(row), list, event.getX(), event.getY(), defaultCopy);
            }
        });
        bind(list, "ctrl C", "copyRow", () -> invoke(menu.apply(list.getSelectedIndex()), defaultCopy));
        bind(list, "shift F10", "rowMenu", () -> showListMenu(list, menu, defaultCopy));
        bind(list, "CONTEXT_MENU", "rowMenu", () -> showListMenu(list, menu, defaultCopy));
    }

    public static void showListMenu(JList<?> list, IntFunction<JPopupMenu> menu, String defaultCopy) {
        int row = list.getSelectedIndex();
        if (row < 0) return;
        var bounds = list.getCellBounds(row, row);
        show(menu.apply(row), list, bounds.x, bounds.y + bounds.height, defaultCopy);
    }

    public static boolean isOpenFor(java.awt.Component owner) {
        var path = MenuSelectionManager.defaultManager().getSelectedPath();
        return path.length > 0 && path[0] instanceof JPopupMenu menu
                && (menu.getInvoker() == owner || SwingUtilities.isDescendingFrom(menu.getInvoker(), owner));
    }

    public static void installOutput(JTextComponent output, String copyAllLabel) {
        JPopupMenu menu = new JPopupMenu();
        menu.addPopupMenuListener(new PopupMenuListener() {
            @Override public void popupMenuWillBecomeVisible(PopupMenuEvent event) {
                menu.removeAll();
                String selected = output.getSelectedText();
                JMenuItem copySelection = copyItem("Copy selection", selected == null ? "" : selected);
                copySelection.setAccelerator(KeyStroke.getKeyStroke("ctrl C"));
                copySelection.setEnabled(selected != null && !selected.isEmpty());
                menu.add(copySelection);
                JMenuItem copyAll = copyItem(copyAllLabel, output.getText());
                copyAll.setEnabled(!output.getText().isEmpty());
                menu.add(copyAll);
            }
            @Override public void popupMenuWillBecomeInvisible(PopupMenuEvent event) {}
            @Override public void popupMenuCanceled(PopupMenuEvent event) {}
        });
        output.setComponentPopupMenu(menu);
    }

    public static void bind(JComponent component, String shortcut, String name, Runnable handler) {
        component.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(shortcut), name);
        component.getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { handler.run(); }
        });
    }

    public static void invoke(JPopupMenu menu, String name) {
        for (var component : menu.getComponents()) {
            if (component instanceof JMenuItem item && name.equals(item.getText()) && item.isEnabled()) {
                item.doClick(0);
                return;
            }
        }
    }

    private static void show(JPopupMenu menu, JComponent owner, int x, int y, String defaultCopy) {
        for (var component : menu.getComponents()) {
            if (component instanceof JMenuItem item && defaultCopy.equals(item.getText())) {
                item.setAccelerator(KeyStroke.getKeyStroke("ctrl C"));
            }
        }
        if (menu.getComponentCount() > 0) menu.show(owner, x, y);
    }
}
