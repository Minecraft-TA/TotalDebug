package com.github.minecraft_ta.totaldebug.client.companion;

import com.github.minecraft_ta.totaldebug.storage.LaunchCache;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

public final class CompanionAppInstaller {
    public static final String DEV_JAR_PROPERTY = "totaldebug.companionJar";
    static final DownloadLimits DOWNLOAD_LIMITS = new DownloadLimits(
            Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofMinutes(5), 256L * 1024 * 1024);

    private final Path appDirectory;
    private final String configuredDevelopmentJar;
    private final CompanionRelease release;
    private final HttpClient httpClient;
    private final DownloadLimits limits;

    public CompanionAppInstaller(Path appDirectory) {
        this(appDirectory, "");
    }

    public CompanionAppInstaller(Path appDirectory, String configuredDevelopmentJar) {
        this(
                appDirectory,
                configuredDevelopmentJar,
                CompanionRelease.loadBundled(),
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build()
        );
    }

    CompanionAppInstaller(Path appDirectory, CompanionRelease release, HttpClient httpClient) {
        this(appDirectory, "", release, httpClient);
    }

    CompanionAppInstaller(
            Path appDirectory,
            String configuredDevelopmentJar,
            CompanionRelease release,
            HttpClient httpClient
    ) {
        this(appDirectory, configuredDevelopmentJar, release, httpClient, DOWNLOAD_LIMITS);
    }

    CompanionAppInstaller(Path appDirectory, String configuredDevelopmentJar, CompanionRelease release,
                          HttpClient httpClient, DownloadLimits limits) {
        this.appDirectory = Objects.requireNonNull(appDirectory, "appDirectory").toAbsolutePath().normalize();
        this.configuredDevelopmentJar = Objects.requireNonNull(
                configuredDevelopmentJar,
                "configuredDevelopmentJar"
        ).trim();
        this.release = Objects.requireNonNull(release, "release");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public CompanionInstallation resolveOrInstall() throws IOException, InterruptedException {
        return resolveOrInstall(progress -> { });
    }

    CompanionInstallation resolveOrInstall(Consumer<CompanionStartupProgress> progressListener)
            throws IOException, InterruptedException {
        Objects.requireNonNull(progressListener, "progressListener");
        String developmentJar = System.getProperty(DEV_JAR_PROPERTY);
        if (developmentJar == null || developmentJar.isBlank()) {
            developmentJar = this.configuredDevelopmentJar;
        }
        boolean hasDevelopmentJar = !developmentJar.isBlank();
        if (hasDevelopmentJar) {
            Path jarPath = Path.of(developmentJar).toAbsolutePath().normalize();
            requireRegularFile(jarPath, "Configured companion development JAR");
            return new CompanionInstallation(jarPath);
        }

        if (!isWindows()) {
            throw new IOException("TotalDebugCompanion " + this.release.version() + " is distributed for Windows only");
        }

        Path jarPath = this.appDirectory.resolve(this.release.artifactFileName());
        if (!Files.exists(jarPath)) {
            installDistribution(jarPath, progressListener);
        }

        requireRegularFile(jarPath, "Companion JAR");
        return new CompanionInstallation(jarPath);
    }

    private void installDistribution(
            Path jarPath,
            Consumer<CompanionStartupProgress> progressListener
    ) throws IOException, InterruptedException {
        Files.createDirectories(jarPath.getParent());
        com.github.minecraft_ta.totaldebug.storage.AtomicFiles.cleanupAbandonedStaging(jarPath.getParent());
        Path stagedJar = com.github.minecraft_ta.totaldebug.storage.AtomicFiles.temporaryFile(jarPath.getParent());
        try {
            downloadDistribution(stagedJar, progressListener);
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

    private void downloadDistribution(
            Path destination,
            Consumer<CompanionStartupProgress> progressListener
    ) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(this.release.downloadUri())
                .timeout(this.limits.headers())
                .header("User-Agent", "TotalDebugCompanionInstaller/" + this.release.version())
                .GET()
                .build();
        HttpResponse<InputStream> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("Companion download returned HTTP " + response.statusCode());
        }

        try (InputStream body = response.body();
             DownloadDeadline deadline = new DownloadDeadline(body, this.limits);
             OutputStream output = Files.newOutputStream(
                     destination,
                     StandardOpenOption.WRITE,
                     StandardOpenOption.TRUNCATE_EXISTING
             )) {
            long totalBytes = response.headers().firstValueAsLong("Content-Length")
                    .orElse(CompanionStartupProgress.UNKNOWN_TOTAL);
            if (totalBytes > this.limits.maxBytes()) {
                throw new IOException("Companion download exceeds " + this.limits.maxBytes() + " bytes");
            }
            progressListener.accept(CompanionStartupProgress.downloading(this.release.version(), 0L, totalBytes));
            long[] lastReportedPercentage = {0L};
            try {
                copyDownload(
                        body,
                        output,
                        this.limits.maxBytes(),
                        downloadedBytes -> {
                            deadline.progress();
                            if (totalBytes <= 0L) {
                                return;
                            }
                            long percentage = Math.min(100L, (long) (downloadedBytes * 100.0D / totalBytes));
                            if (percentage <= lastReportedPercentage[0]) {
                                return;
                            }
                            lastReportedPercentage[0] = percentage;
                            progressListener.accept(CompanionStartupProgress.downloading(
                                    this.release.version(),
                                    downloadedBytes,
                                    totalBytes
                            ));
                        }
                );
            } catch (IOException failure) {
                deadline.check();
                throw failure;
            }
            deadline.check();
        }

        String actualHash = LaunchCache.sha256(destination);
        if (!this.release.sha256().equals(actualHash)) {
            throw new IOException(
                    "Companion archive checksum mismatch: expected "
                            + this.release.sha256()
                            + ", got "
                            + actualHash
            );
        }
    }

    static void copyDownload(InputStream input, OutputStream output, long maxBytes, java.util.function.LongConsumer progress)
            throws IOException {
        byte[] buffer = new byte[16 * 1024];
        long downloadedBytes = 0L;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read > maxBytes - downloadedBytes) {
                throw new IOException("Companion download exceeds " + maxBytes + " bytes");
            }
            output.write(buffer, 0, read);
            downloadedBytes += read;
            progress.accept(downloadedBytes);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    record DownloadLimits(Duration headers, Duration idle, Duration total, long maxBytes) {
        DownloadLimits {
            if (headers.isNegative() || headers.isZero() || idle.isNegative() || idle.isZero()
                    || total.isNegative() || total.isZero() || maxBytes <= 0) {
                throw new IllegalArgumentException("Download limits must be positive");
            }
        }
    }

    private static void requireRegularFile(Path path, String description) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException(description + " does not exist: " + path);
        }
    }
}
