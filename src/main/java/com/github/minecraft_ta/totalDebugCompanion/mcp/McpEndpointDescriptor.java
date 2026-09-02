package com.github.minecraft_ta.totalDebugCompanion.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

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
        com.github.minecraft_ta.totaldebug.storage.AtomicFiles.writeString(target, GSON.toJson(this));
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
