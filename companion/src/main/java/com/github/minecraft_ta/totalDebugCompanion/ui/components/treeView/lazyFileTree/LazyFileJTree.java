package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree;

import com.github.minecraft_ta.totalDebugCompanion.util.UIUtils;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryLabel;
import com.github.minecraft_ta.totalDebugCompanion.ui.speedsearch.SpeedSearch;

import javax.swing.*;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.nio.file.Path;
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
        SpeedSearch.install(this, path -> {
            if (!(path.getLastPathComponent() instanceof LazyTreeNode node)) {
                return "";
            }
            var presentation = node.getUserObject().getPresentation();
            return presentation.secondary().isBlank()
                    ? presentation.primary()
                    : presentation.primary() + ' ' + presentation.secondary();
        });
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

        addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                var pathForRow = getPathForRow(getClosestRowForLocation(e.getX(), e.getY()));
                if (pathForRow == null)
                    return;
                var bounds = getPathBounds(pathForRow);
                if (bounds == null)
                    return;
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
                        getBackgroundSelectionColor(),
                        tree
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
        UIUtils.onEdt(() -> {
            var node = findTopLevelNodeForItem(item);
            if (node == null) return;
            node.markChildrenStale();
            loadItemsForNode(node);
        });
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

        int revision = node.revision();
        DirectoryTreeItem source = (DirectoryTreeItem) node.getUserObject();
        CompletableFuture<Void> load = CompletableFuture.supplyAsync(() ->
                source.loadChildren().stream()
                .sorted(LazyFileJTree::compareTreeItems)
                .toList()
        ).thenComposeAsync(items -> {
            this.activeLoads.remove(node);
            if (!isAttached(node)) {
                items.forEach(TreeItem::dispose);
                return CompletableFuture.completedFuture(null);
            }
            if (source != node.getUserObject() || revision != node.revision()) {
                items.forEach(TreeItem::dispose);
                return loadItemsForNode(node);
            }
            // Filesystem notifications concern this directory; its loaded subfolders can stay cached.
            updateChildren(node, items, node.refreshDescendants() || !(source instanceof FileSystemDirectoryItem));
            return CompletableFuture.completedFuture(null);
        }, SwingUtilities::invokeLater);
        this.activeLoads.put(node, load);
        load.whenComplete((ignored, failure) -> SwingUtilities.invokeLater(() -> {
            this.activeLoads.remove(node, load);
            if (failure != null) {
                failure.printStackTrace(System.err);
                if (isAttached(node) && (source != node.getUserObject() || revision != node.revision())) {
                    loadItemsForNode(node);
                    return;
                }
                if (isAttached(node) && source == node.getUserObject() && revision == node.revision()) {
                    Throwable cause = failure;
                    while (cause.getCause() != null) cause = cause.getCause();
                    var error = new TreeItem(Objects.requireNonNullElse(cause.getMessage(), cause.toString()));
                    error.setIcon(Icons.ERROR);
                    children(node).forEach(LazyFileJTree::disposeNode);
                    node.replaceChildren(List.of(error));
                    getModel().nodeStructureChanged(node);
                }
            }
        }));
        return load;
    }

    /** Reveals an item below one named container beneath a top-level root. */
    public CompletableFuture<Boolean> revealItemPath(
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
        SwingUtilities.invokeLater(() -> revealItemPathOnEventThread(topLevelRoot, container, segments)
                .whenComplete((revealed, failure) -> {
                    if (failure != null) {
                        result.completeExceptionally(failure);
                    } else {
                        result.complete(revealed);
                    }
                }));
        return result;
    }

    /** Reveals an item relative to a top-level directory root. */
    public CompletableFuture<Boolean> revealItemPath(String topLevelRoot, List<String> directorySegments) {
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
                    : findItemPath(root, segments, 0);
            path.whenComplete((revealedPath, failure) -> completeReveal(result, revealedPath, failure));
        });
        return result;
    }

    private CompletableFuture<Boolean> revealItemPathOnEventThread(
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
                    : findItemPath(container, segments, 0);
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

    private CompletableFuture<TreePath> findItemPath(
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
                    .filter(node -> node.getUserObject().isDirectory() || segmentIndex == segments.size() - 1)
                    .filter(node -> node.getUserObject().getName().equals(segment))
                    .findFirst()
                    .orElse(null);
            return child == null
                    ? CompletableFuture.completedFuture(null)
                    : findItemPath(child, segments, segmentIndex + 1);
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

    private boolean isAttached(TreeNode node) {
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
                disposeNode(child);
            }
        }
        hiddenRoot.removeAllChildren();
        for (DirectoryTreeItem root : roots) {
            hiddenRoot.add(new LazyTreeNode(root));
        }
        getModel().nodeStructureChanged(hiddenRoot);
    }

    /** Refresh the current project's roots without replacing surviving tree paths. */
    public void refreshRootNodes(DirectoryTreeItem... roots) {
        updateChildren((LazyTreeNode) getModel().getRoot(), List.of(roots), true);
    }

    private record ItemKey(Class<?> type, String name) {
        ItemKey(TreeItem item) { this(item.getClass(), item.getName()); }
    }

    private void updateChildren(LazyTreeNode parent, List<? extends TreeItem> items, boolean refreshDirectories) {
        // An unloaded node has only its placeholder, so there is no child state to preserve.
        if (parent.getChildCount() == 1 && !(parent.getChildAt(0) instanceof LazyTreeNode)) {
            var path = new TreePath(parent.getPath());
            boolean expanded = isExpanded(path);
            parent.replaceChildren(items);
            getModel().nodeStructureChanged(parent);
            if (expanded) expandPath(path);
            return;
        }
        var expanded = getExpandedDescendants(new TreePath(parent.getPath()));
        var expandedPaths = expanded == null ? List.<TreePath>of() : Collections.list(expanded);
        var selection = getSelectionPaths();
        Map<ItemKey, LazyTreeNode> existing = new HashMap<>();
        children(parent).forEach(child -> existing.put(new ItemKey(child.getUserObject()), child));
        List<LazyTreeNode> updated = new ArrayList<>();
        List<LazyTreeNode> refresh = new ArrayList<>();
        Set<LazyTreeNode> changed = new HashSet<>();
        for (TreeItem item : items) {
            LazyTreeNode child = existing.remove(new ItemKey(item));
            if (child == null) {
                child = new LazyTreeNode(item);
            } else {
                TreeItem previous = child.getUserObject();
                if (!previous.getPresentation().equals(item.getPresentation())
                        || previous.getIcon() != item.getIcon()
                        || !Objects.equals(previous.getTooltip(), item.getTooltip())) changed.add(child);
                if (previous != item) previous.dispose();
                child.setUserObject(item);
                if (refreshDirectories && item.isDirectory()
                        && (child.areChildrenLoaded() || this.activeLoads.containsKey(child))) {
                    child.markChildrenStale(true);
                    refresh.add(child);
                }
            }
            updated.add(child);
        }
        var retained = new HashSet<>(updated);
        List<Integer> removedIndices = new ArrayList<>();
        List<TreeNode> removed = new ArrayList<>();
        for (int i = parent.getChildCount() - 1; i >= 0; i--) {
            var child = parent.getChildAt(i);
            if (retained.contains(child)) continue;
            if (child instanceof LazyTreeNode node) disposeNode(node);
            removedIndices.add(i);
            removed.add(child);
            parent.remove(i);
        }
        if (!removed.isEmpty()) {
            Collections.reverse(removedIndices);
            Collections.reverse(removed);
            getModel().nodesWereRemoved(parent, removedIndices.stream().mapToInt(Integer::intValue).toArray(), removed.toArray());
        }
        parent.markChildrenLoaded();
        List<Integer> insertedIndices = new ArrayList<>();
        List<Integer> changedIndices = new ArrayList<>();
        for (int i = 0; i < updated.size(); i++) {
            LazyTreeNode child = updated.get(i);
            if (child.getParent() == null) {
                parent.insert(child, i);
                insertedIndices.add(i);
            } else if (parent.getChildAt(i) != child) {
                // Publish pending insertions before moving an existing row, so event indices stay valid.
                getModel().nodesWereInserted(parent, insertedIndices.stream().mapToInt(Integer::intValue).toArray());
                insertedIndices.clear();
                getModel().removeNodeFromParent(child);
                getModel().insertNodeInto(child, parent, i);
            } else if (changed.contains(child)) {
                changedIndices.add(i);
            }
        }
        getModel().nodesWereInserted(parent, insertedIndices.stream().mapToInt(Integer::intValue).toArray());
        getModel().nodesChanged(parent, changedIndices.stream().mapToInt(Integer::intValue).toArray());
        expandedPaths.stream().filter(path -> isAttached((TreeNode) path.getLastPathComponent()))
                .forEach(this::expandPath);
        if (selection != null) {
            setSelectionPaths(Arrays.stream(selection)
                    .filter(path -> isAttached((TreeNode) path.getLastPathComponent())).toArray(TreePath[]::new));
        }
        refresh.forEach(this::loadItemsForNode);
    }

    static int compareTreeItems(TreeItem first, TreeItem second) {
        int directoryOrder = Boolean.compare(second.isDirectory(), first.isDirectory());
        if (directoryOrder != 0) {
            return directoryOrder;
        }
        int priorityOrder = Integer.compare(first.getSortPriority(), second.getSortPriority());
        if (priorityOrder != 0) {
            return priorityOrder;
        }
        int nameOrder = String.CASE_INSENSITIVE_ORDER.compare(first.getName(), second.getName());
        return nameOrder != 0 ? nameOrder : first.getName().compareTo(second.getName());
    }

    public CompletableFuture<Void> refreshDirectory(Path directory) {
        var result = new CompletableFuture<Void>();
        UIUtils.onEdt(() -> {
            var loads = new ArrayList<CompletableFuture<Void>>();
            var nodes = ((LazyTreeNode) getModel().getRoot()).depthFirstEnumeration();
            while (nodes.hasMoreElements()) {
                if (!(nodes.nextElement() instanceof LazyTreeNode node)
                        || !(node.getUserObject() instanceof FileSystemDirectoryItem item) || !item.getPath().equals(directory)) continue;
                boolean loaded = node.areChildrenLoaded() || activeLoads.containsKey(node);
                node.markChildrenStale();
                if (loaded) loads.add(loadItemsForNode(node));
            }
            CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).whenComplete((ignored, failure) -> {
                if (failure == null) result.complete(null); else result.completeExceptionally(failure);
            });
        });
        return result;
    }

    public CompletableFuture<Void> restoreItemPath(String rootName, List<String> segments, boolean select) {
        var result = new CompletableFuture<Void>();
        UIUtils.onEdt(() -> {
            var root = findTopLevelNode(rootName);
            if (root == null) { result.complete(null); return; }
            findItemPath(root, segments, 0).whenComplete((path, failure) -> {
                if (path != null) {
                    if (select) addSelectionPath(path);
                    else expandPath(path);
                }
                if (failure == null) result.complete(null); else result.completeExceptionally(failure);
            });
        });
        return result;
    }

    private static void disposeNode(LazyTreeNode node) {
        for (var child : children(node)) disposeNode(child);
        node.getUserObject().dispose();
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
