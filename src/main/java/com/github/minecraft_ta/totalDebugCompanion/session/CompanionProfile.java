package com.github.minecraft_ta.totalDebugCompanion.session;

import com.github.minecraft_ta.totalDebugCompanion.messages.session.ClientHelloMessage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Properties;

public record CompanionProfile(
        String id,
        Path dataDirectory,
        Path indexFile,
        Path workspaceDirectory,
        Path runtimeSourceManifest,
        String runtimeSignature,
        long supportedCapabilities
) {
    private static final String ID = "id";
    private static final String DATA = "data";
    private static final String INDEX = "index";
    private static final String WORKSPACE = "workspace";
    private static final String SOURCES = "sources";
    private static final String SIGNATURE = "signature";
    private static final String CAPABILITIES = "capabilities";

    public CompanionProfile {
        if (Objects.requireNonNull(id, "id").isBlank()) {
            throw new IllegalArgumentException("Profile id is blank");
        }
        dataDirectory = normalize(dataDirectory, "dataDirectory");
        indexFile = normalize(indexFile, "indexFile");
        workspaceDirectory = normalize(workspaceDirectory, "workspaceDirectory");
        runtimeSourceManifest = normalize(runtimeSourceManifest, "runtimeSourceManifest");
        if (Objects.requireNonNull(runtimeSignature, "runtimeSignature").isBlank()) {
            throw new IllegalArgumentException("Runtime signature is blank");
        }
    }

    public static CompanionProfile fromHello(ClientHelloMessage hello, long capabilities) {
        return new CompanionProfile(
                hello.profileId(),
                Path.of(hello.dataDirectory()),
                Path.of(hello.indexFile()),
                Path.of(hello.workspaceDirectory()),
                Path.of(hello.runtimeSourceManifest()),
                hello.runtimeSignature(),
                capabilities
        );
    }

    public void writeAtomically(Path profileFile) throws IOException {
        Path target = profileFile.toAbsolutePath().normalize();
        Path parent = Objects.requireNonNull(target.getParent(), "Profile file has no parent");
        Files.createDirectories(parent);
        Properties values = new Properties();
        values.setProperty(ID, this.id);
        values.setProperty(DATA, this.dataDirectory.toString());
        values.setProperty(INDEX, this.indexFile.toString());
        values.setProperty(WORKSPACE, this.workspaceDirectory.toString());
        values.setProperty(SOURCES, this.runtimeSourceManifest.toString());
        values.setProperty(SIGNATURE, this.runtimeSignature);
        values.setProperty(CAPABILITIES, Long.toUnsignedString(this.supportedCapabilities));
        Path staged = Files.createTempFile(parent, ".profile-", ".tmp");
        try (OutputStream output = Files.newOutputStream(staged)) {
            values.store(output, "TotalDebug Companion profile");
        }
        try {
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    public static CompanionProfile read(Path profileFile) throws IOException {
        Properties values = new Properties();
        try (InputStream input = Files.newInputStream(profileFile)) {
            values.load(input);
        }
        try {
            return new CompanionProfile(
                    required(values, ID),
                    Path.of(required(values, DATA)),
                    Path.of(required(values, INDEX)),
                    Path.of(required(values, WORKSPACE)),
                    Path.of(required(values, SOURCES)),
                    required(values, SIGNATURE),
                    Long.parseUnsignedLong(required(values, CAPABILITIES))
            );
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid Companion profile", exception);
        }
    }

    private static String required(Properties values, String key) {
        String value = values.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing profile field: " + key);
        }
        return value;
    }

    private static Path normalize(Path path, String name) {
        return Objects.requireNonNull(path, name).toAbsolutePath().normalize();
    }
}
