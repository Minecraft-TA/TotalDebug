package com.github.minecraft_ta.totalDebugCompanion.decompile;

import com.github.minecraft_ta.totalDebugCompanion.source.SourceLineMap;
import com.github.minecraft_ta.totalDebugCompanion.source.SourceVariableNames;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.stream.Stream;

final class DecompiledSourceStore {
    private static final int LINE_MAP_MAGIC = 0x54444C4D;
    private static final int LINE_MAP_VERSION = 1;
    private static final int VARIABLE_NAMES_MAGIC = 0x5444564E;
    private static final int VARIABLE_NAMES_VERSION = 1;
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
        return Files.isRegularFile(sourceFile)
                && Files.isRegularFile(lineMapFile(binaryName))
                && Files.isRegularFile(variableNamesFile(binaryName))
                ? sourceFile
                : null;
    }

    SourceLineMap readLineMap(String binaryName) throws IOException {
        Path path = lineMapFile(binaryName);
        if (!Files.isRegularFile(path)) {
            throw new IOException("No decompiled source line map exists for " + binaryName);
        }
        try (DataInputStream input = new DataInputStream(Files.newInputStream(path))) {
            int magic = input.readInt();
            if (magic != LINE_MAP_MAGIC) {
                throw new IOException("Invalid decompiled source line map magic: " + path);
            }
            int version = input.readInt();
            if (version != LINE_MAP_VERSION) {
                throw new IOException("Unsupported decompiled source line map version " + version + ": " + path);
            }
            int length = input.readInt();
            if (length < 0 || length % 2 != 0) {
                throw new IOException("Invalid decompiled source line map length " + length + ": " + path);
            }
            int[] mapping = new int[length];
            for (int i = 0; i < length; i++) {
                mapping[i] = input.readInt();
            }
            if (input.read() != -1) {
                throw new IOException("Trailing data in decompiled source line map: " + path);
            }
            try {
                return SourceLineMap.fromOriginalToDisplayed(mapping);
            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid decompiled source line map: " + path, exception);
            }
        }
    }

    SourceVariableNames readVariableNames(String binaryName) throws IOException {
        Path path = variableNamesFile(binaryName);
        if (!Files.isRegularFile(path)) {
            throw new IOException("No decompiled source variable names exist for " + binaryName);
        }
        try (DataInputStream input = new DataInputStream(Files.newInputStream(path))) {
            if (input.readInt() != VARIABLE_NAMES_MAGIC) {
                throw new IOException("Invalid decompiled variable-name magic: " + path);
            }
            int version = input.readInt();
            if (version != VARIABLE_NAMES_VERSION) {
                throw new IOException("Unsupported decompiled variable-name version " + version + ": " + path);
            }
            int count = input.readInt();
            if (count < 0) {
                throw new IOException("Invalid decompiled variable-name count " + count + ": " + path);
            }
            Map<String, String> mappings = new java.util.LinkedHashMap<>(count);
            for (int index = 0; index < count; index++) {
                String runtimeName = input.readUTF();
                String previous = mappings.put(runtimeName, input.readUTF());
                if (previous != null) {
                    throw new IOException("Duplicate runtime variable name " + runtimeName + ": " + path);
                }
            }
            if (input.read() != -1) {
                throw new IOException("Trailing data in decompiled variable names: " + path);
            }
            try {
                return SourceVariableNames.of(mappings);
            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid decompiled variable names: " + path, exception);
            }
        }
    }

    Path write(
            String binaryName,
            String source,
            SourceLineMap lineMap,
            SourceVariableNames variableNames
    ) throws IOException {
        Objects.requireNonNull(lineMap, "lineMap");
        Objects.requireNonNull(variableNames, "variableNames");
        Path target = sourceFile(binaryName);
        Path mappingTarget = lineMapFile(binaryName);
        Path variableNamesTarget = variableNamesFile(binaryName);
        Path stagedSource = Files.createTempFile(this.directory, ".decompiled-", ".tmp");
        Path stagedMapping = Files.createTempFile(this.directory, ".decompiled-lines-", ".tmp");
        Path stagedVariableNames = Files.createTempFile(this.directory, ".decompiled-names-", ".tmp");
        try {
            Files.writeString(stagedSource, source, StandardCharsets.UTF_8);
            writeLineMap(stagedMapping, lineMap);
            writeVariableNames(stagedVariableNames, variableNames);
            Files.move(
                    stagedMapping,
                    mappingTarget,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
            Files.move(
                    stagedVariableNames,
                    variableNamesTarget,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
            Files.move(
                    stagedSource,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } finally {
            Files.deleteIfExists(stagedSource);
            Files.deleteIfExists(stagedMapping);
            Files.deleteIfExists(stagedVariableNames);
        }
        return target;
    }

    private Path sourceFile(String binaryName) {
        return resolveFile(binaryName, ".java");
    }

    private Path lineMapFile(String binaryName) {
        return resolveFile(binaryName, ".lines");
    }

    private Path variableNamesFile(String binaryName) {
        return resolveFile(binaryName, ".names");
    }

    private Path resolveFile(String binaryName, String extension) {
        Path file = this.directory.resolve(binaryName + extension).normalize();
        if (!file.getParent().equals(this.directory)) {
            throw new IllegalArgumentException("Binary name escapes the decompiled source directory: " + binaryName);
        }
        return file;
    }

    private static void writeLineMap(Path path, SourceLineMap lineMap) throws IOException {
        int[] mapping = lineMap.originalToDisplayed();
        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(path))) {
            output.writeInt(LINE_MAP_MAGIC);
            output.writeInt(LINE_MAP_VERSION);
            output.writeInt(mapping.length);
            for (int line : mapping) {
                output.writeInt(line);
            }
        }
    }

    private static void writeVariableNames(Path path, SourceVariableNames variableNames) throws IOException {
        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(path))) {
            output.writeInt(VARIABLE_NAMES_MAGIC);
            output.writeInt(VARIABLE_NAMES_VERSION);
            output.writeInt(variableNames.mappings().size());
            for (Map.Entry<String, String> mapping : variableNames.mappings().entrySet()) {
                output.writeUTF(mapping.getKey());
                output.writeUTF(mapping.getValue());
            }
        }
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
