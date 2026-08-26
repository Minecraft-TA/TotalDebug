package com.github.minecraft_ta.totalDebugCompanion.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

public record RuntimeInventory(
        String id,
        String javaRuntimeVersion,
        String javaHome,
        boolean production,
        List<Source> sources
) {
    public static final int FORMAT_VERSION = 2;

    public enum SourceKind {
        ARCHIVE,
        DIRECTORY
    }

    public record RuntimeModule(String id, String displayName) {
        public RuntimeModule {
            if (Objects.requireNonNull(id, "id").isBlank()) {
                throw new IllegalArgumentException("Runtime module id is blank");
            }
            if (Objects.requireNonNull(displayName, "displayName").isBlank()) {
                throw new IllegalArgumentException("Runtime module display name is blank");
            }
        }
    }

    public record Source(SourceKind kind, Path path, String logicalUri, RuntimeModule module) {
        public Source {
            Objects.requireNonNull(kind, "kind");
            path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
            if (Objects.requireNonNull(logicalUri, "logicalUri").isBlank()) {
                throw new IllegalArgumentException("Runtime source logical URI is blank");
            }
            Objects.requireNonNull(module, "module");
        }
    }

    public RuntimeInventory {
        if (Objects.requireNonNull(id, "id").isBlank()) {
            throw new IllegalArgumentException("Runtime inventory id is blank");
        }
        if (Objects.requireNonNull(javaRuntimeVersion, "javaRuntimeVersion").isBlank()) {
            throw new IllegalArgumentException("Java runtime version is blank");
        }
        if (Objects.requireNonNull(javaHome, "javaHome").isBlank()) {
            throw new IllegalArgumentException("Java home is blank");
        }
        sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("Runtime inventory has no sources");
        }
    }

    public static RuntimeInventory read(Path file) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        } catch (IOException exception) {
            throw new IOException("Unable to read runtime inventory " + file, exception);
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
                    throw new IllegalArgumentException("Runtime source is not a file URI: " + uri);
                }
                Path path = Path.of(uri).toAbsolutePath().normalize();
                if (kind == SourceKind.ARCHIVE ? !Files.isRegularFile(path) : !Files.isDirectory(path)) {
                    throw new IllegalArgumentException("Runtime source is unavailable: " + path);
                }
                sources.add(new Source(
                        kind,
                        path,
                        required(properties, prefix + "logical"),
                        new RuntimeModule(
                                required(properties, prefix + "module.id"),
                                required(properties, prefix + "module.name")
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
}
