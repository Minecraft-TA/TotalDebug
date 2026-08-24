package com.github.minecraft_ta.totalDebugCompanion.runtime;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

final class PreparedRuntimeSources {
    static final String FILE_NAME = "runtime-sources.properties";
    private static final String FORMAT = "3";

    private PreparedRuntimeSources() {
    }

    static void write(Path file, List<RuntimeSnapshotBytecodeSource.Source> sources) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("format", FORMAT);
        properties.setProperty("source.count", Integer.toString(sources.size()));
        for (int index = 0; index < sources.size(); index++) {
            RuntimeSnapshotBytecodeSource.Source source = sources.get(index);
            properties.setProperty("source." + index + ".id", Integer.toString(source.sourceId()));
            properties.setProperty("source." + index + ".path", source.path().toUri().toASCIIString());
            properties.setProperty("source." + index + ".logical", source.logicalUri());
            properties.setProperty("source." + index + ".module.id", source.module().id());
            properties.setProperty("source." + index + ".module.name", source.module().displayName());
        }
        try (OutputStream output = Files.newOutputStream(file)) {
            properties.store(output, "TotalDebug Companion prepared runtime sources");
        }
    }

    static List<RuntimeSnapshotBytecodeSource.Source> read(Path file) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        }
        try {
            if (!FORMAT.equals(required(properties, "format"))) {
                throw new IllegalArgumentException("Unsupported prepared source format");
            }
            int count = Integer.parseInt(required(properties, "source.count"));
            List<RuntimeSnapshotBytecodeSource.Source> sources = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                int id = Integer.parseInt(required(properties, "source." + index + ".id"));
                URI uri = URI.create(required(properties, "source." + index + ".path"));
                Path path = Path.of(uri).toAbsolutePath().normalize();
                if (!Files.isRegularFile(path) && !Files.isDirectory(path)) {
                    throw new IllegalArgumentException("Prepared runtime source is unavailable: " + path);
                }
                sources.add(new RuntimeSnapshotBytecodeSource.Source(
                        id,
                        path,
                        required(properties, "source." + index + ".logical"),
                        new RuntimeInventory.RuntimeModule(
                                required(properties, "source." + index + ".module.id"),
                                required(properties, "source." + index + ".module.name")
                        )
                ));
            }
            return List.copyOf(sources);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid prepared runtime sources " + file, exception);
        }
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing prepared source field " + key);
        }
        return value;
    }
}
