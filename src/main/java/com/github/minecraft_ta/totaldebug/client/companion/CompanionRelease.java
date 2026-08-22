package com.github.minecraft_ta.totaldebug.client.companion;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

record CompanionRelease(String version, String artifactFileName, URI downloadUri, String sha256) {
    private static final String RESOURCE_NAME = "META-INF/totaldebug/companion-release.properties";
    private static final Set<String> PROPERTY_NAMES = Set.of("version", "artifact", "downloadUri", "sha256");
    private static final Pattern VERSION = Pattern.compile("[0-9A-Za-z][0-9A-Za-z._-]*");
    private static final Pattern ARTIFACT_FILE_NAME = Pattern.compile("[0-9A-Za-z][0-9A-Za-z._-]*\\.jar");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    CompanionRelease {
        requireText(version, "version");
        requireText(artifactFileName, "artifactFileName");
        if (!VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("version contains unsupported characters");
        }
        if (!ARTIFACT_FILE_NAME.matcher(artifactFileName).matches()) {
            throw new IllegalArgumentException("artifactFileName must be a simple JAR file name");
        }
        Objects.requireNonNull(downloadUri, "downloadUri");
        if (!downloadUri.isAbsolute() || !"https".equalsIgnoreCase(downloadUri.getScheme())) {
            throw new IllegalArgumentException("downloadUri must be an absolute HTTPS URI");
        }
        if (!downloadUri.getPath().endsWith("/" + artifactFileName)) {
            throw new IllegalArgumentException("downloadUri must end with artifactFileName");
        }
        Objects.requireNonNull(sha256, "sha256");
        if (!SHA256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("sha256 must contain exactly 64 lowercase hexadecimal characters");
        }
    }

    static CompanionRelease loadBundled() {
        Properties properties = new Properties();
        try (InputStream input = CompanionRelease.class.getClassLoader().getResourceAsStream(RESOURCE_NAME)) {
            if (input == null) {
                throw new IllegalStateException("Missing bundled Companion release metadata: " + RESOURCE_NAME);
            }
            properties.load(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read bundled Companion release metadata", exception);
        }

        if (!properties.stringPropertyNames().equals(PROPERTY_NAMES)) {
            throw new IllegalStateException(
                    "Companion release metadata must contain exactly " + PROPERTY_NAMES
                            + ", got " + properties.stringPropertyNames()
            );
        }
        try {
            return new CompanionRelease(
                    properties.getProperty("version"),
                    properties.getProperty("artifact"),
                    URI.create(properties.getProperty("downloadUri")),
                    properties.getProperty("sha256")
            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Bundled Companion release metadata is invalid", exception);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
