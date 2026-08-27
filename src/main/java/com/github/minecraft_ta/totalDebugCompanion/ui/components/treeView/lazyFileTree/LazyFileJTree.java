package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree;

import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryLabel;

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
    private final Map<LazyTreeNode, CompletableFuture<Void>> activeLoads = new IdentityHashMap<>();

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
            private final PrimarySecondaryLabel presentation = new PrimarySecondaryLabel();

            @Override
            public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded, boolean leaf, int row, boolean hasFocus) {
                super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
                if (!(value instanceof LazyTreeNode treeNode))
                    return this;

                TreeItem item = treeNode.getUserObject();
                this.presentation.configure(
                        item.getPresentation(),
                        item.getIcon(),
                        getFont(),
                        sel,
                        getTextSelectionColor(),
                        getBackgroundSelectionColor()
                );
                this.presentation.setToolTipText(item.getTooltip());
                return this.presentation;
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
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> loadItemsForTopLevelItem(item));
            return;
        }
        var node = findTopLevelNodeForItem(item);
        if (node == null)
            return;
        node.markChildrenStale();
        loadItemsForNode(node);
    }

    private CompletableFuture<Void> loadItemsForNode(LazyTreeNode node) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Tree nodes must be loaded from the Swing event thread");
        }
        if (node.areChildrenLoaded()) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> existing = this.activeLoads.get(node);
        if (existing != null) {
            return existing;
        }

        CompletableFuture<Void> load = CompletableFuture.supplyAsync(() ->
                ((DirectoryTreeItem) node.getUserObject()).loadChildren().stream()
                .sorted(LazyFileJTree::compareTreeItems)
                .toList()
        ).thenAcceptAsync(items -> {
            if (isAttached(node)) {
                var selection = getSelectionRows();
                node.replaceChildren(items);
                getModel().nodeStructureChanged(node);
                setSelectionRows(selection);
            }
        }, SwingUtilities::invokeLater);
        this.activeLoads.put(node, load);
        load.whenComplete((ignored, failure) -> SwingUtilities.invokeLater(() -> {
            this.activeLoads.remove(node, load);
            if (failure != null) {
                failure.printStackTrace(System.err);
            }
        }));
        return load;
    }

    /** Reveals a directory path below one named container beneath a top-level root. */
    public CompletableFuture<Boolean> revealDirectoryPath(
            String topLevelRoot,
            String containerName,
            List<String> directorySegments
    ) {
        Objects.requireNonNull(topLevelRoot, "topLevelRoot");
        String container = Objects.requireNonNull(containerName, "containerName");
        if (container.isBlank()) {
            throw new IllegalArgumentException("A container name must not be blank");
        }
        List<String> segments = List.copyOf(Objects.requireNonNull(directorySegments, "directorySegments"));
        if (segments.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("A directory path must contain only non-blank segments");
        }

        CompletableFuture<Boolean> result = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> revealDirectoryPathOnEventThread(topLevelRoot, container, segments)
                .whenComplete((revealed, failure) -> {
                    if (failure != null) {
                        result.completeExceptionally(failure);
                    } else {
                        result.complete(revealed);
                    }
                }));
        return result;
    }

    /** Reveals a directory path relative to a top-level directory root. */
    public CompletableFuture<Boolean> revealDirectoryPath(String topLevelRoot, List<String> directorySegments) {
        Objects.requireNonNull(topLevelRoot, "topLevelRoot");
        List<String> segments = List.copyOf(Objects.requireNonNull(directorySegments, "directorySegments"));
        if (segments.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("A directory path must contain only non-blank segments");
        }

        CompletableFuture<Boolean> result = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> {
            LazyTreeNode root = findTopLevelNode(topLevelRoot);
            CompletableFuture<TreePath> path = root == null
                    ? CompletableFuture.completedFuture(null)
                    : findDirectoryPath(root, segments, 0);
            path.whenComplete((revealedPath, failure) -> completeReveal(result, revealedPath, failure));
        });
        return result;
    }

    private CompletableFuture<Boolean> revealDirectoryPathOnEventThread(
            String topLevelRoot,
            String containerName,
            List<String> segments
    ) {
        LazyTreeNode root = findTopLevelNode(topLevelRoot);
        if (root == null) {
            return CompletableFuture.completedFuture(false);
        }
        return loadItemsForNode(root).thenCompose(ignored -> {
            LazyTreeNode container = children(root).stream()
                    .filter(child -> child.getUserObject().isDirectory())
                    .filter(child -> child.getUserObject().getName().equals(containerName))
                    .findFirst()
                    .orElse(null);
            return container == null
                    ? CompletableFuture.completedFuture(null)
                    : findDirectoryPath(container, segments, 0);
        }).thenApply(this::revealPath);
    }

    private void completeReveal(
            CompletableFuture<Boolean> result,
            TreePath path,
            Throwable failure
    ) {
        if (failure != null) {
            result.completeExceptionally(failure);
        } else {
            result.complete(revealPath(path));
        }
    }

    private boolean revealPath(TreePath path) {
        if (path == null || !isAttached((LazyTreeNode) path.getLastPathComponent())) {
            return false;
        }
        setSelectionPath(path);
        expandPath(path);
        scrollPathToVisible(path);
        requestFocusInWindow();
        return true;
    }

    private CompletableFuture<TreePath> findDirectoryPath(
            LazyTreeNode parent,
            List<String> segments,
            int segmentIndex
    ) {
        if (segmentIndex == segments.size()) {
            return CompletableFuture.completedFuture(new TreePath(parent.getPath()));
        }
        return loadItemsForNode(parent).thenCompose(ignored -> {
            String segment = segments.get(segmentIndex);
            LazyTreeNode child = children(parent).stream()
                    .filter(node -> node.getUserObject().isDirectory())
                    .filter(node -> node.getUserObject().getName().equals(segment))
                    .findFirst()
                    .orElse(null);
            return child == null
                    ? CompletableFuture.completedFuture(null)
                    : findDirectoryPath(child, segments, segmentIndex + 1);
        });
    }

    private static List<LazyTreeNode> children(LazyTreeNode node) {
        List<LazyTreeNode> children = new ArrayList<>(node.getChildCount());
        for (int index = 0; index < node.getChildCount(); index++) {
            if (node.getChildAt(index) instanceof LazyTreeNode child) {
                children.add(child);
            }
        }
        return children;
    }

    private boolean isAttached(LazyTreeNode node) {
        TreeNode current = node;
        while (current.getParent() != null) {
            current = current.getParent();
        }
        return current == getModel().getRoot();
    }

    private LazyTreeNode findTopLevelNode(String name) {
        LazyTreeNode root = (LazyTreeNode) getModel().getRoot();
        return children(root).stream()
                .filter(child -> child.getUserObject().getName().equals(name))
                .findFirst()
                .orElse(null);
    }

    public void setRootNodes(DirectoryTreeItem... roots) {
        LazyTreeNode hiddenRoot = (LazyTreeNode) getModel().getRoot();
        for (int i = 0; i < hiddenRoot.getChildCount(); i++) {
            if (hiddenRoot.getChildAt(i) instanceof LazyTreeNode child) {
                child.getUserObject().dispose();
            }
        }
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
        for (var lazyTreeNode : toReload) {
            lazyTreeNode.markChildrenStale();
            loadItemsForNode(lazyTreeNode);
        }

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
