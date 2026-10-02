package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CurrentWorld;
import com.github.minecraft_ta.totalDebugCompanion.catalog.PackCatalogService;
import com.github.minecraft_ta.totalDebugCompanion.catalog.WorldReading;
import com.github.minecraft_ta.totalDebugCompanion.decompile.CompanionDecompilationService;
import com.github.minecraft_ta.totalDebugCompanion.navigation.ModTab;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.navigation.WorldTab;
import com.github.minecraft_ta.totalDebugCompanion.pack.PackResources;
import com.github.minecraft_ta.totalDebugCompanion.project.CurrentProject;
import com.github.minecraft_ta.totalDebugCompanion.project.InstanceFolders;
import com.github.minecraft_ta.totalDebugCompanion.project.ProjectScope;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeBinding;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeSourceCatalog;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totalDebugCompanion.ui.ContextMenus;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.PageLoader;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.*;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryText;
import com.github.minecraft_ta.totalDebugCompanion.util.WindowFocus;
import com.github.minecraft_ta.totalDebugCompanion.util.Workers;
import com.github.minecraft_ta.totaldebug.protocol.message.PackStackPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.PlayingPayload;
import com.github.minecraft_ta.totaldebug.storage.RuntimeInventory;

import javax.swing.*;
import java.awt.Color;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The Project tree (docs/PROJECT_TREE.md). Its roots are computed on the Swing thread from what the current project's
 * owners published ({@link #syncRoots}), with no file read of the tree's own; each root is built from a source, and only
 * a root whose source differs from the one shown is built again and loads its rows again.
 */
public class FileTreeView extends JScrollPane {
    private static final String SCRIPTS = "scripts";
    private static final String DECOMPILED = "decompiled-files";
    private static final String RUNTIME = "runtime";

    /** A root shown: what it was built from, and its item. */
    private record Shown(Object source, DirectoryTreeItem item) {
    }

    /** The Modpack root's source: the catalog's index and the runtime's sources by identity, the count, the logs. */
    private record ModpackSource(ModTreeItems.Same index, ModTreeItems.Same sources, int changes, boolean logs) {
    }

    /** The Runtime root's source: the runtime's sources by identity, and whether they are the game's. */
    private record RuntimeSource(ModTreeItems.Same sources, boolean runtime) {
    }

    private final LazyFileJTree tree;
    private final CurrentProject currentProject;
    private final List<Runnable> stopFollowing;
    private final PageLoader<Void> returns;
    private ScriptFileActions fileActions;
    private ProjectScope shownProject;
    private final Map<String, Shown> shown = new LinkedHashMap<>();
    /** Whether the tree was disposed with its window; a computation queued before then shows nothing. */
    private boolean closed;

    LazyFileJTree tree() { return tree; }
    public void setFileActions(ScriptFileActions actions) { fileActions = actions; }

    public FileTreeView(CurrentProject currentProject, Consumer<NavigationTarget> navigator) {
        super();
        this.currentProject = Objects.requireNonNull(currentProject, "currentProject");
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

        // What the roots are computed from: the current project, and its owners' signals.
        this.stopFollowing = List.of(
                currentProject.changed().subscribe(() -> SwingUtilities.invokeLater(this::syncRoots)),
                currentProject.follows(scope -> scope.catalog().changed(), this::syncRoots),
                currentProject.follows(scope -> scope.changes().changed(), this::syncRoots),
                currentProject.follows(scope -> scope.world().changed(), this::syncRoots),
                currentProject.follows(scope -> scope.location().playingChanged(), this::syncRoots),
                currentProject.follows(scope -> scope.packs().changed(ChangeRecord.PackSide.DATA), this::syncRoots),
                currentProject.follows(scope -> scope.folders().changed(), this::syncRoots));
        // Another program may have changed the loaded Scripts folders while the user was away.
        this.returns = PageLoader.redraws(this).updates(WindowFocus.returned(), this::refreshScripts);
        syncRoots();
    }

    public void dispose() {
        this.closed = true;
        this.stopFollowing.forEach(Runnable::run);
        this.returns.dispose();
        this.tree.setRootNodes();
        this.shown.clear();
    }

    /**
     * Computes the roots from what the current project's owners published, on the Swing thread and without reading a
     * file, and shows them: roots of another project replace every root; otherwise only a root whose source differs
     * from the one shown is built again, and loads its rows again as far as its source says. Cheap, so it runs whenever
     * something may have changed; one that finds nothing different changes nothing.
     */
    public void syncRoots() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("The roots are computed on the Swing thread");
        if (this.closed) return;
        ProjectScope scope = this.currentProject.scope();
        if (scope == null) {
            this.tree.setRootNodes();
            this.shown.clear();
            this.shownProject = null;
            return;
        }
        // Every root is built from what this one computation read; nothing below reads an owner again.
        PackCatalogService.State catalog = scope.catalog().state();
        Map<String, Object> sources = sources(scope, catalog);
        if (scope != this.shownProject) {
            this.shown.clear();
            List<DirectoryTreeItem> items = new ArrayList<>();
            sources.forEach((name, source) -> {
                DirectoryTreeItem item = build(scope, name, source, catalog);
                this.shown.put(name, new Shown(source, item));
                items.add(item);
            });
            this.tree.setRootNodes(items.toArray(DirectoryTreeItem[]::new));
            this.shownProject = scope;
            return;
        }
        Map<String, LazyFileJTree.Reload> reloads = new HashMap<>();
        Map<String, Shown> next = new LinkedHashMap<>();
        sources.forEach((name, source) -> {
            Shown before = this.shown.get(name);
            if (before != null && before.source().equals(source)) {
                if (before.item() instanceof ModTreeItems.Root modpack) modpack.showState(catalog);
                next.put(name, before);
                return;
            }
            next.put(name, new Shown(source, build(scope, name, source, catalog)));
            if (before != null) reloads.put(name, reload(name, before.source(), source));
        });
        this.shown.clear();
        this.shown.putAll(next);
        this.tree.updateRoots(next.values().stream().map(Shown::item).toList(), reloads);
    }

    /**
     * Each root that exists, in its place, with what it is built from, given the catalog's {@code state}. Only published
     * values; no file is read.
     */
    private static Map<String, Object> sources(ProjectScope scope, PackCatalogService.State state) {
        Map<String, Object> sources = new LinkedHashMap<>();
        InstanceFolders.Folders folders = scope.folders().published();
        RuntimeBinding binding = scope.runtime();
        // The binding's own sources, so that the decompiler and the Runtime rows come from one binding.
        RuntimeSourceCatalog catalog = binding == null ? scope.sources() : binding.sources();
        int changes = scope.changes().count();
        if (folders.scripts()) sources.put(SCRIPTS, Boolean.TRUE);
        var index = Optional.ofNullable(PackCatalogService.shown(state));
        if (!catalog.modules().isEmpty() || index.isPresent() || changes > 0 || folders.logs()) {
            sources.put(ModTreeItems.ROOT, new ModpackSource(new ModTreeItems.Same(index.orElse(null)),
                    new ModTreeItems.Same(catalog), changes, folders.logs()));
        }
        PlayingPayload playing = scope.location().playing();
        // A server's world is on the server, so an instance without worlds of its own has one while it plays there.
        if (playing instanceof PlayingPayload.Multiplayer server) {
            PackStackPayload datapacks = scope.packs().datapacks();
            int named = datapacks == null || !server.totalDebug() ? 0 : PackResources.serverDatapackCount(datapacks);
            sources.put(WorldTreeItems.ROOT, new WorldTreeItems.Rows(0, named));
        } else if (folders.saves()) {
            CurrentWorld.Saved saved = scope.world().published().map(WorldReading.World::saved).orElse(null);
            sources.put(WorldTreeItems.ROOT, saved == null ? new WorldTreeItems.Rows(0, 0)
                    : new WorldTreeItems.Rows(saved.gameRules().size(), saved.datapacks().size()));
        }
        if (binding != null && !catalog.modules().isEmpty()) sources.put(DECOMPILED, new ModTreeItems.Same(binding.decompiler()));
        if (!catalog.modules().isEmpty()) {
            sources.put(RUNTIME, new RuntimeSource(new ModTreeItems.Same(catalog), binding != null && binding.snapshot().isRuntime()));
        }
        return sources;
    }

    /** How far a root whose source changed from {@code before} to {@code now} loads its rows again. */
    private static LazyFileJTree.Reload reload(String name, Object before, Object now) {
        if (before instanceof ModpackSource was && now instanceof ModpackSource is
                && was.index().equals(is.index()) && was.sources().equals(is.sources())) {
            // Only the count of changes or the logs differ: the root's own rows.
            return LazyFileJTree.Reload.ROWS;
        }
        return WorldTreeItems.ROOT.equals(name) ? LazyFileJTree.Reload.ROWS : LazyFileJTree.Reload.BELOW;
    }

    /** The root {@code name} built from {@code source}, with the catalog's {@code state} its source was computed from. */
    private DirectoryTreeItem build(ProjectScope scope, String name, Object source, PackCatalogService.State state) {
        return switch (name) {
            case SCRIPTS -> {
                var scripts = this.tree.getItemFactory().createRootDirectoryItem(scope.paths().scripts());
                scripts.setIcon(FileTreeIcons.forRootDirectory(SCRIPTS));
                yield scripts;
            }
            case ModTreeItems.ROOT -> {
                ModpackSource modpack = (ModpackSource) source;
                yield new ModTreeItems.Root(new ModTreeItems.Snapshot(state,
                        (RuntimeSourceCatalog) modpack.sources().value(), modpack.changes(), modpack.logs()));
            }
            case WorldTreeItems.ROOT -> new WorldTreeItems.Root((WorldTreeItems.Rows) source);
            // The decompiler the roots were computed with: the binding may have been replaced since.
            case DECOMPILED -> new DecompiledSourcesTreeItem(this.tree, (CompanionDecompilationService) ((ModTreeItems.Same) source).value());
            case RUNTIME -> runtime((RuntimeSource) source);
            default -> throw new IllegalArgumentException("No root " + name);
        };
    }

    private static DirectoryTreeItem runtime(RuntimeSource source) {
        RuntimeSourceCatalog catalog = (RuntimeSourceCatalog) source.sources().value();
        var runtime = new DirectoryTreeItem(RUNTIME) {
            @Override
            public List<TreeItem> loadChildren() {
                return runtimeItems(catalog);
            }
        };
        runtime.setPresentation(PrimarySecondaryText.primary(source.runtime() ? "Runtime" : "Sources"));
        runtime.setIcon(Icons.LIBRARY);
        return runtime;
    }

    /**
     * Lists the loaded folders of Scripts again, which an editor may have changed while the user was away, or shows the
     * Scripts root where the folder was made meanwhile. Swing thread only.
     */
    private CompletableFuture<Void> refreshScripts() {
        ProjectScope scope = this.currentProject.scope();
        if (this.closed || scope == null) return CompletableFuture.completedFuture(null);
        if (!this.tree.hasRootNode(SCRIPTS)) return showScriptsRoot(scope);
        return this.tree.reloadRoot(SCRIPTS, LazyFileJTree.Reload.BELOW);
    }

    /** Has the instance's folders read again, as after Companion made the scripts folder, and shows the roots then. */
    private CompletableFuture<Void> showScriptsRoot(ProjectScope scope) {
        return scope.folders().refresh().thenAcceptAsync(folders -> {
            if (this.currentProject.scope() == scope) syncRoots();
        }, SwingUtilities::invokeLater);
    }

    /** Lists {@code parent} again, as after a script action changed it, showing the Scripts root if the folder is new. */
    public CompletableFuture<Void> refreshDirectory(Path parent) {
        if (parent == null) return CompletableFuture.completedFuture(null);
        return CompletableFuture.<Void>completedFuture(null).thenComposeAsync(ignored -> {
            ProjectScope scope = this.currentProject.scope();
            if (this.closed || scope == null) return CompletableFuture.completedFuture(null);
            CompletableFuture<Void> roots = this.tree.hasRootNode(SCRIPTS) ? CompletableFuture.completedFuture(null) : showScriptsRoot(scope);
            return roots.thenCompose(shown -> this.tree.refreshDirectory(parent));
        }, SwingUtilities::invokeLater);
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
        if (reference != null) menu.add(ContextMenus.defaultCopy(ContextMenus.copyAction("Copy Reference", reference)));
        if (location != null && !location.isBlank()) {
            Action copyPath = ContextMenus.copyAction("Copy Path", location);
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
        var scope = this.currentProject.scope();
        return scope == null ? RuntimeSourceCatalog.empty() : scope.sources();
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

    /**
     * Selects a runtime class's row in the Runtime tree, if {@code stillWanted} still holds in the step that selects it.
     * A JDK class's module is found on file work first.
     */
    public CompletableFuture<Boolean> revealRuntimePath(
            String ownerClassName,
            String entryPath,
            RuntimeSnapshotBytecodeSource.Source source,
            BooleanSupplier stillWanted
    ) {
        if (entryPath == null || entryPath.isBlank()) {
            throw new IllegalArgumentException("A runtime path must not be blank");
        }
        RuntimeSourceCatalog catalog = sourceCatalog();
        List<String> path = new ArrayList<>();
        if (catalog.sourcesForModule(source.module().id()).size() > 1) {
            path.add(RuntimeSourceTreeItem.nodeName(source));
        }
        CompletableFuture<List<String>> located = source.logicalUri().equals("jrt:/")
                ? CompletableFuture.supplyAsync(() -> findJrtModule(ownerClassName), Workers.files()).thenApply(module -> {
                    if (module == null) {
                        throw new IllegalStateException("Unable to locate " + ownerClassName + " in the Java runtime image");
                    }
                    List<String> inModule = new ArrayList<>(path);
                    inModule.add(module);
                    return inModule;
                })
                : CompletableFuture.completedFuture(path);
        return located.thenComposeAsync(segments -> {
            List<String> full = new ArrayList<>(segments);
            full.addAll(List.of(entryPath.split("/")));
            return revealRuntimeDirectory(source.module(), full, stillWanted);
        }, SwingUtilities::invokeLater);
    }

    /** Selects a mod's node, or the group of the requested tab, under Mods in the Modpack tree. */
    public CompletableFuture<Boolean> revealModPage(NavigationTarget.ModPage page, BooleanSupplier stillWanted) {
        List<String> path = new ArrayList<>(List.of(ModTreeItems.MODS));
        var scope = this.currentProject.scope();
        var index = scope == null ? null : scope.catalog().index().orElse(null);
        if (index != null && index.mod(page.modId()).isEmpty() && index.otherNamespaces().contains(page.modId())) {
            path.add(ModTreeItems.OTHER_NAMESPACES);
        }
        path.add(page.modId());
        boolean singleKind = page.tab() == ModTab.CONTENT && index != null && index.content(page.modId()).size() == 1;
        if (page.tab() != ModTab.OVERVIEW && !singleKind) {
            path.add(ModTreeItems.groupName(page.tab()));
        }
        if (page.tab() == ModTab.CONTENT && (singleKind || !page.section().isEmpty())) {
            path.add(singleKind ? index.content(page.modId()).keySet().iterator().next() : page.section());
        }
        return reveal(ModTreeItems.ROOT, path, stillWanted);
    }

    /** Selects the Configuration row of the Modpack tree. */
    public CompletableFuture<Boolean> revealPackConfiguration(BooleanSupplier stillWanted) {
        return reveal(ModTreeItems.ROOT, List.of(ModTreeItems.CONFIGURATION), stillWanted);
    }

    /** Selects the row of a kind under Content in the Modpack tree, or Content itself for an empty registry. */
    public CompletableFuture<Boolean> revealContent(String registry, BooleanSupplier stillWanted) {
        return reveal(ModTreeItems.ROOT,
                registry.isEmpty() ? List.of(ModTreeItems.CONTENT) : List.of(ModTreeItems.CONTENT, registry), stillWanted);
    }

    /** Selects the Resources row of the Modpack tree. */
    public CompletableFuture<Boolean> revealPackResources(BooleanSupplier stillWanted) {
        return reveal(ModTreeItems.ROOT, List.of(ModTreeItems.RESOURCES), stillWanted);
    }

    /** Selects the Logs row of the Modpack tree. */
    public CompletableFuture<Boolean> revealLogs(BooleanSupplier stillWanted) {
        return reveal(ModTreeItems.ROOT, List.of(ModTreeItems.LOGS), stillWanted);
    }

    /** Selects the Key bindings row of the Modpack tree. */
    public CompletableFuture<Boolean> revealKeyBindings(BooleanSupplier stillWanted) {
        return reveal(ModTreeItems.ROOT, List.of(ModTreeItems.KEY_BINDINGS), stillWanted);
    }

    /**
     * Selects the row of one of the World page's tabs, or the World root for the Overview and for a tab the tree has no
     * row for, since the tree read the world before the page did.
     */
    public CompletableFuture<Boolean> revealWorld(WorldTab tab, BooleanSupplier stillWanted) {
        if (tab == WorldTab.OVERVIEW) return reveal(WorldTreeItems.ROOT, List.of(), stillWanted);
        return reveal(WorldTreeItems.ROOT, List.of(WorldTreeItems.rowName(tab)), stillWanted).thenCompose(revealed ->
                revealed ? CompletableFuture.completedFuture(true) : reveal(WorldTreeItems.ROOT, List.of(), stillWanted));
    }

    /** Selects the Changes row of the Modpack tree. */
    public CompletableFuture<Boolean> revealChanges(BooleanSupplier stillWanted) {
        return reveal(ModTreeItems.ROOT, List.of(ModTreeItems.CHANGES), stillWanted);
    }

    /** Selects a runtime module's node in the Runtime tree. */
    public CompletableFuture<Boolean> revealRuntimeModule(String moduleId, BooleanSupplier stillWanted) {
        return sourceCatalog().modules().stream()
                .filter(module -> module.id().equals(moduleId))
                .findFirst()
                .map(module -> revealRuntimeDirectory(module, List.of(), stillWanted))
                .orElseGet(() -> CompletableFuture.completedFuture(false));
    }

    /**
     * Selects a folder or a script under Scripts. A Scripts root not shown yet has the instance's folders read again
     * first; a script not found lists the loaded Scripts folders again once and tries again, as one a tool made beside
     * the tree's own actions.
     */
    public CompletableFuture<Boolean> revealLocalPath(Path directory, BooleanSupplier stillWanted) {
        ProjectScope scope = this.currentProject.scope();
        if (scope == null) return CompletableFuture.completedFuture(false);
        Path target = directory.toAbsolutePath().normalize();
        Path root = scope.paths().scripts();
        if (!target.startsWith(root)) return CompletableFuture.completedFuture(false);
        List<String> relative = new ArrayList<>();
        if (!target.equals(root)) {
            for (Path segment : root.relativize(target)) relative.add(segment.toString());
        }
        return CompletableFuture.<Void>completedFuture(null).thenComposeAsync(ignored -> {
            syncRoots();
            return this.tree.hasRootNode(SCRIPTS) ? CompletableFuture.<Void>completedFuture(null) : showScriptsRoot(scope);
        }, SwingUtilities::invokeLater).thenComposeAsync(shown -> this.tree.revealItemPath(SCRIPTS, relative, stillWanted)
                .thenComposeAsync(revealed -> revealed
                        ? CompletableFuture.completedFuture(true)
                        : refreshScripts().handle((done, failure) -> null)
                                .thenCompose(done -> this.tree.revealItemPath(SCRIPTS, relative, stillWanted)),
                        SwingUtilities::invokeLater), SwingUtilities::invokeLater);
    }

    /** Selects an archive's entry in the Runtime tree. */
    public CompletableFuture<Boolean> revealArchivePath(Path archive, String entryName, BooleanSupplier stillWanted) {
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
                return revealRuntimeDirectory(module, path, stillWanted);
            }
        }
        return CompletableFuture.completedFuture(false);
    }

    private CompletableFuture<Boolean> revealRuntimeDirectory(
            RuntimeInventory.RuntimeModule module,
            List<String> directorySegments,
            BooleanSupplier stillWanted
    ) {
        return reveal(RUNTIME, runtimeDirectoryPath(module, directorySegments), stillWanted);
    }

    /** Brings the roots up to date, then reveals the row on {@code path} below the root {@code rootName}. */
    private CompletableFuture<Boolean> reveal(String rootName, List<String> path, BooleanSupplier stillWanted) {
        return CompletableFuture.<Void>completedFuture(null).thenComposeAsync(ignored -> {
            syncRoots();
            return this.tree.revealItemPath(rootName, path, stillWanted);
        }, SwingUtilities::invokeLater);
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

    /** The JDK module holding {@code ownerClassName}, or null. Lists the runtime image's modules: file work only. */
    private static String findJrtModule(String ownerClassName) {
        Path modules = FileSystems.getFileSystem(URI.create("jrt:/")).getPath("/modules");
        String resource = ownerClassName.replace('.', '/') + ".class";
        try (var candidates = Files.list(modules)) {
            return candidates.filter(module -> Files.isRegularFile(module.resolve(resource)))
                    .map(module -> module.getFileName().toString())
                    .findFirst()
                    .orElse(null);
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to browse the Java runtime image", exception);
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
