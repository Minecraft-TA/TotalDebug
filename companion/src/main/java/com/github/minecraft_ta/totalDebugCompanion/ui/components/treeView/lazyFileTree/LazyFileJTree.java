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
import java.awt.event.KeyEvent;
import java.awt.event.ActionEvent;
import java.util.List;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

public class LazyFileJTree extends JTree {

    private long interactionRevision;
    private TreePath gesturePath;
    private int gestureSegment = -1;
    private record RestoreState(long interaction, List<List<String>> expanded, List<List<String>> selected) { }
    private RestoreState restoration;
    private TreePath pressedPath;
    private int pressedSegment = -1;
    private TreePath hoverPath;
    private int hoverSegment = -1;
    private TreePath dropPath;
    private int dropSegment = -1;
    private final DirectoryChainRenderer measurement = new DirectoryChainRenderer();
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
        addTreeSelectionListener(event -> {
            for (TreePath path : event.getPaths()) if (event.isAddedPath(path) && path.getLastPathComponent() instanceof LazyTreeNode node)
                node.selectSegment(path.equals(pressedPath) ? pressedSegment : -1);
        });
        installSegmentNavigation("LEFT", -1);
        installSegmentNavigation("RIGHT", 1);
        enableEvents(AWTEvent.MOUSE_MOTION_EVENT_MASK);
        addPropertyChangeListener("dropLocation", event -> {
            DropLocation location = (DropLocation) event.getNewValue();
            dropPath = location == null ? null : rowAt(location.getDropPoint());
            dropSegment = dropPath == null ? -1 : segmentAt(dropPath, location.getDropPoint(), true);
            repaint();
        });
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
            private final DirectoryChainRenderer chain = new DirectoryChainRenderer();

            @Override
            public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded, boolean leaf, int row, boolean hasFocus) {
                super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
                if (!(value instanceof LazyTreeNode treeNode))
                    return this;

                TreeItem item = treeNode.getUserObject();
                if (item instanceof DirectoryChain directoryChain && directoryChain.supportsSegmentSelection()) {
                    TreePath path = new TreePath(treeNode.getPath());
                    chain.configure(LazyFileJTree.this, treeNode, sel, getTextSelectionColor(), getBackgroundSelectionColor(),
                            path.equals(hoverPath) ? hoverSegment : -1, path.equals(dropPath) ? dropSegment : -1);
                    return chain;
                }
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

    private DirectoryChainRenderer measuredChain(TreePath path) {
        var node = (LazyTreeNode) path.getLastPathComponent();
        var label = measurement;
        label.configure(this, node, false, getForeground(), getBackground(), -1, -1);
        Rectangle row = getPathBounds(path);
        label.setSize(row.width, row.height);
        return label;
    }

    private int segmentAt(TreePath path, Point point, boolean defaultToLast) {
        if (!(path.getLastPathComponent() instanceof LazyTreeNode node)) return -1;
        var parts = DirectoryChain.segments(node.getUserObject());
        if (!(node.getUserObject() instanceof DirectoryChain chain) || !chain.supportsSegmentSelection())
            return defaultToLast ? parts.size() - 1 : -1;
        Rectangle row = getPathBounds(path);
        int segment = row == null ? -1 : measuredChain(path).segmentAt(point.x - row.x);
        return segment < 0 && defaultToLast ? parts.size() - 1 : segment;
    }

    public TreeItem itemAt(Point point) {
        TreePath path = rowAt(point);
        if (path == null || !(path.getLastPathComponent() instanceof LazyTreeNode node)) return null;
        return DirectoryChain.segments(node.getUserObject()).get(segmentAt(path, point, true));
    }

    private TreePath rowAt(Point point) {
        TreePath path = getClosestPathForLocation(point.x, point.y);
        Rectangle bounds = path == null ? null : getPathBounds(path);
        return contains(point) && bounds != null && point.y >= bounds.y && point.y < bounds.y + bounds.height ? path : null;
    }

    @Override public String getToolTipText(MouseEvent event) {
        TreeItem item = itemAt(event.getPoint());
        return item == null ? null : item.getTooltip();
    }

    @Override protected void processMouseEvent(MouseEvent event) {
        if (event.getID() == MouseEvent.MOUSE_PRESSED) interactionRevision++;
        if (event.getID() == MouseEvent.MOUSE_PRESSED || event.isPopupTrigger()) {
            pressedPath = rowAt(event.getPoint());
            if (pressedPath != null && pressedPath.getLastPathComponent() instanceof LazyTreeNode && event.getX() >= getPathBounds(pressedPath).x) {
                pressedSegment = segmentAt(pressedPath, event.getPoint(), true);
                ((LazyTreeNode) pressedPath.getLastPathComponent()).selectSegment(pressedSegment);
            } else pressedPath = null;
            gesturePath = pressedPath;
            gestureSegment = pressedSegment;
        } else if (event.getID() == MouseEvent.MOUSE_RELEASED && gesturePath != null
                && isAttached((TreeNode) gesturePath.getLastPathComponent())) {
            // Swing defers some selections until release when drag-and-drop is enabled.
            pressedPath = gesturePath;
            pressedSegment = gestureSegment;
        }
        try { super.processMouseEvent(event); }
        finally {
            pressedPath = null; pressedSegment = -1;
            if (event.getID() == MouseEvent.MOUSE_RELEASED) { gesturePath = null; gestureSegment = -1; }
        }
        if (event.getID() == MouseEvent.MOUSE_EXITED) { hoverPath = null; hoverSegment = -1; setCursor(Cursor.getDefaultCursor()); }
        repaint();
    }

    @Override protected void processMouseMotionEvent(MouseEvent event) {
        super.processMouseMotionEvent(event);
        if (event.getID() != MouseEvent.MOUSE_MOVED) return;
        TreePath path = rowAt(event.getPoint());
        int segment = path == null ? -1 : segmentAt(path, event.getPoint(), false);
        if (!Objects.equals(path, hoverPath) || segment != hoverSegment) {
            hoverPath = path; hoverSegment = segment;
            repaint();
        }
    }

    @Override protected void processKeyEvent(KeyEvent event) {
        if (event.getID() == KeyEvent.KEY_PRESSED) interactionRevision++;
        super.processKeyEvent(event);
    }

    private void installSegmentNavigation(String key, int direction) {
        KeyStroke stroke = KeyStroke.getKeyStroke(key);
        Action original = getActionMap().get(getInputMap().get(stroke));
        String name = "directorySegment" + key;
        getInputMap().put(stroke, name);
        getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) {
                TreePath path = getLeadSelectionPath();
                if (path != null && path.getLastPathComponent() instanceof LazyTreeNode node
                        && node.getUserObject() instanceof DirectoryChain chain && chain.supportsSegmentSelection()) {
                    int next = node.selectedSegment() + direction;
                    if ((direction > 0 || !isExpanded(path)) && next >= 0 && next < DirectoryChain.segments(node.getUserObject()).size()) {
                        node.selectSegment(next); repaint(); return;
                    }
                }
                if (original != null) original.actionPerformed(event);
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
                DirectoryChain.compactChildren(source.loadChildren()).stream()
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
            if (items.stream().anyMatch(DirectoryChain::changedDuringDiscovery)) {
                items.forEach(TreeItem::dispose);
                node.markChildrenStale();
                return loadItemsForNode(node);
            }
            // Filesystem notifications concern this directory; its loaded subfolders can stay cached.
            return updateChildren(node, items, node.refreshDescendants() || !(DirectoryChain.last(source) instanceof FileSystemDirectoryItem));
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
        SwingUtilities.invokeLater(() -> {
            interactionRevision++;
            revealItemPathOnEventThread(topLevelRoot, container, segments).whenComplete((revealed, failure) -> {
                    if (failure != null) {
                        result.completeExceptionally(failure);
                    } else {
                        result.complete(revealed);
                    }
                });
        });
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
            interactionRevision++;
            LazyTreeNode root = findTopLevelNode(topLevelRoot);
            CompletableFuture<Located> path = root == null
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
            if (container == null) return CompletableFuture.completedFuture(null);
            var path = new ArrayList<>(segments);
            path.add(0, containerName);
            return findItemPath(root, path, 0);
        }).thenApply(this::revealPath);
    }

    private void completeReveal(
            CompletableFuture<Boolean> result,
            Located path,
            Throwable failure
    ) {
        if (failure != null) {
            result.completeExceptionally(failure);
        } else {
            result.complete(revealPath(path));
        }
    }

    private record Located(TreePath path, int segment) { }

    private boolean revealPath(Located location) {
        if (location == null || !isAttached((LazyTreeNode) location.path().getLastPathComponent())) {
            return false;
        }
        TreePath path = location.path();
        setSelectionPath(path);
        ((LazyTreeNode) path.getLastPathComponent()).selectSegment(location.segment());
        expandPath(path);
        scrollPathToVisible(path);
        requestFocusInWindow();
        return true;
    }

    private CompletableFuture<Located> findItemPath(
            LazyTreeNode parent,
            List<String> segments,
            int segmentIndex
    ) {
        if (segmentIndex == segments.size()) {
            return CompletableFuture.completedFuture(new Located(new TreePath(parent.getPath()), DirectoryChain.segments(parent.getUserObject()).size() - 1));
        }
        return loadItemsForNode(parent).thenCompose(ignored -> {
            String segment = segments.get(segmentIndex);
            LazyTreeNode child = children(parent).stream()
                    .filter(node -> node.getUserObject().isDirectory() || segmentIndex == segments.size() - 1)
                    .filter(node -> node.getUserObject().getName().equals(segment))
                    .findFirst()
                    .orElse(null);
            if (child == null) return CompletableFuture.completedFuture(null);
            var parts = DirectoryChain.segments(child.getUserObject());
            int consumed = 0;
            while (consumed < parts.size() && segmentIndex + consumed < segments.size()) {
                if (!parts.get(consumed).getName().equals(segments.get(segmentIndex + consumed))) return CompletableFuture.completedFuture(null);
                consumed++;
            }
            if (segmentIndex + consumed == segments.size())
                return CompletableFuture.completedFuture(new Located(new TreePath(child.getPath()), consumed - 1));
            return findItemPath(child, segments, segmentIndex + consumed);
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
        interactionRevision++;
        restoration = null;
        clearPointerTargets();
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
        ItemKey(TreeItem item) { this(DirectoryChain.first(item).getClass(), item.getName()); }
    }

    private CompletableFuture<Void> updateChildren(LazyTreeNode parent, List<? extends TreeItem> items, boolean refreshDirectories) {
        // An unloaded node has only its placeholder, so there is no child state to preserve.
        if (parent.getChildCount() == 1 && !(parent.getChildAt(0) instanceof LazyTreeNode)) {
            var path = new TreePath(parent.getPath());
            boolean expanded = isExpanded(path);
            parent.replaceChildren(items);
            getModel().nodeStructureChanged(parent);
            if (expanded) expandPath(path);
            return CompletableFuture.completedFuture(null);
        }
        var expanded = getExpandedDescendants(new TreePath(parent.getPath()));
        var expandedPaths = expanded == null ? List.<TreePath>of() : Collections.list(expanded);
        var selection = getSelectionPaths();
        var expandedAddresses = expandedPaths.stream().map(path -> address(path, false)).filter(Objects::nonNull).toList();
        var selectedAddresses = selection == null ? List.<List<String>>of() : Arrays.stream(selection).map(path -> address(path, true)).filter(Objects::nonNull).toList();
        boolean regrouped = false;
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
                boolean endpointChanged = !DirectoryChain.segments(previous).stream().map(TreeItem::getName).toList()
                        .equals(DirectoryChain.segments(item).stream().map(TreeItem::getName).toList());
                boolean wasLoaded = child.areChildrenLoaded() || this.activeLoads.containsKey(child) || child.refreshDescendants();
                if (previous != item) previous.dispose();
                child.setUserObject(item);
                if (endpointChanged) {
                    regrouped = true;
                    child.selectSegment(-1);
                    children(child).forEach(LazyFileJTree::disposeNode);
                    child.resetChildren();
                    clearPointerTargets();
                    getModel().nodeStructureChanged(child);
                }
                if ((refreshDirectories || endpointChanged || child.refreshDescendants()) && item.isDirectory() && wasLoaded) {
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
        // A descendant owns its own error row; waiting must not turn its failure into a parent error.
        var loads = refresh.stream().map(node -> loadItemsForNode(node).handle((ignored, failure) -> null)).toArray(CompletableFuture[]::new);
        if (!regrouped) return CompletableFuture.allOf(loads);
        if (restoration != null && restoration.interaction() == interactionRevision) {
            var merged = new LinkedHashSet<>(restoration.expanded());
            merged.addAll(expandedAddresses);
            expandedAddresses = List.copyOf(merged);
            selectedAddresses = restoration.selected();
        }
        RestoreState state = new RestoreState(interactionRevision, expandedAddresses, selectedAddresses);
        restoration = state;
        return CompletableFuture.allOf(loads).thenComposeAsync(ignored -> restoreAddresses(state), SwingUtilities::invokeLater);
    }

    private void clearPointerTargets() {
        pressedPath = null; gesturePath = null; hoverPath = null; dropPath = null;
        pressedSegment = gestureSegment = hoverSegment = dropSegment = -1;
        setCursor(Cursor.getDefaultCursor());
    }

    private List<String> address(TreePath path, boolean selected) {
        var names = new ArrayList<String>();
        for (Object value : path.getPath()) {
            if (!(value instanceof LazyTreeNode node)) return null;
            if (node.getUserObject().isHiddenRoot()) continue;
            var parts = DirectoryChain.segments(node.getUserObject());
            int count = selected && node == path.getLastPathComponent() ? node.selectedSegment() + 1 : parts.size();
            parts.subList(0, Math.min(count, parts.size())).forEach(item -> names.add(item.getName()));
        }
        return List.copyOf(names);
    }

    private CompletableFuture<Void> restoreAddresses(RestoreState state) {
        if (restoration != state || interactionRevision != state.interaction()) return CompletableFuture.completedFuture(null);
        var root = (LazyTreeNode) getModel().getRoot();
        var expansions = state.expanded().stream().map(path -> findItemPath(root, path, 0).exceptionally(failure -> null)).toList();
        var selections = state.selected().stream().map(path -> findItemPath(root, path, 0).exceptionally(failure -> null)).toList();
        var pending = new ArrayList<CompletableFuture<Located>>(expansions);
        pending.addAll(selections);
        return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).thenRunAsync(() -> {
            if (restoration != state || interactionRevision != state.interaction()) return;
            restoration = null;
            expansions.stream().map(CompletableFuture::join).filter(this::attached).forEach(location -> expandPath(location.path()));
            if (!state.selected().isEmpty()) {
                var found = selections.stream().map(CompletableFuture::join).filter(this::attached).toList();
                setSelectionPaths(found.stream().map(Located::path).toArray(TreePath[]::new));
                found.forEach(location -> ((LazyTreeNode) location.path().getLastPathComponent()).selectSegment(location.segment()));
                repaint();
            }
        }, SwingUtilities::invokeLater);
    }

    private boolean attached(Located location) {
        return location != null && isAttached((TreeNode) location.path().getLastPathComponent());
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
            var refresh = new LinkedHashSet<LazyTreeNode>();
            var nodes = ((LazyTreeNode) getModel().getRoot()).depthFirstEnumeration();
            while (nodes.hasMoreElements()) {
                if (!(nodes.nextElement() instanceof LazyTreeNode node)) continue;
                boolean contains = DirectoryChain.segments(node.getUserObject()).stream()
                        .anyMatch(item -> item instanceof FileSystemDirectoryItem folder && folder.getPath().equals(directory));
                if (!contains) continue;
                LazyTreeNode parent = node.getParent();
                // Even a single directory can become a chain after its children change.
                if (parent.getUserObject().isHiddenRoot()) refresh.add(node);
                else {
                    node.markChildrenStale(node.areChildrenLoaded() || activeLoads.containsKey(node));
                    refresh.add(parent);
                }
            }
            var loads = new ArrayList<CompletableFuture<Void>>();
            for (LazyTreeNode node : refresh) {
                node.markChildrenStale();
                loads.add(loadItemsForNode(node));
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
                    if (select) {
                        addSelectionPath(path.path());
                        ((LazyTreeNode) path.path().getLastPathComponent()).selectSegment(path.segment());
                        repaint();
                    } else expandPath(path.path());
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
