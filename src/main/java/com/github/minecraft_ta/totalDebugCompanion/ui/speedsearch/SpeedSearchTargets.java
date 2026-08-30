package com.github.minecraft_ta.totalDebugCompanion.ui.speedsearch;

import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JTable;
import javax.swing.JTabbedPane;
import javax.swing.JTree;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import javax.swing.event.RowSorterEvent;
import javax.swing.event.RowSorterListener;
import javax.swing.event.TableModelEvent;
import javax.swing.event.TableModelListener;
import javax.swing.event.TreeModelEvent;
import javax.swing.event.TreeModelListener;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.tree.TreePath;
import java.awt.Rectangle;
import java.beans.PropertyChangeListener;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.IntFunction;

final class SpeedSearchTargets {
    private SpeedSearchTargets() {
    }

    static <T> SpeedSearchTarget list(JList<T> list, Function<? super T, String> text) {
        return new ListTarget<>(list, text);
    }

    static SpeedSearchTarget tree(JTree tree, Function<? super TreePath, String> text) {
        return new TreeTarget(tree, text);
    }

    static SpeedSearchTarget table(JTable table, IntFunction<String> text) {
        return new TableTarget(table, text);
    }

    static SpeedSearchTarget tabs(JTabbedPane tabs, IntFunction<String> text) {
        return new TabTarget(tabs, text);
    }

    private abstract static class ModelTarget {
        Runnable contentListener = () -> {
        };

        final void changed() {
            this.contentListener.run();
        }
    }

    private static final class ListTarget<T> extends ModelTarget implements SpeedSearchTarget {
        private final JList<T> list;
        private final Function<? super T, String> text;
        private final ListDataListener modelListener = new ListDataListener() {
            @Override
            public void intervalAdded(ListDataEvent event) {
                changed();
            }

            @Override
            public void intervalRemoved(ListDataEvent event) {
                changed();
            }

            @Override
            public void contentsChanged(ListDataEvent event) {
                changed();
            }
        };
        private final PropertyChangeListener modelPropertyListener = event -> {
            if (event.getOldValue() instanceof javax.swing.ListModel<?> previous) {
                previous.removeListDataListener(this.modelListener);
            }
            if (event.getNewValue() instanceof javax.swing.ListModel<?> replacement) {
                replacement.addListDataListener(this.modelListener);
            }
            changed();
        };

        private ListTarget(JList<T> list, Function<? super T, String> text) {
            this.list = Objects.requireNonNull(list, "list");
            this.text = Objects.requireNonNull(text, "text");
        }

        @Override
        public JList<T> component() {
            return this.list;
        }

        @Override
        public int size() {
            return this.list.getModel().getSize();
        }

        @Override
        public String textAt(int index) {
            return this.text.apply(this.list.getModel().getElementAt(index));
        }

        @Override
        public int selectedIndex() {
            return this.list.getSelectedIndex();
        }

        @Override
        public void select(int index) {
            this.list.setSelectedIndex(index);
            scrollCenteredVertically(this.list, this.list.getCellBounds(index, index));
        }

        @Override
        public void installContentListener(Runnable listener) {
            this.contentListener = Objects.requireNonNull(listener, "listener");
            this.list.getModel().addListDataListener(this.modelListener);
            this.list.addPropertyChangeListener("model", this.modelPropertyListener);
        }

        @Override
        public void dispose() {
            this.list.getModel().removeListDataListener(this.modelListener);
            this.list.removePropertyChangeListener("model", this.modelPropertyListener);
        }
    }

    private static final class TreeTarget extends ModelTarget implements SpeedSearchTarget {
        private final JTree tree;
        private final Function<? super TreePath, String> text;
        private final TreeModelListener modelListener = new TreeModelListener() {
            @Override
            public void treeNodesChanged(TreeModelEvent event) {
                changed();
            }

            @Override
            public void treeNodesInserted(TreeModelEvent event) {
                changed();
            }

            @Override
            public void treeNodesRemoved(TreeModelEvent event) {
                changed();
            }

            @Override
            public void treeStructureChanged(TreeModelEvent event) {
                changed();
            }
        };
        private final TreeExpansionListener expansionListener = new TreeExpansionListener() {
            @Override
            public void treeExpanded(TreeExpansionEvent event) {
                changed();
            }

            @Override
            public void treeCollapsed(TreeExpansionEvent event) {
                changed();
            }
        };
        private final PropertyChangeListener modelPropertyListener = event -> {
            if (event.getOldValue() instanceof javax.swing.tree.TreeModel previous) {
                previous.removeTreeModelListener(this.modelListener);
            }
            if (event.getNewValue() instanceof javax.swing.tree.TreeModel replacement) {
                replacement.addTreeModelListener(this.modelListener);
            }
            changed();
        };

        private TreeTarget(JTree tree, Function<? super TreePath, String> text) {
            this.tree = Objects.requireNonNull(tree, "tree");
            this.text = Objects.requireNonNull(text, "text");
        }

        @Override
        public JTree component() {
            return this.tree;
        }

        @Override
        public int size() {
            return this.tree.getRowCount();
        }

        @Override
        public String textAt(int index) {
            TreePath path = this.tree.getPathForRow(index);
            return path == null ? "" : this.text.apply(path);
        }

        @Override
        public int selectedIndex() {
            return this.tree.getLeadSelectionRow();
        }

        @Override
        public void select(int index) {
            this.tree.setSelectionRow(index);
            scrollCenteredVertically(this.tree, this.tree.getRowBounds(index));
        }

        @Override
        public void installContentListener(Runnable listener) {
            this.contentListener = Objects.requireNonNull(listener, "listener");
            this.tree.getModel().addTreeModelListener(this.modelListener);
            this.tree.addPropertyChangeListener("model", this.modelPropertyListener);
            this.tree.addTreeExpansionListener(this.expansionListener);
        }

        @Override
        public void dispose() {
            this.tree.getModel().removeTreeModelListener(this.modelListener);
            this.tree.removePropertyChangeListener("model", this.modelPropertyListener);
            this.tree.removeTreeExpansionListener(this.expansionListener);
        }
    }

    private static final class TableTarget extends ModelTarget implements SpeedSearchTarget {
        private final JTable table;
        private final IntFunction<String> text;
        private final TableModelListener modelListener = this::modelChanged;
        private final RowSorterListener sorterListener = this::sorterChanged;
        private final PropertyChangeListener modelPropertyListener = event -> {
            if (event.getOldValue() instanceof javax.swing.table.TableModel previous) {
                previous.removeTableModelListener(this.modelListener);
            }
            if (event.getNewValue() instanceof javax.swing.table.TableModel replacement) {
                replacement.addTableModelListener(this.modelListener);
            }
            changed();
        };
        private final PropertyChangeListener sorterPropertyListener = event -> {
            if (event.getOldValue() instanceof javax.swing.RowSorter<?> previous) {
                previous.removeRowSorterListener(this.sorterListener);
            }
            if (event.getNewValue() instanceof javax.swing.RowSorter<?> replacement) {
                replacement.addRowSorterListener(this.sorterListener);
            }
            changed();
        };

        private TableTarget(JTable table, IntFunction<String> text) {
            this.table = Objects.requireNonNull(table, "table");
            this.text = Objects.requireNonNull(text, "text");
        }

        private void modelChanged(TableModelEvent ignored) {
            changed();
        }

        private void sorterChanged(RowSorterEvent ignored) {
            changed();
        }

        @Override
        public JTable component() {
            return this.table;
        }

        @Override
        public int size() {
            return this.table.getRowCount();
        }

        @Override
        public String textAt(int index) {
            return this.text.apply(index);
        }

        @Override
        public int selectedIndex() {
            return this.table.getSelectedRow();
        }

        @Override
        public void select(int index) {
            this.table.setRowSelectionInterval(index, index);
            scrollCenteredVertically(this.table, this.table.getCellRect(index, 0, true));
        }

        @Override
        public void installContentListener(Runnable listener) {
            this.contentListener = Objects.requireNonNull(listener, "listener");
            this.table.getModel().addTableModelListener(this.modelListener);
            this.table.addPropertyChangeListener("model", this.modelPropertyListener);
            if (this.table.getRowSorter() != null) {
                this.table.getRowSorter().addRowSorterListener(this.sorterListener);
            }
            this.table.addPropertyChangeListener("rowSorter", this.sorterPropertyListener);
        }

        @Override
        public void dispose() {
            this.table.getModel().removeTableModelListener(this.modelListener);
            this.table.removePropertyChangeListener("model", this.modelPropertyListener);
            if (this.table.getRowSorter() != null) {
                this.table.getRowSorter().removeRowSorterListener(this.sorterListener);
            }
            this.table.removePropertyChangeListener("rowSorter", this.sorterPropertyListener);
        }
    }

    private static final class TabTarget extends ModelTarget implements SpeedSearchTarget {
        private final JTabbedPane tabs;
        private final IntFunction<String> text;
        private final java.awt.event.ContainerListener containerListener = new java.awt.event.ContainerAdapter() {
            @Override
            public void componentAdded(java.awt.event.ContainerEvent event) {
                changed();
            }

            @Override
            public void componentRemoved(java.awt.event.ContainerEvent event) {
                changed();
            }
        };
        private final javax.swing.event.ChangeListener changeListener = event -> changed();

        private TabTarget(JTabbedPane tabs, IntFunction<String> text) {
            this.tabs = Objects.requireNonNull(tabs, "tabs");
            this.text = Objects.requireNonNull(text, "text");
        }

        @Override
        public JTabbedPane component() {
            return this.tabs;
        }

        @Override
        public int size() {
            return this.tabs.getTabCount();
        }

        @Override
        public String textAt(int index) {
            return this.text.apply(index);
        }

        @Override
        public int selectedIndex() {
            return this.tabs.getSelectedIndex();
        }

        @Override
        public void select(int index) {
            this.tabs.setSelectedIndex(index);
        }

        @Override
        public void installContentListener(Runnable listener) {
            this.contentListener = Objects.requireNonNull(listener, "listener");
            this.tabs.addContainerListener(this.containerListener);
            this.tabs.addChangeListener(this.changeListener);
        }

        @Override
        public void dispose() {
            this.tabs.removeContainerListener(this.containerListener);
            this.tabs.removeChangeListener(this.changeListener);
        }
    }

    private static void scrollCenteredVertically(JComponent component, Rectangle itemBounds) {
        if (itemBounds == null) {
            return;
        }
        Rectangle visible = component.getVisibleRect();
        int scrollHeight = Math.max(itemBounds.height, visible.height);
        int centeredY = itemBounds.y - Math.max(0, (visible.height - itemBounds.height) / 2);
        int maximumY = Math.max(0, component.getHeight() - scrollHeight);
        component.scrollRectToVisible(new Rectangle(
                visible.x,
                Math.max(0, Math.min(centeredY, maximumY)),
                Math.max(1, visible.width),
                scrollHeight
        ));
    }
}
