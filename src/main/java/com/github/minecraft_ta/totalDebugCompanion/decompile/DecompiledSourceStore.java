package com.github.minecraft_ta.totalDebugCompanion.decompile;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Objects;
import java.util.Properties;
import java.util.stream.Stream;

final class DecompiledSourceStore {
    private static final String DIRECTORY_NAME = "decompiled-files";
    private static final String STATE_FILE_NAME = "decompiled-files.properties";
    private final Path directory;

    private DecompiledSourceStore(Path directory) {
        this.directory = directory;
    }

    static DecompiledSourceStore open(
            Path dataDirectory,
            String runtimeSignature,
            String decompilerFormat
    ) throws IOException {
        Path root = Objects.requireNonNull(dataDirectory, "dataDirectory").toAbsolutePath().normalize();
        String signature = requireNonBlank(runtimeSignature, "runtimeSignature");
        String format = requireNonBlank(decompilerFormat, "decompilerFormat");
        Path directory = root.resolve(DIRECTORY_NAME);
        Path stateFile = root.resolve(STATE_FILE_NAME);

        Files.createDirectories(directory);
        if (!matches(stateFile, signature, format)) {
            clear(directory);
            writeState(stateFile, signature, format);
        }
        return new DecompiledSourceStore(directory);
    }

    Path find(String binaryName) {
        Path sourceFile = sourceFile(binaryName);
        return Files.isRegularFile(sourceFile) ? sourceFile : null;
    }

    Path write(String binaryName, String source) throws IOException {
        Path target = sourceFile(binaryName);
        Path staged = Files.createTempFile(this.directory, ".decompiled-", ".tmp");
        try {
            Files.writeString(staged, source, StandardCharsets.UTF_8);
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(staged);
        }
        return target;
    }

    private Path sourceFile(String binaryName) {
        Path sourceFile = this.directory.resolve(binaryName + ".java").normalize();
        if (!sourceFile.getParent().equals(this.directory)) {
            throw new IllegalArgumentException("Binary name escapes the decompiled source directory: " + binaryName);
        }
        return sourceFile;
    }

    private static boolean matches(Path stateFile, String runtimeSignature, String decompilerFormat)
            throws IOException {
        if (!Files.isRegularFile(stateFile)) {
            return false;
        }
        Properties state = new Properties();
        try (Reader reader = Files.newBufferedReader(stateFile, StandardCharsets.UTF_8)) {
            state.load(reader);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid decompiled source state: " + stateFile, exception);
        }
        return runtimeSignature.equals(state.getProperty("runtime.signature"))
                && decompilerFormat.equals(state.getProperty("decompiler.format"));
    }

    private static void writeState(Path stateFile, String runtimeSignature, String decompilerFormat)
            throws IOException {
        Files.createDirectories(Objects.requireNonNull(stateFile.getParent(), "stateFile has no parent"));
        Path staged = Files.createTempFile(stateFile.getParent(), ".decompiled-files-", ".tmp");
        String content = "runtime.signature=" + runtimeSignature + System.lineSeparator()
                + "decompiler.format=" + decompilerFormat + System.lineSeparator();
        try {
            Files.writeString(staged, content, StandardCharsets.UTF_8);
            Files.move(staged, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private static void clear(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.filter(candidate -> !candidate.equals(directory))
                    .sorted(Comparator.reverseOrder())
                    .toList()) {
                Files.delete(path);
            }
        }
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
