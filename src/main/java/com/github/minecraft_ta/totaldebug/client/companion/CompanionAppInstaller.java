package com.github.minecraft_ta.totaldebug.client.companion;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

public final class CompanionAppInstaller {
    public static final String DEV_JAR_PROPERTY = "totaldebug.companionJar";
    public static final String COMPANION_VERSION = "2.0.0";
    public static final String DISTRIBUTION_SHA256 =
            "4f1d24f98d49fce91f397fb98044c39c24e9ff4f0b5b0a534f379aa0014897ec";

    private static final URI DISTRIBUTION_URI = URI.create(
            "https://github.com/Minecraft-TA/TotalDebugCompanion/releases/download/v"
                    + COMPANION_VERSION
                    + "/TotalDebugCompanion.jar"
    );

    private final Path appDirectory;
    private final HttpClient httpClient;

    public CompanionAppInstaller(Path appDirectory) {
        this(
                appDirectory,
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build()
        );
    }

    CompanionAppInstaller(Path appDirectory, HttpClient httpClient) {
        this.appDirectory = Objects.requireNonNull(appDirectory, "appDirectory").toAbsolutePath().normalize();
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    public CompanionInstallation resolveOrInstall() throws IOException, InterruptedException {
        String developmentJar = System.getProperty(DEV_JAR_PROPERTY);
        boolean hasDevelopmentJar = developmentJar != null && !developmentJar.isBlank();
        if (hasDevelopmentJar) {
            Path jarPath = Path.of(developmentJar).toAbsolutePath().normalize();
            requireRegularFile(jarPath, "Configured companion development JAR");
            return new CompanionInstallation(jarPath);
        }

        if (!isWindows()) {
            throw new IOException("TotalDebugCompanion " + COMPANION_VERSION + " is distributed for Windows only");
        }

        Path installationDirectory = this.appDirectory.resolve(COMPANION_VERSION);
        Path jarPath = installationDirectory.resolve("TotalDebugCompanion.jar");
        if (!Files.isRegularFile(jarPath) || !DISTRIBUTION_SHA256.equals(sha256(jarPath))) {
            installDistribution(jarPath);
        }

        requireRegularFile(jarPath, "Companion JAR");
        String installedHash = sha256(jarPath);
        if (!DISTRIBUTION_SHA256.equals(installedHash)) {
            throw new IOException(
                    "Installed companion checksum mismatch: expected "
                            + DISTRIBUTION_SHA256
                            + ", got "
                            + installedHash
            );
        }
        return new CompanionInstallation(jarPath);
    }

    private void installDistribution(Path jarPath) throws IOException, InterruptedException {
        Files.createDirectories(jarPath.getParent());
        Path stagedJar = Files.createTempFile(jarPath.getParent(), ".companion-", ".jar");
        try {
            downloadDistribution(stagedJar);
            Files.move(
                    stagedJar,
                    jarPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } finally {
            Files.deleteIfExists(stagedJar);
        }
    }

    private void downloadDistribution(Path destination) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(DISTRIBUTION_URI)
                .header("User-Agent", "TotalDebug/2.0")
                .GET()
                .build();
        HttpResponse<InputStream> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("Companion download returned HTTP " + response.statusCode());
        }

        MessageDigest digest = sha256Digest();
        try (InputStream body = response.body(); DigestInputStream verifiedBody = new DigestInputStream(body, digest)) {
            Files.copy(verifiedBody, destination, StandardCopyOption.REPLACE_EXISTING);
        }

        String actualHash = HexFormat.of().formatHex(digest.digest());
        if (!DISTRIBUTION_SHA256.equals(actualHash)) {
            throw new IOException(
                    "Companion archive checksum mismatch: expected "
                            + DISTRIBUTION_SHA256
                            + ", got "
                            + actualHash
            );
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    static String sha256(Path path) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream input = Files.newInputStream(path); DigestInputStream verified = new DigestInputStream(input, digest)) {
            verified.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("This JVM does not provide SHA-256", exception);
        }
    }

    private static void requireRegularFile(Path path, String description) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException(description + " does not exist: " + path);
        }
    }
}
