package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeSourceCatalog;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProtocol;
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

        this.tree.addMouseDoubleClickListener((node, item) -> {
            if (item.isDirectory())
                return;

            if (item instanceof FileSystemFileItem fileItem) {
                String lowerName = fileItem.getName().toLowerCase(Locale.ROOT);
                boolean javaFile = lowerName.endsWith(".java");
                if (javaFile
                        && node.getParent().getUserObject().getName().equals("scripts")
                        && CompanionApp.supportsCapability(CompanionProtocol.CAPABILITY_SCRIPT_EXECUTION)) {
                    var name = fileItem.getName().substring(0, fileItem.getName().length() - ".java".length());

                    navigator.accept(new NavigationTarget.LocalFile(fileItem.getPath()));
                } else if (javaFile && fileItem.getPath().getParent().equals(
                        CompanionApp.getRootPath().resolve("decompiled-files")
                )) {
                    String binaryName = fileItem.getName().substring(0, fileItem.getName().length() - ".java".length());
                    navigator.accept(new NavigationTarget.RuntimeClass(binaryName));
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

    public void reloadProfile() {
        if (!CompanionApp.hasProfile()) {
            this.tree.setRootNodes();
            return;
        }

        List<DirectoryTreeItem> rootItems = new ArrayList<>();
        if (CompanionApp.supportsCapability(CompanionProtocol.CAPABILITY_SCRIPT_EXECUTION)) {
            var scripts = this.tree.getItemFactory().createFileSystemDirectoryItem(
                    CompanionApp.getRootPath().resolve("scripts"),
                    true
            );
            scripts.setIcon(FileTreeIcons.forRootDirectory("scripts"));
            rootItems.add(scripts);
        }
        var decompiledFiles = this.tree.getItemFactory().createFileSystemDirectoryItem(
                CompanionApp.getRootPath().resolve("decompiled-files"),
                true
        );
        decompiledFiles.setIcon(FileTreeIcons.forRootDirectory("decompiled-files"));
        rootItems.add(decompiledFiles);

        RuntimeSourceCatalog catalog = CompanionApp.getRuntimeSourceCatalog();
        if (!catalog.modules().isEmpty()) {
            var runtime = new DirectoryTreeItem("runtime") {
                {
                    setPresentation(PrimarySecondaryText.primary("Runtime"));
                }

                @Override
                public List<TreeItem> loadChildren() {
                    return catalog.modules().stream()
                            .<TreeItem>map(module -> new RuntimeModuleTreeItem(
                                    module,
                                    catalog.sourcesForModule(module.id())
                            ))
                            .toList();
                }
            };
            runtime.setIcon(Icons.LIBRARY);
            rootItems.add(runtime);
        }
        this.tree.setRootNodes(rootItems.toArray(DirectoryTreeItem[]::new));
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
        return this.tree.revealDirectoryPath(
                "runtime",
                RuntimeModuleTreeItem.nodeName(source.module()),
                path
        );
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
