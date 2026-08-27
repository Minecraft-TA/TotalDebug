package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProtocol;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.*;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryText;

import javax.swing.*;
import java.awt.Color;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Optional;
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

        Path modsPath = CompanionApp.getWorkspaceDirectory().resolve("mods");
        var mods = new DirectoryTreeItem("mods") {
            @Override
            public List<TreeItem> loadChildren() {
                if (!Files.isDirectory(modsPath)) {
                    return List.of();
                }
                try (var paths = Files.walk(modsPath, 1)) {
                    return paths.skip(1)
                            .filter(path -> path.getFileName().toString().endsWith(".jar"))
                            .<TreeItem>map(ZipFileRootItem::new)
                            .toList();
                } catch (IOException exception) {
                    throw new RuntimeException(exception);
                }
            }
        };
        mods.setIcon(FileTreeIcons.forRootDirectory("mods"));
        rootItems.add(mods);
        this.tree.setRootNodes(rootItems.toArray(DirectoryTreeItem[]::new));
    }

    /** Reveals every runtime archive containing the requested Java package. */
    public CompletableFuture<Boolean> revealPackage(String packageName, String archiveName) {
        if (packageName == null || packageName.isBlank()) {
            throw new IllegalArgumentException("A package name must not be blank");
        }
        return this.tree.revealDirectoryPath(
                "mods",
                archiveName,
                List.of(packageName.split("\\."))
        );
    }

    public static Optional<String> workspaceArchiveName(RuntimeSnapshotBytecodeSource.Source source) {
        Path modsDirectory = CompanionApp.getWorkspaceDirectory().resolve("mods").toAbsolutePath().normalize();
        Path archive = source.path().toAbsolutePath().normalize();
        if (modsDirectory.equals(archive.getParent())) {
            return Optional.of(archive.getFileName().toString());
        }

        String logicalRoot = source.logicalUri();
        int nestedSeparator = logicalRoot.indexOf("!/");
        if (nestedSeparator >= 0) {
            logicalRoot = logicalRoot.substring(0, nestedSeparator);
        }
        if (!logicalRoot.startsWith("file:")) {
            return Optional.empty();
        }
        Path logicalArchive = Path.of(URI.create(logicalRoot)).toAbsolutePath().normalize();
        return modsDirectory.equals(logicalArchive.getParent())
                ? Optional.of(logicalArchive.getFileName().toString())
                : Optional.empty();
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
