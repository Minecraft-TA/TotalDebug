package com.github.minecraft_ta.totaldebug.client.companion;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

final class RuntimeInventory {
    static final int FORMAT_VERSION = 4;
    static final String FILE_NAME = "runtime-inventory.properties";

    enum SourceKind {
        ARCHIVE,
        DIRECTORY
    }

    enum ModuleKind {
        PLATFORM,
        MOD,
        LIBRARY,
        JAVA_RUNTIME
    }

    record RuntimeModule(String id, String displayName, ModuleKind kind) {
        RuntimeModule {
            id = requireText(id, "module id");
            displayName = requireText(displayName, "module display name");
            Objects.requireNonNull(kind, "module kind");
        }
    }

    record Source(SourceKind kind, Path path, String logicalUri, RuntimeModule module) {
        Source {
            Objects.requireNonNull(kind, "kind");
            path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
            if (path.getFileSystem() != FileSystems.getDefault()) {
                throw new IllegalArgumentException("Published runtime source is not on the default filesystem: " + path);
            }
            if (Objects.requireNonNull(logicalUri, "logicalUri").isBlank()) {
                throw new IllegalArgumentException("Runtime source logical URI is blank");
            }
            Objects.requireNonNull(module, "module");
        }
    }

    private final String id;
    private final String javaRuntimeVersion;
    private final String javaHome;
    private final boolean production;
    private final List<Source> sources;

    RuntimeInventory(
            String id,
            String javaRuntimeVersion,
            String javaHome,
            boolean production,
            List<Source> sources
    ) {
        this.id = requireText(id, "id");
        this.javaRuntimeVersion = requireText(javaRuntimeVersion, "javaRuntimeVersion");
        this.javaHome = requireText(javaHome, "javaHome");
        this.production = production;
        this.sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        if (this.sources.isEmpty()) {
            throw new IllegalArgumentException("Runtime inventory has no sources");
        }
    }

    String id() {
        return this.id;
    }

    List<Source> sources() {
        return this.sources;
    }

    void write(Path file) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("format", Integer.toString(FORMAT_VERSION));
        properties.setProperty("inventory.id", this.id);
        properties.setProperty("java.runtime.version", this.javaRuntimeVersion);
        properties.setProperty("java.home", this.javaHome);
        properties.setProperty("production", Boolean.toString(this.production));
        properties.setProperty("source.count", Integer.toString(this.sources.size()));
        for (int index = 0; index < this.sources.size(); index++) {
            Source source = this.sources.get(index);
            String prefix = "source." + index + ".";
            properties.setProperty(prefix + "kind", source.kind().name());
            properties.setProperty(prefix + "path", source.path().toUri().toASCIIString());
            properties.setProperty(prefix + "logical", source.logicalUri());
            properties.setProperty(prefix + "module.id", source.module().id());
            properties.setProperty(prefix + "module.name", source.module().displayName());
            properties.setProperty(prefix + "module.kind", source.module().kind().name());
        }
        Path parent = Objects.requireNonNull(file.toAbsolutePath().normalize().getParent(), "Inventory has no parent");
        Files.createDirectories(parent);
        try (OutputStream output = Files.newOutputStream(file)) {
            properties.store(output, "TotalDebug runtime inventory");
        }
    }

    static RuntimeInventory read(Path file) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        }
        try {
            int format = Integer.parseInt(required(properties, "format"));
            if (format != FORMAT_VERSION) {
                throw new IllegalArgumentException("Unsupported runtime inventory format " + format);
            }
            int sourceCount = Integer.parseInt(required(properties, "source.count"));
            List<Source> sources = new ArrayList<>(sourceCount);
            for (int index = 0; index < sourceCount; index++) {
                String prefix = "source." + index + ".";
                SourceKind kind = SourceKind.valueOf(required(properties, prefix + "kind"));
                URI uri = URI.create(required(properties, prefix + "path"));
                if (!"file".equalsIgnoreCase(uri.getScheme())) {
                    throw new IllegalArgumentException("Runtime source does not use the file scheme: " + uri);
                }
                Path path = Path.of(uri);
                if (kind == SourceKind.ARCHIVE ? !Files.isRegularFile(path) : !Files.isDirectory(path)) {
                    throw new IllegalArgumentException("Runtime source is unavailable: " + path);
                }
                sources.add(new Source(
                        kind,
                        path,
                        required(properties, prefix + "logical"),
                        new RuntimeModule(
                                required(properties, prefix + "module.id"),
                                required(properties, prefix + "module.name"),
                                ModuleKind.valueOf(required(properties, prefix + "module.kind"))
                        )
                ));
            }
            return new RuntimeInventory(
                    required(properties, "inventory.id"),
                    required(properties, "java.runtime.version"),
                    required(properties, "java.home"),
                    Boolean.parseBoolean(required(properties, "production")),
                    sources
            );
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid runtime inventory " + file, exception);
        }
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing runtime inventory field " + key);
        }
        return value;
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
        return value;
    }
}
