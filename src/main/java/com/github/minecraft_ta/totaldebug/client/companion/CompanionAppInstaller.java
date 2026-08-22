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
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class CompanionAppInstaller {
    public static final String DEV_JAR_PROPERTY = "totaldebug.companionJar";
    public static final String DEV_JAVA_PROPERTY = "totaldebug.companionJava";
    public static final String COMPANION_VERSION = "1.9.1";
    public static final String DISTRIBUTION_SHA256 =
            "824d77c95133eeda01b2a27044fe3df7f33c8f425105be5932675f35677faa55";

    private static final URI DISTRIBUTION_URI = URI.create(
            "https://github.com/Minecraft-TA/TotalDebugCompanion/releases/download/"
                    + COMPANION_VERSION
                    + "/TotalDebugCompanion.zip"
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
        String developmentJava = System.getProperty(DEV_JAVA_PROPERTY);
        boolean hasDevelopmentJar = developmentJar != null && !developmentJar.isBlank();
        boolean hasDevelopmentJava = developmentJava != null && !developmentJava.isBlank();
        if (hasDevelopmentJar != hasDevelopmentJava) {
            throw new IOException(
                    DEV_JAR_PROPERTY + " and " + DEV_JAVA_PROPERTY + " must be configured together"
            );
        }
        if (hasDevelopmentJar) {
            Path jarPath = Path.of(developmentJar).toAbsolutePath().normalize();
            Path javaExecutable = Path.of(developmentJava).toAbsolutePath().normalize();
            requireRegularFile(jarPath, "Configured companion development JAR");
            requireRegularFile(javaExecutable, "Configured companion development Java executable");
            return new CompanionInstallation(javaExecutable, jarPath);
        }

        if (!isWindows()) {
            throw new IOException("TotalDebugCompanion " + COMPANION_VERSION + " is distributed for Windows only");
        }

        Path installationDirectory = this.appDirectory.resolve(COMPANION_VERSION);
        Path jarPath = installationDirectory.resolve("TotalDebugCompanion.jar");
        Path javaExecutable = distributionJavaExecutable(installationDirectory);
        boolean jarExists = Files.isRegularFile(jarPath);
        boolean javaExists = Files.isRegularFile(javaExecutable);

        if (jarExists != javaExists) {
            throw new IOException("Companion installation is incomplete at " + installationDirectory);
        }
        if (!jarExists) {
            installDistribution(installationDirectory);
        }

        requireRegularFile(jarPath, "Companion JAR");
        requireRegularFile(javaExecutable, "Companion Java executable");
        return new CompanionInstallation(javaExecutable, jarPath);
    }

    private void installDistribution(Path installationDirectory) throws IOException, InterruptedException {
        Files.createDirectories(this.appDirectory);
        Path archive = Files.createTempFile(this.appDirectory, ".companion-", ".zip");
        Path stagingDirectory = Files.createTempDirectory(this.appDirectory, ".companion-install-");
        try {
            downloadDistribution(archive);
            try (InputStream input = Files.newInputStream(archive)) {
                extractZip(input, stagingDirectory);
            }

            requireRegularFile(stagingDirectory.resolve("TotalDebugCompanion.jar"), "Downloaded companion JAR");
            requireRegularFile(
                    distributionJavaExecutable(stagingDirectory),
                    "Downloaded companion Java executable"
            );
            Files.move(stagingDirectory, installationDirectory);
        } finally {
            Files.deleteIfExists(archive);
            deleteTreeIfExists(stagingDirectory);
        }
    }

    private void downloadDistribution(Path archive) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(DISTRIBUTION_URI)
                .header("User-Agent", "TotalDebug/2.0")
                .GET()
                .build();
        HttpResponse<InputStream> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("Companion download returned HTTP " + response.statusCode());
        }

        MessageDigest digest = sha256();
        try (InputStream body = response.body(); DigestInputStream verifiedBody = new DigestInputStream(body, digest)) {
            Files.copy(verifiedBody, archive, StandardCopyOption.REPLACE_EXISTING);
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

    static void extractZip(InputStream input, Path destination) throws IOException {
        Objects.requireNonNull(input, "input");
        Path normalizedDestination = Objects.requireNonNull(destination, "destination").toAbsolutePath().normalize();
        Files.createDirectories(normalizedDestination);

        try (ZipInputStream zipInput = new ZipInputStream(input)) {
            for (ZipEntry entry = zipInput.getNextEntry(); entry != null; entry = zipInput.getNextEntry()) {
                Path output = normalizedDestination.resolve(entry.getName()).normalize();
                if (!output.startsWith(normalizedDestination)) {
                    throw new IOException("Companion archive contains an unsafe path: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                } else {
                    Files.createDirectories(output.getParent());
                    Files.copy(zipInput, output, StandardCopyOption.REPLACE_EXISTING);
                }
                zipInput.closeEntry();
            }
        }
    }

    private static Path distributionJavaExecutable(Path installationDirectory) {
        return installationDirectory.resolve("bin").resolve("java.exe");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("This JVM does not provide SHA-256", exception);
        }
    }

    private static void deleteTreeIfExists(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void requireRegularFile(Path path, String description) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException(description + " does not exist: " + path);
        }
    }
}
