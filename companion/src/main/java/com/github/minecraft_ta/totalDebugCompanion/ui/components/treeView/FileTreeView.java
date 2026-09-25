package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView;

import com.github.minecraft_ta.totalDebugCompanion.project.ProjectScope;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.util.Objects;
import java.util.function.Supplier;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.ui.ContextMenus;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.minecraft_ta.totalDebugCompanion.navigation.ModTab;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totaldebug.storage.RuntimeInventory;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeSourceCatalog;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.*;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryText;

import javax.swing.*;
import java.awt.Color;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

public class FileTreeView extends JScrollPane {
    private final LazyFileJTree tree;
    private ScriptFileActions fileActions;
    private boolean disposed;
    private ProjectScope pendingScriptsProject;
    private CompletableFuture<Void> pendingScriptsRoot;
    LazyFileJTree tree() { return tree; }
    public void setFileActions(ScriptFileActions actions) { fileActions = actions; }
    public CompletableFuture<Void> refreshDirectory(Path parent) {
        if (parent == null) return CompletableFuture.completedFuture(null);
        return CompletableFuture.<Void>completedFuture(null).thenComposeAsync(ignored ->
                ensureScriptsRoot().thenCompose(ready -> tree.refreshDirectory(parent)), SwingUtilities::invokeLater);
    }

    private boolean hasScriptsRoot(ProjectScope scope) {
        if (scope == null) return false;
        var root = (LazyTreeNode) tree.getModel().getRoot();
        for (int i = 0; i < root.getChildCount(); i++) {
            var item = ((LazyTreeNode) root.getChildAt(i)).getUserObject();
            if (item instanceof FileSystemDirectoryItem folder && folder.getPath().equals(scope.paths().scripts())) return true;
        }
        return false;
    }

    /** Creation can introduce Scripts after profile loading; prepare only that new root off the EDT. */
    private CompletableFuture<Void> ensureScriptsRoot() {
        var scope = project.get();
        if (disposed || scope == null || scope.phase() == ProjectScope.Phase.RETIRED)
            return CompletableFuture.failedFuture(new CancellationException("Files view is no longer active"));
        if (hasScriptsRoot(scope)) return CompletableFuture.completedFuture(null);
        if (pendingScriptsProject == scope && pendingScriptsRoot != null) return pendingScriptsRoot;
        var factory = tree.getItemFactory();
        var loading = CompletableFuture.supplyAsync(() -> {
            if (!Files.isDirectory(scope.paths().scripts())) return null;
            var scripts = factory.createFileSystemDirectoryItem(scope.paths().scripts(), true);
            scripts.setIcon(FileTreeIcons.forRootDirectory("scripts"));
            return scripts;
        }).thenAcceptAsync(scripts -> {
            if (disposed || project.get() != scope || scope.phase() == ProjectScope.Phase.RETIRED) {
                if (scripts != null) scripts.dispose();
                throw new CancellationException("Project changed while preparing Scripts");
            }
            if (scripts == null) return;
            if (hasScriptsRoot(scope)) scripts.dispose();
            else tree.insertRootNode(scripts, 0);
        }, SwingUtilities::invokeLater);
        pendingScriptsProject = scope;
        pendingScriptsRoot = loading;
        loading.whenCompleteAsync((ignored, failure) -> {
            if (pendingScriptsRoot == loading) {
                pendingScriptsProject = null;
                pendingScriptsRoot = null;
            }
        }, SwingUtilities::invokeLater);
        return loading;
    }
    public void dispose() { disposed = true; tree.setRootNodes(); }


    private final Supplier<ProjectScope> project;
    private ProjectScope displayedProject;

    public FileTreeView(Supplier<ProjectScope> project, Consumer<NavigationTarget> navigator) {
        super();
        this.project = project;
        Objects.requireNonNull(navigator, "navigator");

        this.tree = new LazyFileJTree();
        ContextMenus.installTree(this.tree, path -> createContextMenu(path != null && path.getLastPathComponent() instanceof LazyTreeNode node
                ? node.selectedItem() : null));

        this.tree.addMouseDoubleClickListener((node, item) -> openItem(item, navigator));
        this.tree.getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "openSelectedFile");
        this.tree.getActionMap().put("openSelectedFile", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                if (tree.getSelectionPath() == null
                        || !(tree.getSelectionPath().getLastPathComponent() instanceof LazyTreeNode node)) {
                    return;
                }
                openItem(node.getUserObject(), navigator);
            }
        });

        this.tree.setItemFactory(new FileTreeItemFactory() {
            @Override
            public FileSystemFileItem createFileSystemFileItem(Path path) {
                var item = super.createFileSystemFileItem(path);

                var fileName = item.getName();
                if (fileName.toLowerCase(Locale.ROOT).endsWith(".java")) {
                    var splitIndex = fileName.lastIndexOf('.', fileName.length() - ".java".length() - 1);
                    if (splitIndex != -1) {
                        item.setPresentation(new PrimarySecondaryText(
                                fileName.substring(splitIndex + 1),
                                fileName.substring(0, splitIndex)
                        ));
                    }
                }

                item.setIcon(FileTreeIcons.forFileName(fileName));
                return item;
            }
        });

        setViewportView(this.tree);
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 3));
    }

    private void openItem(
            TreeItem item,
            Consumer<NavigationTarget> navigator
    ) {
        if (item instanceof NavigableTreeItem navigable) {
            navigator.accept(navigable.navigationTarget());
            return;
        }
        if (item.isDirectory()) {
            return;
        }

        if (item instanceof DecompiledSourcesTreeItem.SourceItem source) {
            navigator.accept(new NavigationTarget.RuntimeClass(source.binaryName()));
        } else if (item instanceof FileSystemFileItem fileItem) {
            navigator.accept(new NavigationTarget.LocalFile(fileItem.getPath()));
        } else if (item instanceof ZipFileRootItem.Entry entry) {
            String entryPath = entry.getEntryPath();
            if (entryPath.toLowerCase(Locale.ROOT).endsWith(".class")) {
                navigator.accept(new NavigationTarget.RuntimeClass(
                        entryPath.substring(0, entryPath.length() - 6).replace('/', '.')
                ));
            } else {
                navigator.accept(new NavigationTarget.ArchiveEntry(
                        entry.getArchivePath(),
                        entry.getEntryPath()
                ));
            }
        } else if (item instanceof RuntimeSourceTreeItem.RuntimeFileEntry runtimeFile) {
            String binaryName = runtimeFile.binaryName();
            navigator.accept(binaryName == null
                    ? new NavigationTarget.LocalFile(runtimeFile.path())
                    : new NavigationTarget.RuntimeClass(binaryName));
        }
    }

    JPopupMenu createContextMenu(TreeItem item) {
        JPopupMenu menu = new JPopupMenu();
        if (item == null) return menu;
        String reference = reference(item);
        String location = location(item);
        Path path = item instanceof FileSystemFileItem file ? file.getPath()
                : item instanceof FileSystemDirectoryItem folder ? folder.getPath() : null;
        if (fileActions != null && fileActions.managed(path)) {
            fileActions.addMenu(menu, path, item.isDirectory());
            return menu;
        }
        if (reference != null) menu.add(ContextMenus.defaultCopy(ContextMenus.copyAction("Copy reference", reference)));
        if (location != null && !location.isBlank()) {
            Action copyPath = ContextMenus.copyAction("Copy path", location);
            if (reference == null) ContextMenus.defaultCopy(copyPath);
            menu.add(copyPath);
        }
        return menu;
    }

    private static String reference(TreeItem item) {
        if (item instanceof DecompiledSourcesTreeItem.SourceItem source) return source.binaryName();
        if (item instanceof RuntimeSourceTreeItem.RuntimeFileEntry file) return file.binaryName();
        if (item instanceof ZipFileRootItem.Entry entry && entry.getEntryPath().endsWith(".class")) {
            return entry.getEntryPath().substring(0, entry.getEntryPath().length() - 6).replace('/', '.');
        }
        return null;
    }

    private static String location(TreeItem item) {
        return item.location();
    }

    private RuntimeSourceCatalog sourceCatalog() {
        var scope = project.get();
        return scope == null ? RuntimeSourceCatalog.empty() : scope.sources();
    }

    public void reloadProfile() {
        var scope = project.get();
        if (scope == null) {
            this.tree.setRootNodes();
            this.displayedProject = null;
            return;
        }
        var binding = scope.runtime();
        RuntimeSourceCatalog catalog = scope.sources();
        List<DirectoryTreeItem> rootItems = new ArrayList<>();
        if (Files.isDirectory(scope.paths().scripts())) {
            var scripts = this.tree.getItemFactory().createFileSystemDirectoryItem(scope.paths().scripts(), true);
            scripts.setIcon(FileTreeIcons.forRootDirectory("scripts"));
            rootItems.add(scripts);
        }
        var mods = new ModTreeItems.Root(() -> new ModTreeItems.Snapshot(scope.catalog().state(), scope.sources()));
        if (!catalog.modules().isEmpty() || scope.catalog().index().isPresent()) {
            rootItems.add(mods);
        }
        if (binding != null && !catalog.modules().isEmpty()) {
            rootItems.add(new DecompiledSourcesTreeItem(this.tree, binding.decompiler()));
        }

        if (!catalog.modules().isEmpty()) {
            var runtime = new DirectoryTreeItem("runtime") {
                {
                    setPresentation(PrimarySecondaryText.primary(binding != null && binding.snapshot().isRuntime() ? "Runtime" : "Sources"));
                }

                @Override
                public List<TreeItem> loadChildren() {
                    return runtimeItems(catalog);
                }
            };
            runtime.setIcon(Icons.LIBRARY);
            rootItems.add(runtime);
        }
        var roots = rootItems.toArray(DirectoryTreeItem[]::new);
        if (this.displayedProject == scope) this.tree.refreshRootNodes(roots);
        else this.tree.setRootNodes(roots);
        this.displayedProject = scope;
    }

    static List<TreeItem> runtimeItems(RuntimeSourceCatalog catalog) {
        List<TreeItem> modules = new ArrayList<>();
        catalog.modules().stream()
                .filter(module -> module.kind() == RuntimeInventory.ModuleKind.PLATFORM
                        || module.kind() == RuntimeInventory.ModuleKind.MOD)
                .map(module -> new RuntimeModuleTreeItem(
                        module,
                        catalog.sourcesForModule(module.id())
                ))
                .forEach(modules::add);
        List<RuntimeInventory.RuntimeModule> libraries = catalog.modules(RuntimeInventory.ModuleKind.LIBRARY);
        if (!libraries.isEmpty()) {
            modules.add(new RuntimeLibrariesTreeItem(catalog, libraries));
        }
        catalog.modules(RuntimeInventory.ModuleKind.JAVA_RUNTIME).stream()
                .map(module -> new RuntimeModuleTreeItem(
                        module,
                        catalog.sourcesForModule(module.id())
                ))
                .forEach(modules::add);
        return List.copyOf(modules);
    }

    public CompletableFuture<Boolean> revealRuntimePath(
            String ownerClassName,
            String entryPath,
            RuntimeSnapshotBytecodeSource.Source source
    ) {
        if (entryPath == null || entryPath.isBlank()) {
            throw new IllegalArgumentException("A runtime path must not be blank");
        }
        RuntimeSourceCatalog catalog = sourceCatalog();
        List<String> path = new ArrayList<>();
        if (catalog.sourcesForModule(source.module().id()).size() > 1) {
            path.add(RuntimeSourceTreeItem.nodeName(source));
        }
        if (source.logicalUri().equals("jrt:/")) {
            String module = findJrtModule(ownerClassName);
            if (module == null) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "Unable to locate " + ownerClassName + " in the Java runtime image"
                ));
            }
            path.add(module);
        }
        path.addAll(List.of(entryPath.split("/")));
        return revealRuntimeDirectory(source.module(), path);
    }

    /** Selects a mod's node, or the group of the requested tab, under Mods in the Modpack tree. */
    public CompletableFuture<Boolean> revealModPage(NavigationTarget.ModPage page) {
        List<String> path = new ArrayList<>(List.of(ModTreeItems.MODS));
        var scope = project.get();
        var index = scope == null ? null : scope.catalog().index().orElse(null);
        if (index != null && index.mod(page.modId()).isEmpty() && index.otherNamespaces().contains(page.modId())) {
            path.add(ModTreeItems.OTHER_NAMESPACES);
        }
        path.add(page.modId());
        if (page.tab() != ModTab.OVERVIEW) {
            path.add(ModTreeItems.groupName(page.tab()));
        }
        return this.tree.revealItemPath(ModTreeItems.ROOT, path);
    }

    /** Selects the Configuration row of the Modpack tree. */
    public CompletableFuture<Boolean> revealPackConfiguration() {
        return this.tree.revealItemPath(ModTreeItems.ROOT, List.of(ModTreeItems.CONFIGURATION));
    }

    /** Selects a runtime module's node in the Runtime tree. */
    public CompletableFuture<Boolean> revealRuntimeModule(String moduleId) {
        return sourceCatalog().modules().stream()
                .filter(module -> module.id().equals(moduleId))
                .findFirst()
                .map(module -> revealRuntimeDirectory(module, List.of()))
                .orElseGet(() -> CompletableFuture.completedFuture(false));
    }

    public CompletableFuture<Boolean> revealLocalPath(Path directory) {
        Path target = directory.toAbsolutePath().normalize();
        for (Path root : List.of(
                project.get().paths().scripts()
        )) {
            if (!target.startsWith(root)) {
                continue;
            }
            List<String> relative = new ArrayList<>();
            if (!target.equals(root)) {
                for (Path segment : root.relativize(target)) relative.add(segment.toString());
            }
            return this.tree.revealItemPath(root.getFileName().toString(), relative);
        }
        return CompletableFuture.completedFuture(false);
    }

    public CompletableFuture<Boolean> revealArchivePath(Path archive, String entryName) {
        Path normalizedArchive = archive.toAbsolutePath().normalize();
        RuntimeSourceCatalog catalog = sourceCatalog();
        for (var module : catalog.modules()) {
            List<RuntimeSnapshotBytecodeSource.Source> sources = catalog.sourcesForModule(module.id());
            for (RuntimeSnapshotBytecodeSource.Source source : sources) {
                if (!source.path().equals(normalizedArchive)) {
                    continue;
                }
                List<String> path = new ArrayList<>();
                if (sources.size() > 1) {
                    path.add(RuntimeSourceTreeItem.nodeName(source));
                }
                if (!entryName.isBlank()) {
                    path.addAll(List.of(entryName.split("/")));
                }
                return revealRuntimeDirectory(module, path);
            }
        }
        return CompletableFuture.completedFuture(false);
    }

    private CompletableFuture<Boolean> revealRuntimeDirectory(
            RuntimeInventory.RuntimeModule module,
            List<String> directorySegments
    ) {
        return this.tree.revealItemPath("runtime", runtimeDirectoryPath(module, directorySegments));
    }

    static List<String> runtimeDirectoryPath(
            RuntimeInventory.RuntimeModule module,
            List<String> directorySegments
    ) {
        List<String> path = new ArrayList<>();
        if (module.kind() == RuntimeInventory.ModuleKind.LIBRARY) {
            path.add(RuntimeLibrariesTreeItem.NODE_NAME);
        }
        path.add(RuntimeModuleTreeItem.nodeName(module));
        path.addAll(directorySegments);
        return List.copyOf(path);
    }

    private static String findJrtModule(String ownerClassName) {
        Path modules = FileSystems.getFileSystem(URI.create("jrt:/")).getPath("/modules");
        String resource = ownerClassName.replace('.', '/') + ".class";
        try (var candidates = Files.list(modules)) {
            return candidates.filter(module -> Files.isRegularFile(module.resolve(resource)))
                    .map(module -> module.getFileName().toString())
                    .findFirst()
                    .orElse(null);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to browse the Java runtime image", exception);
        }
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (getViewport() != null) {
            Color background = UIManager.getColor("ToolWindow.background");
            if (background != null) {
                getViewport().setBackground(background);
            }
        }
    }
}
