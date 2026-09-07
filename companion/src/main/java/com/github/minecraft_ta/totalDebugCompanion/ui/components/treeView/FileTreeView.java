package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.model.ScriptView;
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
import java.util.function.Consumer;

public class FileTreeView extends JScrollPane {
    private final LazyFileJTree tree;

    public FileTreeView(Consumer<NavigationTarget> navigator) {
        super();
        java.util.Objects.requireNonNull(navigator, "navigator");

        this.tree = new LazyFileJTree() {
            @Override
            protected void showPopupMenu(LazyTreeNode node, TreeItem treeItem, int x, int y) {
                if (!(treeItem instanceof FileSystemFileItem))
                    return;

                var popupMenu = new JPopupMenu();
                var deleteItem = popupMenu.add("Delete");
                deleteItem.setIcon(Icons.DELETE);
                deleteItem.addActionListener(event -> deleteSelectedItems());

                popupMenu.show(this, x, y);
            }
        };

        this.tree.addMouseDoubleClickListener((node, item) -> openItem(node, item, navigator));
        this.tree.getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0), "openSelectedFile");
        this.tree.getActionMap().put("openSelectedFile", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                if (tree.getSelectionPath() == null
                        || !(tree.getSelectionPath().getLastPathComponent() instanceof LazyTreeNode node)) {
                    return;
                }
                openItem(node, node.getUserObject(), navigator);
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

    private static void openItem(
            LazyTreeNode node,
            TreeItem item,
            Consumer<NavigationTarget> navigator
    ) {
        if (item.isDirectory()) {
            return;
        }

        if (item instanceof DecompiledSourcesTreeItem.SourceItem source) {
            navigator.accept(new NavigationTarget.RuntimeClass(source.binaryName()));
        } else if (item instanceof FileSystemFileItem fileItem) {
            String lowerName = fileItem.getName().toLowerCase(Locale.ROOT);
            boolean scriptFile = lowerName.endsWith(ScriptView.FILE_EXTENSION);
            if (scriptFile
                    && node.getParent().getUserObject().getName().equals("scripts")
                    && CompanionApp.hasProfile()) {
                navigator.accept(new NavigationTarget.LocalFile(fileItem.getPath()));
            } else {
                navigator.accept(new NavigationTarget.LocalFile(fileItem.getPath()));
            }
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

    public void reloadProfile() {
        if (!CompanionApp.hasProfile()) {
            this.tree.setRootNodes();
            return;
        }

        List<DirectoryTreeItem> rootItems = new ArrayList<>();
        if (CompanionApp.hasProfile()) {
            var scripts = this.tree.getItemFactory().createFileSystemDirectoryItem(
                    CompanionApp.instancePaths().scripts(),
                    true
            );
            scripts.setIcon(FileTreeIcons.forRootDirectory("scripts"));
            rootItems.add(scripts);
        }
        if (!CompanionApp.getRuntimeSourceCatalog().modules().isEmpty()) {
            rootItems.add(new DecompiledSourcesTreeItem(this.tree, CompanionApp.getDecompilationService()));
        }

        RuntimeSourceCatalog catalog = CompanionApp.getRuntimeSourceCatalog();
        if (!catalog.modules().isEmpty()) {
            var runtime = new DirectoryTreeItem("runtime") {
                {
                    setPresentation(PrimarySecondaryText.primary("Runtime"));
                }

                @Override
                public List<TreeItem> loadChildren() {
                    return runtimeItems(catalog);
                }
            };
            runtime.setIcon(Icons.LIBRARY);
            rootItems.add(runtime);
        }
        this.tree.setRootNodes(rootItems.toArray(DirectoryTreeItem[]::new));
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

    public CompletableFuture<Boolean> revealPackage(
            String packageName,
            String ownerClassName,
            RuntimeSnapshotBytecodeSource.Source source
    ) {
        if (packageName == null || packageName.isBlank()) {
            throw new IllegalArgumentException("A package name must not be blank");
        }
        RuntimeSourceCatalog catalog = CompanionApp.getRuntimeSourceCatalog();
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
        path.addAll(List.of(packageName.split("\\.")));
        return revealRuntimeDirectory(source.module(), path);
    }

    public CompletableFuture<Boolean> revealLocalDirectory(Path directory) {
        Path target = directory.toAbsolutePath().normalize();
        for (Path root : List.of(
                CompanionApp.instancePaths().scripts()
        )) {
            if (!target.startsWith(root)) {
                continue;
            }
            List<String> relative = new ArrayList<>();
            for (Path segment : root.relativize(target)) {
                relative.add(segment.toString());
            }
            return this.tree.revealDirectoryPath(root.getFileName().toString(), relative);
        }
        return CompletableFuture.completedFuture(false);
    }

    public CompletableFuture<Boolean> revealArchiveDirectory(Path archive, String entryName) {
        Path normalizedArchive = archive.toAbsolutePath().normalize();
        RuntimeSourceCatalog catalog = CompanionApp.getRuntimeSourceCatalog();
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
        return this.tree.revealDirectoryPath("runtime", runtimeDirectoryPath(module, directorySegments));
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
        } catch (java.io.IOException exception) {
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
