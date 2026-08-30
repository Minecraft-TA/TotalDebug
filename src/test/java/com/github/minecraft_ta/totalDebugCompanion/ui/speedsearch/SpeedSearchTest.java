package com.github.minecraft_ta.totalDebugCompanion.ui.speedsearch;

import org.junit.jupiter.api.Test;

import javax.swing.DefaultListModel;
import javax.swing.JList;
import javax.swing.JTable;
import javax.swing.JTabbedPane;
import javax.swing.JTree;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeedSearchTest {
    @Test
    void typingSelectsAndCyclesMatchesWithoutChangingTheModel() throws Exception {
        onEventThread(() -> {
            DefaultListModel<String> model = model("Alpha", "Beta", "Gamma", "Garden");
            JList<String> list = new JList<>(model);
            list.setSelectedIndex(0);
            SpeedSearch search = SpeedSearch.install(list, value -> value);

            type(list, 'g');
            assertEquals("g", search.query());
            assertEquals(2, list.getSelectedIndex());
            press(list, KeyEvent.VK_DOWN, 0);
            assertEquals(3, list.getSelectedIndex());
            press(list, KeyEvent.VK_DOWN, 0);
            assertEquals(2, list.getSelectedIndex());
            assertEquals(4, model.size());

            KeyEvent enter = press(list, KeyEvent.VK_ENTER, 0);
            assertFalse(enter.isConsumed(), "Enter remains available to the component's normal action");
            assertFalse(search.isActive());
            search.close();
        });
    }

    @Test
    void escapeBackspaceAndControlFManageTheSearchSession() throws Exception {
        onEventThread(() -> {
            JList<String> list = new JList<>(new String[]{"Alpha", "Beta"});
            SpeedSearch search = SpeedSearch.install(list, value -> value);

            KeyEvent find = press(list, KeyEvent.VK_F, KeyEvent.CTRL_DOWN_MASK);
            assertTrue(find.isConsumed());
            assertTrue(search.isActive());
            assertEquals("", search.query());

            type(list, 'b');
            type(list, 'e');
            press(list, KeyEvent.VK_BACK_SPACE, 0);
            assertEquals("b", search.query());
            press(list, KeyEvent.VK_BACK_SPACE, 0);
            assertFalse(search.isActive());

            type(list, 'a');
            press(list, KeyEvent.VK_ESCAPE, 0);
            assertFalse(search.isActive());
            search.close();
        });
    }

    @Test
    void spacesSeparateFragmentsAndArrowsVisitOnlyMatchingRows() throws Exception {
        onEventThread(() -> {
            JList<String> list = new JList<>(new String[]{
                    "build.gradle",
                    "model",
                    "gradlew",
                    "logger",
                    "settings.gradle"
            });
            list.setSelectedIndex(0);
            SpeedSearch search = SpeedSearch.install(list, value -> value);

            for (char character : "gra le".toCharArray()) {
                type(list, character);
            }
            assertEquals("gra le", search.query());
            list.setSelectedIndex(0);
            press(list, KeyEvent.VK_DOWN, 0);
            assertEquals(2, list.getSelectedIndex());
            press(list, KeyEvent.VK_DOWN, 0);
            assertEquals(4, list.getSelectedIndex());
            press(list, KeyEvent.VK_DOWN, 0);
            assertEquals(0, list.getSelectedIndex());
            search.close();
        });
    }

    @Test
    void sessionsAreIsolatedBetweenComponents() throws Exception {
        onEventThread(() -> {
            JList<String> first = new JList<>(new String[]{"Alpha", "Beta"});
            JList<String> second = new JList<>(new String[]{"Gamma", "Delta"});
            SpeedSearch firstSearch = SpeedSearch.install(first, value -> value);
            SpeedSearch secondSearch = SpeedSearch.install(second, value -> value);

            type(first, 'b');
            assertEquals(1, first.getSelectedIndex());
            assertEquals(-1, second.getSelectedIndex());
            assertEquals("b", firstSearch.query());
            assertEquals("", secondSearch.query());

            firstSearch.close();
            secondSearch.close();
        });
    }

    @Test
    void anExplicitInputSourceRoutesTypingOnlyWhileInstalled() throws Exception {
        onEventThread(() -> {
            JTextField editor = new JTextField();
            JList<String> chooser = new JList<>(new String[]{"Alpha", "Beta"});
            SpeedSearch search = SpeedSearch.install(chooser, editor, value -> value);

            type(editor, 'b');
            assertEquals(1, chooser.getSelectedIndex());
            assertEquals("", editor.getText());

            search.close();
            KeyEvent afterClose = typedEvent(editor, 'a');
            for (KeyListener listener : editor.getKeyListeners()) {
                listener.keyTyped(afterClose);
            }
            assertFalse(afterClose.isConsumed());
        });
    }

    @Test
    void modelChangesRefreshAnActiveSearch() throws Exception {
        onEventThread(() -> {
            DefaultListModel<String> model = model("Alpha");
            JList<String> list = new JList<>(model);
            SpeedSearch search = SpeedSearch.install(list, value -> value);

            type(list, 'z');
            assertEquals(-1, list.getSelectedIndex());
            model.addElement("Zebra");
            assertEquals(1, list.getSelectedIndex());
            search.close();
        });
    }

    @Test
    void treeSearchVisitsOnlyExpandedRows() throws Exception {
        onEventThread(() -> {
            DefaultMutableTreeNode root = new DefaultMutableTreeNode("root");
            root.add(new DefaultMutableTreeNode("Alpha"));
            DefaultMutableTreeNode group = new DefaultMutableTreeNode("Group");
            group.add(new DefaultMutableTreeNode("HiddenNeedle"));
            root.add(group);
            JTree tree = new JTree(new DefaultTreeModel(root));
            tree.setRootVisible(false);
            tree.expandRow(0);
            tree.setSelectionRow(0);
            AtomicBoolean inspectedHiddenNode = new AtomicBoolean();
            SpeedSearch search = SpeedSearch.install(tree, path -> {
                if (path.getLastPathComponent() == group.getFirstChild()) {
                    inspectedHiddenNode.set(true);
                }
                return path.getLastPathComponent().toString();
            });

            type(tree, 'h');
            type(tree, 'i');
            type(tree, 'd');
            assertEquals(0, tree.getSelectionRows()[0]);
            assertFalse(inspectedHiddenNode.get());
            search.close();
        });
    }

    @Test
    void tableAndTabAdaptersUseViewRowsAndTitles() throws Exception {
        onEventThread(() -> {
            JTable table = new JTable(new DefaultTableModel(
                    new Object[][]{{"Alpha", "one"}, {"Beta", "two"}},
                    new Object[]{"Name", "Value"}
            ));
            SpeedSearch tableSearch = SpeedSearch.install(
                    table,
                    row -> table.getValueAt(row, 0) + " " + table.getValueAt(row, 1)
            );
            type(table, 't');
            type(table, 'w');
            assertEquals(1, table.getSelectedRow());

            JTabbedPane tabs = new JTabbedPane();
            tabs.addTab("First.java", new javax.swing.JPanel());
            tabs.addTab("Second.java", new javax.swing.JPanel());
            SpeedSearch tabSearch = SpeedSearch.install(tabs, tabs::getTitleAt);
            type(tabs, 's');
            type(tabs, 'e');
            assertEquals(1, tabs.getSelectedIndex());

            tableSearch.close();
            tabSearch.close();
        });
    }

    @Test
    void treeSelectionPreservesTheHorizontalViewport() throws Exception {
        onEventThread(() -> {
            DefaultMutableTreeNode root = new DefaultMutableTreeNode("root");
            DefaultMutableTreeNode parent = root;
            for (int index = 0; index < 20; index++) {
                DefaultMutableTreeNode child = new DefaultMutableTreeNode(
                        index == 19 ? "Needle" : "Group " + index
                );
                parent.add(child);
                parent = child;
            }
            JTree tree = new JTree(new DefaultTreeModel(root));
            tree.setRootVisible(false);
            tree.setRowHeight(20);
            tree.setSize(700, 400);
            for (int row = 0; row < 20; row++) {
                tree.expandRow(row);
            }
            JViewport viewport = new JViewport();
            viewport.setExtentSize(new Dimension(120, 60));
            viewport.setView(tree);
            viewport.setViewPosition(new Point(0, 0));

            SpeedSearchTarget target = SpeedSearchTargets.tree(
                    tree,
                    path -> path.getLastPathComponent().toString()
            );
            target.select(19);

            assertEquals(0, viewport.getViewPosition().x);
            assertTrue(viewport.getViewPosition().y > 0);
        });
    }

    private static DefaultListModel<String> model(String... values) {
        DefaultListModel<String> model = new DefaultListModel<>();
        for (String value : values) {
            model.addElement(value);
        }
        return model;
    }

    private static void type(javax.swing.JComponent component, char character) {
        KeyEvent event = typedEvent(component, character);
        for (KeyListener listener : component.getKeyListeners()) {
            listener.keyTyped(event);
        }
        assertTrue(event.isConsumed(), "Speed-search typing should not reach the component itself");
    }

    private static KeyEvent typedEvent(javax.swing.JComponent component, char character) {
        return new KeyEvent(
                component,
                KeyEvent.KEY_TYPED,
                1L,
                0,
                KeyEvent.VK_UNDEFINED,
                character
        );
    }

    private static KeyEvent press(javax.swing.JComponent component, int keyCode, int modifiers) {
        KeyEvent event = new KeyEvent(
                component,
                KeyEvent.KEY_PRESSED,
                1L,
                modifiers,
                keyCode,
                KeyEvent.CHAR_UNDEFINED
        );
        for (KeyListener listener : component.getKeyListeners()) {
            listener.keyPressed(event);
        }
        return event;
    }

    private static void onEventThread(ThrowingRunnable task) throws InvocationTargetException, InterruptedException {
        SwingUtilities.invokeAndWait(() -> {
            try {
                task.run();
            } catch (RuntimeException | Error exception) {
                throw exception;
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
