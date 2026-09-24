package com.github.minecraft_ta.totalDebugCompanion.ui.components.inspection;

import com.github.minecraft_ta.totalDebugCompanion.ui.ContextMenus;
import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import com.github.minecraft_ta.totaldebug.protocol.execution.Fact;

import javax.swing.BorderFactory;
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
import java.util.List;

/** Nested facts, such as NBT, as a collapsible key/value tree whose first level starts expanded. */
final class FactTree extends JTree {
    private record Row(String label, String value, boolean omission) {
    }

    private FactTree(DefaultMutableTreeNode root) {
        super(new DefaultTreeModel(root));
        setRootVisible(false);
        setShowsRootHandles(true);
        setRowHeight(UiMetrics.TREE_ROW_HEIGHT);
        setCellRenderer(new Renderer());
        setOpaque(false);
        ContextMenus.installTree(this, this::menu);
    }

    static FactTree of(List<Fact> facts) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode();
        for (Fact fact : facts) {
            root.add(node(fact));
        }
        FactTree tree = new FactTree(root);
        for (int row = tree.getRowCount() - 1; row >= 0; row--) {
            tree.expandRow(row);
        }
        return tree;
    }

    private static DefaultMutableTreeNode node(Fact fact) {
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(new Row(fact.label(), fact.value(), false));
        for (Fact child : fact.children()) {
            node.add(node(child));
        }
        if (fact.omittedChildren() > 0) {
            node.add(new DefaultMutableTreeNode(new Row(fact.omittedChildren() + " more not shown", "", true)));
        }
        return node;
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
            this.value.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
            add(this.label);
            add(this.value);
        }

        @Override
        public Component getTreeCellRendererComponent(
                JTree tree, Object node, boolean selected, boolean expanded, boolean leaf, int row, boolean focused) {
            Row fact = (Row) ((DefaultMutableTreeNode) node).getUserObject();
            this.label.setText(fact.label());
            this.value.setText(fact.value());
            this.label.setForeground(fact.omission()
                    ? UIManager.getColor("Label.disabledForeground")
                    : selected ? UIManager.getColor("Tree.selectionForeground") : UIManager.getColor("Label.foreground"));
            this.value.setForeground(selected
                    ? UIManager.getColor("Tree.selectionForeground")
                    : UIManager.getColor("Label.disabledForeground"));
            return this;
        }
    }
}
