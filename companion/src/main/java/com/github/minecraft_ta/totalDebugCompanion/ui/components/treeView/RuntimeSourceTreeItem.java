package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView;

import com.github.minecraft_ta.totalDebugCompanion.ui.Tooltip;
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
        // The path is Companion's cached copy; the logical location names the file the game loaded.
        return Tooltip.of(RuntimeModulePresentation.location(this.source.logicalUri())).html();
    }

    @Override
    public String location() {
        return this.source.logicalUri();
    }

    private static String displayName(RuntimeSnapshotBytecodeSource.Source source) {
        if (source.logicalUri().equals("jrt:/")) {
            return "JDK modules";
        }
        String logical = source.logicalUri();
        // A trailing archive-root delimiter identifies the archive itself, not an empty entry.
        while (logical.endsWith("!/")) logical = logical.substring(0, logical.length() - 2);
        URI uri = URI.create(logical);
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            Path fileName = Path.of(uri).getFileName();
            if (fileName == null) {
                throw new IllegalArgumentException("Runtime source URI has no file name: " + logical);
            }
            return fileName.toString();
        }
        String path = uri.isOpaque() ? uri.getSchemeSpecificPart() : uri.getPath();
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Runtime source URI has no display path: " + logical);
        }
        while (path.endsWith("/") && path.length() > 1) path = path.substring(0, path.length() - 1);
        String name = path.substring(path.lastIndexOf('/') + 1);
        if ("union".equalsIgnoreCase(uri.getScheme()) && !logical.contains("!/")) {
            // Union filesystem instance ids are not part of the backing archive's filename.
            name = name.replaceFirst("#\\d+$", "");
        }
        if (name.isBlank()) throw new IllegalArgumentException("Runtime source URI has no file name: " + source.logicalUri());
        return name;
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
        private boolean empty;

        RuntimeDirectoryEntry(Path classRoot, Path directory) {
            super(directory.getFileName().toString());
            this.classRoot = classRoot;
            this.directory = directory;
            setIcon(resourceDirectory() ? Icons.FOLDER : Icons.PACKAGE);
        }

        private boolean resourceDirectory() {
            if (classRoot.equals(directory)) return false;
            String first = classRoot.relativize(directory).getName(0).toString();
            return first.equalsIgnoreCase("assets") || first.equalsIgnoreCase("data") || first.equalsIgnoreCase("META-INF");
        }

        @Override public String compactSeparator() {
            if (classRoot.equals(directory)) return null;
            return resourceDirectory() ? "/" : ".";
        }

        @Override protected boolean isInitiallyEmpty() { return empty; }
        @Override public Object compactIdentity() throws IOException { return directory.toRealPath(); }

        @Override public DirectoryTreeItem singleDirectoryChild() throws IOException {
            if (!ordinaryDirectory(directory)) return null;
            var entries = firstChildren(directory);
            empty = entries.isEmpty();
            return entries.size() == 1 && ordinaryDirectory(entries.getFirst())
                    ? new RuntimeDirectoryEntry(classRoot, entries.getFirst()) : null;
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
            return Tooltip.of(Tooltip.shortPath(this.directory)).html();
        }

        @Override
        public String location() {
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
            return Tooltip.of(Tooltip.shortPath(this.path)).html();
        }

        @Override
        public String location() {
            return this.path.toString();
        }
    }
}
