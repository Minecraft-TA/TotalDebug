package com.github.minecraft_ta.totalDebugCompanion.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

record McpEndpointDescriptor(
        int schemaVersion,
        String transport,
        String url,
        long processId,
        String startedAt
) {
    static final String FILE_NAME = "mcp-endpoint.json";
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .setFieldNamingPolicy(com.google.gson.FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create();

    McpEndpointDescriptor(String url, Instant startedAt) {
        this(
                2,
                "streamable-http",
                Objects.requireNonNull(url, "url"),
                ProcessHandle.current().pid(),
                Objects.requireNonNull(startedAt, "startedAt").toString()
        );
    }

    void writeAtomically(Path target) throws IOException {
        Path normalized = Objects.requireNonNull(target, "target").toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent == null) {
            throw new IOException("MCP endpoint descriptor has no parent: " + normalized);
        }
        Files.createDirectories(parent);
        Path temporary = parent.resolve("." + normalized.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(this), StandardCharsets.UTF_8);
            try {
                Files.move(
                        temporary,
                        normalized,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, normalized, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static McpEndpointDescriptor read(Path target) throws IOException {
        return GSON.fromJson(Files.readString(target, StandardCharsets.UTF_8), McpEndpointDescriptor.class);
    }

    static void deleteIfOwned(Path target, String url) throws IOException {
        if (!Files.isRegularFile(target)) {
            return;
        }
        McpEndpointDescriptor descriptor = read(target);
        if (ProcessHandle.current().pid() == descriptor.processId()
                && Objects.equals(url, descriptor.url())) {
            Files.deleteIfExists(target);
        }
    }
}
