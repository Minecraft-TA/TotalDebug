package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree;

import javax.swing.*;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

public class LazyFileJTree extends JTree {

    private FileTreeItemFactory itemFactory = new FileTreeItemFactory();
    {
        itemFactory.setTree(this);
    }

    private final List<BiConsumer<LazyTreeNode, TreeItem>> mouseDoubleClickListeners = new ArrayList<>();

    public LazyFileJTree() {
        setShowsRootHandles(true);
        setRootVisible(false);
        setBorder(BorderFactory.createEmptyBorder());
        putClientProperty("JTree.wideSelection", true);
        ToolTipManager.sharedInstance().registerComponent(this);

        setModel(new DefaultTreeModel(new LazyTreeNode(this.itemFactory.createHiddenRoot())));
        addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent event) {
                var treePath = event.getPath();
                var lastComponent = treePath.getLastPathComponent();

                if (!(lastComponent instanceof LazyTreeNode treeNode) || !(treeNode.getUserObject() instanceof DirectoryTreeItem))
                    return;

                loadItemsForNode(treeNode);
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent event) {
            }
        });

        addKeyListener(new KeyAdapter() {
            @Override
            public void keyReleased(KeyEvent e) {
                if (e.getKeyCode() != KeyEvent.VK_DELETE)
                    return;

                deleteSelectedItems();
            }
        });

        addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                var pathForRow = getPathForRow(getClosestRowForLocation(e.getX(), e.getY()));
                if (pathForRow == null)
                    return;
                var bounds = getPathBounds(pathForRow);
                //We only care about the vertical bounds check
                bounds.setSize(10000, (int) bounds.getHeight());
                if (!bounds.contains(e.getX(), e.getY()))
                    return;

                LazyTreeNode node = (LazyTreeNode) pathForRow.getLastPathComponent();
                if (node == null)
                    return;
                TreeItem treeItem = node.getUserObject();
                if (treeItem.isDirectory())
                    return;

                if (SwingUtilities.isRightMouseButton(e)) {
                    if (!getSelectionModel().isPathSelected(pathForRow))
                        setSelectionPath(pathForRow);
                    showPopupMenu(node, treeItem, e.getX(), e.getY());
                    return;
                }

                if (!SwingUtilities.isLeftMouseButton(e) || e.getClickCount() < 2)
                    return;

                mouseDoubleClickListeners.forEach(l -> l.accept(node, treeItem));
            }
        });

        setCellRenderer(new DefaultTreeCellRenderer() {
            @Override
            public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded, boolean leaf, int row, boolean hasFocus) {
                super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
                if (!(value instanceof LazyTreeNode treeNode))
                    return this;

                setText(treeNode.getUserObject().getRenderedName());
                setIcon(treeNode.getUserObject().getIcon());
                setIconTextGap(5);
                setToolTipText(treeNode.getUserObject().getTooltip());
                return this;
            }
        });
    }

    @Override
    public void updateUI() {
        super.updateUI();
        Color background = UIManager.getColor("ToolWindow.background");
        if (background != null) {
            setBackground(background);
        }
    }

    protected void showPopupMenu(LazyTreeNode node, TreeItem treeItem, int x, int y) {

    }

    public void addRootNodes(DirectoryTreeItem... roots) {
        for (DirectoryTreeItem root : roots) {
            ((LazyTreeNode) getModel().getRoot()).add(new LazyTreeNode(root));
        }

        getModel().nodeStructureChanged((TreeNode) getModel().getRoot());
    }

    public void addMouseDoubleClickListener(BiConsumer<LazyTreeNode, TreeItem> listener) {
        this.mouseDoubleClickListeners.add(listener);
    }

    public void setItemFactory(FileTreeItemFactory itemFactory) {
        this.itemFactory = itemFactory;
        itemFactory.setTree(this);
    }

    public FileTreeItemFactory getItemFactory() {
        return itemFactory;
    }

    @Override
    public DefaultTreeModel getModel() {
        return (DefaultTreeModel) super.getModel();
    }

    public void loadItemsForTopLevelItem(TreeItem item) {
        var node = findTopLevelNodeForItem(item);
        if (node == null)
            throw new IllegalStateException();
        loadItemsForNode(node);
    }

    private void loadItemsForNode(LazyTreeNode node) {
        CompletableFuture.supplyAsync(() -> ((DirectoryTreeItem) node.getUserObject()).loadChildren().stream()
                .sorted(LazyFileJTree::compareTreeItems)
                .toList()).thenAccept((items) -> {
            SwingUtilities.invokeLater(() -> {
                var selection = getSelectionRows();

                node.removeAllChildren();
                items.forEach((i) -> node.add(new LazyTreeNode(i)));
                getModel().nodeStructureChanged(node);

                setSelectionRows(selection);
            });
        }).exceptionally((e) -> {
            e.printStackTrace();
            return null;
        });
    }

    public void setRootNodes(DirectoryTreeItem... roots) {
        LazyTreeNode hiddenRoot = (LazyTreeNode) getModel().getRoot();
        hiddenRoot.removeAllChildren();
        for (DirectoryTreeItem root : roots) {
            hiddenRoot.add(new LazyTreeNode(root));
        }
        getModel().nodeStructureChanged(hiddenRoot);
    }

    static int compareTreeItems(TreeItem first, TreeItem second) {
        int directoryOrder = Boolean.compare(second.isDirectory(), first.isDirectory());
        if (directoryOrder != 0) {
            return directoryOrder;
        }
        int nameOrder = String.CASE_INSENSITIVE_ORDER.compare(first.getName(), second.getName());
        return nameOrder != 0 ? nameOrder : first.getName().compareTo(second.getName());
    }

    public void deleteSelectedItems() {
        var paths = getSelectionModel().getSelectionPaths();
        if (paths == null || paths.length == 0)
            return;

        var rows = getSelectionRows();

        var toReload = new HashSet<LazyTreeNode>();
        Arrays.stream(paths)
                .map(TreePath::getLastPathComponent)
                .filter(Objects::nonNull)
                .forEach(node -> {
                    var item = ((LazyTreeNode) node).getUserObject();
                    item.delete();

                    toReload.add(((LazyTreeNode) node).getParent());
                });
        for (var lazyTreeNode : toReload)
            loadItemsForNode(lazyTreeNode);

        if (rows == null || rows.length == 0)
            return;

        SwingUtilities.invokeLater(() -> setSelectionRow(Math.max(0, rows[0] - 1)));
    }

    private LazyTreeNode findTopLevelNodeForItem(TreeItem item) {
        var root = (LazyTreeNode) getModel().getRoot();
        for (int i = 0; i < root.getChildCount(); i++) {
            if (!(root.getChildAt(i) instanceof LazyTreeNode child) || child.getUserObject() != item)
                continue;

            return child;
        }

        return null;
    }
}
