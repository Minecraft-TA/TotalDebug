package com.github.minecraft_ta.totalDebugCompanion.ui.components.inspection;

import com.github.minecraft_ta.totalDebugCompanion.ui.ContextMenus;
import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import com.github.minecraft_ta.totaldebug.protocol.execution.Fact;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTree;
import javax.swing.UIManager;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeCellRenderer;
import javax.swing.tree.TreePath;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Nested facts, such as NBT, as a collapsible key/value tree whose first level starts expanded. A newer read of the
 * same shape updates values in place, keeping expansion and marking changed values.
 */
final class FactTree extends JTree {
    private record Row(String label, String value, boolean omission, boolean changed) {
    }

    private final DefaultMutableTreeNode root;

    private FactTree(DefaultMutableTreeNode root) {
        super(new DefaultTreeModel(root));
        this.root = root;
        setRootVisible(false);
        setShowsRootHandles(true);
        setRowHeight(UiMetrics.TREE_ROW_HEIGHT);
        setCellRenderer(new Renderer());
        setOpaque(false);
        ContextMenus.installTree(this, this::menu);
    }

    /** Builds the tree; label paths in {@code expanded} are expanded, otherwise the first level is. */
    static FactTree of(List<Fact> facts, Set<List<String>> expanded) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode();
        for (Fact fact : facts) {
            root.add(node(fact));
        }
        FactTree tree = new FactTree(root);
        if (expanded == null) {
            for (int row = tree.getRowCount() - 1; row >= 0; row--) {
                tree.expandRow(row);
            }
        } else {
            expanded.stream().sorted(Comparator.comparingInt(List::size)).forEach(tree::expandLabels);
        }
        return tree;
    }

    private static DefaultMutableTreeNode node(Fact fact) {
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(new Row(fact.label(), fact.value(), false, false));
        for (Fact child : fact.children()) {
            node.add(node(child));
        }
        if (fact.omittedChildren() > 0) {
            node.add(omission(fact.omittedChildren()));
        }
        return node;
    }

    private static DefaultMutableTreeNode omission(int omitted) {
        return new DefaultMutableTreeNode(new Row(omitted + " more not shown", "", true, false));
    }

    /** Applies a newer read with the same shape: values change in place and differing ones are marked. */
    void update(List<Fact> facts) {
        DefaultTreeModel model = (DefaultTreeModel) getModel();
        for (int index = 0; index < facts.size(); index++) {
            update(model, (DefaultMutableTreeNode) this.root.getChildAt(index), facts.get(index));
        }
    }

    private static void update(DefaultTreeModel model, DefaultMutableTreeNode node, Fact fact) {
        Row previous = (Row) node.getUserObject();
        node.setUserObject(new Row(fact.label(), fact.value(), false, !previous.value().equals(fact.value())));
        model.nodeChanged(node);
        for (int index = 0; index < fact.children().size(); index++) {
            update(model, (DefaultMutableTreeNode) node.getChildAt(index), fact.children().get(index));
        }
    }

    /** Label paths of the expanded nodes, for restoring them in a rebuilt tree. */
    Set<List<String>> expandedLabels() {
        Set<List<String>> result = new LinkedHashSet<>();
        var descendants = getExpandedDescendants(new TreePath(this.root));
        if (descendants != null) {
            while (descendants.hasMoreElements()) {
                List<String> labels = new ArrayList<>();
                for (Object component : descendants.nextElement().getPath()) {
                    if (((DefaultMutableTreeNode) component).getUserObject() instanceof Row row) {
                        labels.add(row.label());
                    }
                }
                if (!labels.isEmpty()) result.add(labels);
            }
        }
        return result;
    }

    private void expandLabels(List<String> labels) {
        DefaultMutableTreeNode current = this.root;
        TreePath path = new TreePath(this.root);
        for (String label : labels) {
            DefaultMutableTreeNode next = null;
            for (int index = 0; index < current.getChildCount(); index++) {
                DefaultMutableTreeNode child = (DefaultMutableTreeNode) current.getChildAt(index);
                if (child.getUserObject() instanceof Row row && !row.omission() && row.label().equals(label)) {
                    next = child;
                    break;
                }
            }
            if (next == null) return;
            current = next;
            path = path.pathByAddingChild(next);
        }
        expandPath(path);
    }

    private JPopupMenu menu(TreePath path) {
        JPopupMenu menu = new JPopupMenu();
        if (path == null || !(((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject() instanceof Row row)
                || row.omission()) {
            return menu;
        }
        menu.add(ContextMenus.defaultCopy(ContextMenus.copyAction("Copy value", row.value())));
        menu.add(ContextMenus.copyAction("Copy key", row.label()));
        return menu;
    }

    private static final class Renderer extends JPanel implements TreeCellRenderer {
        private final JLabel label = new JLabel();
        private final JLabel value = new JLabel();

        private Renderer() {
            super(new FlowLayout(FlowLayout.LEFT, 0, 0));
            setOpaque(false);
            this.value.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
            add(this.label);
            add(Box.createHorizontalStrut(4));
            add(this.value);
        }

        @Override
        public Component getTreeCellRendererComponent(
                JTree tree, Object node, boolean selected, boolean expanded, boolean leaf, int row, boolean focused) {
            if (!(((DefaultMutableTreeNode) node).getUserObject() instanceof Row fact)) {
                // The hidden root is measured when its children change.
                this.label.setText("");
                this.value.setText("");
                this.value.setOpaque(false);
                return this;
            }
            this.label.setText(fact.label());
            this.value.setText(fact.value());
            this.label.setForeground(fact.omission()
                    ? UIManager.getColor("Label.disabledForeground")
                    : selected ? UIManager.getColor("Tree.selectionForeground") : UIManager.getColor("Label.foreground"));
            this.value.setForeground(selected
                    ? UIManager.getColor("Tree.selectionForeground")
                    : UIManager.getColor("Label.disabledForeground"));
            this.value.setOpaque(fact.changed() && !selected);
            this.value.setBackground(ChangeMarks.tint());
            return this;
        }
    }
}
