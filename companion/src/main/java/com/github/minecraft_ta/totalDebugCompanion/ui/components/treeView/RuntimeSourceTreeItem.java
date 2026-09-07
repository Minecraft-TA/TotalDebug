package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.minecraft_ta.totaldebug.storage.RuntimeInventory;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeSourceCatalog;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.DirectoryTreeItem;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.TreeItem;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryText;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.RuntimeModulePresentation;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

final class RuntimeModuleTreeItem extends DirectoryTreeItem {
    static final int PLATFORM_PRIORITY = 0;
    static final int MOD_PRIORITY = 10;
    static final int LIBRARIES_PRIORITY = 20;
    static final int JAVA_RUNTIME_PRIORITY = 30;

    private final RuntimeInventory.RuntimeModule module;
    private final List<RuntimeSnapshotBytecodeSource.Source> sources;

    RuntimeModuleTreeItem(
            RuntimeInventory.RuntimeModule module,
            List<RuntimeSnapshotBytecodeSource.Source> sources
    ) {
        super(nodeName(module));
        this.module = module;
        this.sources = List.copyOf(sources);
        RuntimeModulePresentation presentation = RuntimeModulePresentation.of(module);
        setPresentation(presentation.text());
        setIcon(Icons.MODULE);
        setSortPriority(switch (module.kind()) {
            case PLATFORM -> PLATFORM_PRIORITY;
            case MOD -> MOD_PRIORITY;
            case LIBRARY -> LIBRARIES_PRIORITY;
            case JAVA_RUNTIME -> JAVA_RUNTIME_PRIORITY;
        });
    }

    static String nodeName(RuntimeInventory.RuntimeModule module) {
        return module.displayName() + " [" + module.id() + ']';
    }

    @Override
    public List<TreeItem> loadChildren() {
        if (this.sources.size() == 1) {
            return new RuntimeSourceTreeItem(this.sources.getFirst()).loadChildren();
        }
        return this.sources.stream().<TreeItem>map(RuntimeSourceTreeItem::new).toList();
    }

    @Override
    public String getTooltip() {
        return RuntimeModulePresentation.of(this.module).tooltip();
    }
}

final class RuntimeLibrariesTreeItem extends DirectoryTreeItem {
    static final String NODE_NAME = "runtime-libraries";

    private final RuntimeSourceCatalog catalog;
    private final List<RuntimeInventory.RuntimeModule> modules;

    RuntimeLibrariesTreeItem(
            RuntimeSourceCatalog catalog,
            List<RuntimeInventory.RuntimeModule> modules
    ) {
        super(NODE_NAME);
        this.catalog = catalog;
        this.modules = List.copyOf(modules);
        String moduleCount = this.modules.size() == 1
                ? "1 module"
                : this.modules.size() + " modules";
        setPresentation(new PrimarySecondaryText("Libraries", moduleCount));
        setIcon(Icons.LIBRARY);
        setSortPriority(RuntimeModuleTreeItem.LIBRARIES_PRIORITY);
    }

    @Override
    public List<TreeItem> loadChildren() {
        return this.modules.stream()
                .<TreeItem>map(module -> new RuntimeModuleTreeItem(
                        module,
                        this.catalog.sourcesForModule(module.id())
                ))
                .toList();
    }
}

final class RuntimeSourceTreeItem extends DirectoryTreeItem {
    private static final URI JRT_URI = URI.create("jrt:/");

    private final RuntimeSnapshotBytecodeSource.Source source;

    RuntimeSourceTreeItem(RuntimeSnapshotBytecodeSource.Source source) {
        super(nodeName(source));
        this.source = source;
        setPresentation(PrimarySecondaryText.primary(displayName(source)));
        setIcon(source.logicalUri().equals("jrt:/") || Files.isDirectory(source.path())
                ? Icons.SOURCE_ROOT
                : Icons.JAR_FILE);
    }

    static String nodeName(RuntimeSnapshotBytecodeSource.Source source) {
        return displayName(source) + " [source " + source.sourceId() + ']';
    }

    @Override
    public List<TreeItem> loadChildren() {
        if (this.source.logicalUri().equals("jrt:/")) {
            Path modules = FileSystems.getFileSystem(JRT_URI).getPath("/modules");
            return directories(modules).stream()
                    .<TreeItem>map(module -> new RuntimeDirectoryEntry(module, module))
                    .toList();
        }
        if (Files.isDirectory(this.source.path())) {
            return new RuntimeDirectoryEntry(this.source.path(), this.source.path()).loadChildren();
        }
        if (Files.isRegularFile(this.source.path())) {
            return new ZipFileRootItem(this.source.path()).loadChildren();
        }
        throw new IllegalStateException("Runtime source cannot be browsed: " + this.source.path());
    }

    @Override
    public String getTooltip() {
        return this.source.logicalUri();
    }

    private static String displayName(RuntimeSnapshotBytecodeSource.Source source) {
        if (source.logicalUri().equals("jrt:/")) {
            return "JDK modules";
        }
        String logical = source.logicalUri();
        int nested = logical.lastIndexOf("!/");
        if (nested >= 0) {
            String entry = logical.substring(nested + 2);
            int separator = entry.lastIndexOf('/');
            return separator < 0 ? entry : entry.substring(separator + 1);
        }
        URI uri = URI.create(logical);
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            Path fileName = Path.of(uri).getFileName();
            if (fileName == null) {
                throw new IllegalArgumentException("Runtime source URI has no file name: " + logical);
            }
            return fileName.toString();
        }
        String path = uri.getPath();
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Runtime source URI has no display path: " + logical);
        }
        int separator = path.lastIndexOf('/');
        return separator < 0 ? path : path.substring(separator + 1);
    }

    private static List<Path> directories(Path directory) {
        try (var children = Files.list(directory)) {
            return children.filter(Files::isDirectory).toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to browse runtime source " + directory, exception);
        }
    }

    static final class RuntimeDirectoryEntry extends DirectoryTreeItem {
        private final Path classRoot;
        private final Path directory;

        RuntimeDirectoryEntry(Path classRoot, Path directory) {
            super(directory.getFileName().toString());
            this.classRoot = classRoot;
            this.directory = directory;
            setIcon(Icons.PACKAGE);
        }

        @Override
        public List<TreeItem> loadChildren() {
            try (var children = Files.list(this.directory)) {
                return children.map(path -> Files.isDirectory(path)
                                ? new RuntimeDirectoryEntry(this.classRoot, path)
                                : new RuntimeFileEntry(this.classRoot, path))
                        .toList();
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to browse runtime directory " + this.directory, exception);
            }
        }

        @Override
        public String getTooltip() {
            return this.directory.toString();
        }
    }

    static final class RuntimeFileEntry extends TreeItem {
        private final Path classRoot;
        private final Path path;

        RuntimeFileEntry(Path classRoot, Path path) {
            super(path.getFileName().toString());
            this.classRoot = classRoot;
            this.path = path;
            setIcon(FileTreeIcons.forFileName(getName()));
        }

        Path path() {
            return this.path;
        }

        String binaryName() {
            String name = getName().toLowerCase(Locale.ROOT);
            if (!name.endsWith(".class") || name.equals("module-info.class")) {
                return null;
            }
            String relative = this.classRoot.relativize(this.path).toString().replace('\\', '/');
            return relative.substring(0, relative.length() - ".class".length()).replace('/', '.');
        }

        @Override
        public String getTooltip() {
            return this.path.toString();
        }
    }
}
