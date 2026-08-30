package com.github.minecraft_ta.totalDebugCompanion.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class CodeModeArtifactStore {
    static final String SOURCE_ARTIFACT = "source";
    static final String JOB_ARTIFACT = "job";
    private static final Gson GSON = new GsonBuilder()
            .serializeNulls()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private final Path root;

    CodeModeArtifactStore(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    ArtifactPaths create(String jobId, String className, String source, Map<String, Object> snapshot) throws IOException {
        Path jobDirectory = jobDirectory(jobId);
        Files.createDirectories(jobDirectory);
        Path sourceFile = jobDirectory.resolve(className + ".java");
        Path jobFile = jobDirectory.resolve("job.json");
        writeAtomically(sourceFile, source);
        writeAtomically(jobFile, GSON.toJson(snapshot));
        return new ArtifactPaths(jobDirectory, sourceFile, jobFile);
    }

    void update(ArtifactPaths paths, Map<String, Object> snapshot) throws IOException {
        writeAtomically(paths.jobFile(), GSON.toJson(snapshot));
    }

    String read(String jobId, String artifact) throws IOException {
        Path directory = jobDirectory(jobId);
        Path target = switch (artifact) {
            case SOURCE_ARTIFACT -> findSourceFile(directory);
            case JOB_ARTIFACT -> directory.resolve("job.json");
            default -> throw new IllegalArgumentException("Unknown artifact: " + artifact);
        };
        if (!Files.isRegularFile(target)) {
            throw new IllegalArgumentException("Artifact does not exist: " + artifact + " for job " + jobId);
        }
        return Files.readString(target, StandardCharsets.UTF_8);
    }

    private Path jobDirectory(String jobId) {
        UUID.fromString(jobId);
        Path directory = this.root.resolve(jobId).normalize();
        if (!directory.startsWith(this.root)) {
            throw new IllegalArgumentException("Job id escapes the artifact root");
        }
        return directory;
    }

    private static Path findSourceFile(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("No artifacts exist for job " + directory.getFileName());
        }
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".java"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Source artifact does not exist for job " + directory.getFileName()
                    ));
        }
    }

    private static void writeAtomically(Path target, String value) throws IOException {
        Path temporary = target.resolveSibling("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.writeString(temporary, value, StandardCharsets.UTF_8);
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    record ArtifactPaths(Path directory, Path sourceFile, Path jobFile) {
        Map<String, Object> asMap() {
            return Map.of(
                    SOURCE_ARTIFACT, this.sourceFile.toString(),
                    JOB_ARTIFACT, this.jobFile.toString()
            );
        }
    }
}
