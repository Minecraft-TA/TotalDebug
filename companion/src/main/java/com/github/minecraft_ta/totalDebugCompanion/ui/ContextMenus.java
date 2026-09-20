package com.github.minecraft_ta.totalDebugCompanion.ui;

import com.github.minecraft_ta.totalDebugCompanion.Icons;

import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.text.JTextComponent;
import javax.swing.tree.TreePath;
import java.awt.Toolkit;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.awt.Component;

/** Shared Swing actions, clipboard commands, and popup behavior. */
public final class ContextMenus {
    private ContextMenus() {}

    private static final String DEFAULT_COPY = "ContextMenus.defaultCopy";

    public static Action action(String name, Icon icon, String shortcut, Runnable handler) {
        Action action = new AbstractAction(name, icon) {
            @Override public void actionPerformed(ActionEvent event) {
                if (isEnabled()) handler.run();
            }
        };
        action.putValue(Action.SHORT_DESCRIPTION, name);
        action.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(shortcut));
        return action;
    }

    public static Action copyAction(String name, String text) {
        Action action = action(name, Icons.COPY, null, () -> copyText(text));
        action.putValue(Action.ACTION_COMMAND_KEY, text);
        return action;
    }

    public static void copyText(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    /** Marks the command invoked by Ctrl+C in the owning view's row menus. */
    public static Action defaultCopy(Action action) {
        action.putValue(DEFAULT_COPY, true);
        action.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke("ctrl C"));
        return action;
    }

    public static void bindAction(JComponent component, Action action) {
        component.getInputMap(JComponent.WHEN_FOCUSED).put((KeyStroke) action.getValue(Action.ACCELERATOR_KEY), action);
        component.getActionMap().put(action, action);
    }

    public static void installTree(JTree tree, Function<TreePath, JPopupMenu> menu) {
        install(tree, point -> {
            TreePath path = tree.getClosestPathForLocation(point.x, point.y);
            Rectangle row = path == null ? null : tree.getPathBounds(path);
            if (!tree.contains(point) || row == null || point.y < row.y || point.y >= row.y + row.height) return null;
            if (!tree.isPathSelected(path)) tree.setSelectionPath(path);
            return tree.isPathSelected(path) ? menu.apply(path) : null;
        }, () -> menu.apply(tree.getSelectionPath()), () -> {
            TreePath path = tree.getSelectionPath();
            if (path == null) return null;
            tree.scrollPathToVisible(path);
            return tree.getPathBounds(path);
        });
    }

    public static void installList(JList<?> list, IntFunction<JPopupMenu> menu) {
        install(list, point -> {
            int row = list.locationToIndex(point);
            if (row < 0 || !list.getCellBounds(row, row).contains(point)) return null;
            list.setSelectedIndex(row);
            return list.getSelectedIndex() == row ? menu.apply(row) : null;
        }, () -> menu.apply(list.getSelectedIndex()), () -> {
            int row = list.getSelectedIndex();
            if (row < 0) return null;
            list.ensureIndexIsVisible(row);
            return list.getCellBounds(row, row);
        });
    }

    private static void install(JComponent owner, Function<Point, JPopupMenu> clicked,
                                Supplier<JPopupMenu> selected, Supplier<Rectangle> bounds) {
        owner.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) { popup(event); }
            @Override public void mouseReleased(MouseEvent event) { popup(event); }
            private void popup(MouseEvent event) {
                if (!event.isPopupTrigger()) return;
                JPopupMenu menu = clicked.apply(event.getPoint());
                if (menu == null || menu.getComponentCount() == 0) return;
                owner.requestFocusInWindow();
                menu.show(owner, event.getX(), event.getY());
                event.consume();
            }
        });
        bind(owner, "ctrl C", "copyRow", () -> copy(selected.get()));
        Runnable show = () -> {
            Rectangle location = bounds.get();
            JPopupMenu menu = selected.get();
            if (location != null && menu != null && menu.getComponentCount() > 0) {
                menu.show(owner, location.x, location.y + location.height);
            }
        };
        bind(owner, "shift F10", "rowMenu", show);
        bind(owner, "CONTEXT_MENU", "rowMenu", show);
    }

    /** For choosers that keep keyboard focus in their invoking editor. */
    public static void showKeyboardMenu(JComponent owner) {
        owner.getActionMap().get("rowMenu").actionPerformed(new ActionEvent(owner, ActionEvent.ACTION_PERFORMED, "rowMenu"));
    }

    public static boolean isOpenFor(Component owner) {
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
                JMenuItem copySelection = new JMenuItem(copyAction("Copy selection", selected == null ? "" : selected));
                copySelection.setAccelerator(KeyStroke.getKeyStroke("ctrl C"));
                copySelection.setEnabled(selected != null && !selected.isEmpty());
                menu.add(copySelection);
                JMenuItem copyAll = new JMenuItem(copyAction(copyAllLabel, output.getText()));
                copyAll.setEnabled(!output.getText().isEmpty());
                menu.add(copyAll);
            }
            @Override public void popupMenuWillBecomeInvisible(PopupMenuEvent event) {}
            @Override public void popupMenuCanceled(PopupMenuEvent event) {}
        });
        output.setComponentPopupMenu(menu);
    }

    private static void bind(JComponent component, String shortcut, String name, Runnable handler) {
        component.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(shortcut), name);
        component.getActionMap().put(name, action(name, null, shortcut, handler));
    }

    public static void copy(JPopupMenu menu) {
        if (menu == null) return;
        for (var component : menu.getComponents()) {
            if (component instanceof JMenuItem item && item.getAction() != null
                    && Boolean.TRUE.equals(item.getAction().getValue(DEFAULT_COPY))) {
                Action action = item.getAction();
                if (action.isEnabled()) action.actionPerformed(new ActionEvent(menu, ActionEvent.ACTION_PERFORMED, "copy"));
                return;
            }
        }
    }
}
