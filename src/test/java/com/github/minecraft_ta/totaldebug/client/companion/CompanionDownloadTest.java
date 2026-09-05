package com.github.minecraft_ta.totaldebug.client.companion;

import com.github.minecraft_ta.totaldebug.storage.LaunchCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.WINDOWS)
class CompanionDownloadTest {
    @TempDir Path directory;

    @Test
    void downloadsThePairedReleaseWhenMissing() throws Exception {
        byte[] expected = "current release".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        CompanionRelease release = release(expected);
        Path installed = this.directory.resolve(release.artifactFileName());
        DownloadClient client = new DownloadClient(new ByteArrayInputStream(expected), expected.length);

        new CompanionAppInstaller(this.directory, release, client).resolveOrInstall();

        assertArrayEquals(expected, Files.readAllBytes(installed));
        assertEquals(1, client.requests);
    }

    @Test
    void badDownloadLeavesNoInstallationAndCanBeRetried() throws Exception {
        byte[] expected = "current release".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        CompanionRelease release = release(expected);
        Path installed = this.directory.resolve(release.artifactFileName());
        DownloadClient client = new DownloadClient(new ByteArrayInputStream(new byte[]{1, 2}), 2);
        CompanionAppInstaller installer = new CompanionAppInstaller(this.directory, release, client);

        IOException failure = assertThrows(IOException.class, installer::resolveOrInstall);
        assertTrue(failure.getMessage().contains("checksum mismatch"));
        assertEmptyDirectory();
        client.body = new ByteArrayInputStream(expected);
        client.length = expected.length;
        installer.resolveOrInstall();
        assertArrayEquals(expected, Files.readAllBytes(installed));
        try (var files = Files.list(this.directory)) {
            assertEquals(List.of(installed), files.toList());
        }
    }

    @Test
    void manuallyReplacedJarIsPreservedWithoutNetworkOrReleaseChecksumCheck() throws Exception {
        byte[] expected = {3, 4, 5};
        CompanionRelease release = release(expected);
        Path installed = Files.writeString(this.directory.resolve(release.artifactFileName()), "development build");
        DownloadClient client = new DownloadClient(InputStream.nullInputStream(), 0);

        assertEquals(installed, new CompanionAppInstaller(this.directory, release, client).resolveOrInstall().companionJar());
        assertEquals(0, client.requests);
        assertEquals("development build", Files.readString(installed));
    }

    @Test
    void rejectsOversizedDeclaredAndUnannouncedBodies() throws Exception {
        for (long length : new long[]{5, -1}) {
            CompanionRelease release = release(new byte[]{1});
            DownloadClient client = new DownloadClient(new ByteArrayInputStream(new byte[5]), length);
            IOException failure = assertThrows(IOException.class, () -> installer(release, client,
                    Duration.ofSeconds(1), Duration.ofSeconds(2), 4).resolveOrInstall());
            assertTrue(failure.getMessage().contains("exceeds 4 bytes"));
            assertEmptyDirectory();
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(5)
    void stalledBodyIsClosedAndItsStagedFileIsRemoved() throws Exception {
        CompanionRelease release = release(new byte[]{1});
        StalledBody body = new StalledBody();
        DownloadClient client = new DownloadClient(body, -1);

        IOException failure = assertThrows(IOException.class, () -> installer(release, client,
                Duration.ofMillis(80), Duration.ofSeconds(2), 4).resolveOrInstall());

        assertTrue(failure.getMessage().contains("made no progress"), failure.toString());
        assertTrue(body.closed);
        assertEmptyDirectory();
    }

    @Test
    @org.junit.jupiter.api.Timeout(5)
    void continuousProgressStillHasATotalDeadline() throws Exception {
        CompanionRelease release = release(new byte[]{1});
        StalledBody body = new StalledBody() {
            @Override public synchronized int read(byte[] bytes, int offset, int length) throws IOException {
                try { wait(10); } catch (InterruptedException failure) { throw new IOException(failure); }
                if (this.closed) throw new IOException("closed");
                bytes[offset] = 1;
                return 1;
            }
        };
        DownloadClient client = new DownloadClient(body, -1);

        IOException failure = assertThrows(IOException.class, () -> installer(release, client,
                Duration.ofSeconds(2), Duration.ofMillis(100), 100_000).resolveOrInstall());

        assertTrue(failure.getMessage().contains("total time limit"), failure.toString());
        assertTrue(body.closed);
        assertEmptyDirectory();
    }

    @Test
    void failedHttpStatusClosesTheBodyAndLeavesNoInstall() throws Exception {
        CompanionRelease release = release(new byte[]{1});
        StalledBody body = new StalledBody();
        DownloadClient client = new DownloadClient(body, -1);
        client.status = 503;

        IOException failure = assertThrows(IOException.class,
                () -> new CompanionAppInstaller(this.directory, release, client).resolveOrInstall());

        assertEquals("Companion download returned HTTP 503", failure.getMessage());
        assertTrue(body.closed);
        assertEmptyDirectory();
    }

    @Test
    void failedAtomicReplacementPreservesDestinationAndRemovesStaging() throws Exception {
        byte[] expected = {1};
        CompanionRelease release = release(expected);
        Path destination = Files.createDirectory(this.directory.resolve(release.artifactFileName()));
        Path marker = Files.writeString(destination.resolve("marker"), "keep");
        DownloadClient client = new DownloadClient(new ByteArrayInputStream(expected), expected.length);

        assertThrows(IOException.class, () -> new CompanionAppInstaller(this.directory, release, client).resolveOrInstall());

        assertEquals("keep", Files.readString(marker));
        try (var files = Files.list(this.directory)) { assertEquals(List.of(destination), files.toList()); }
    }

    @Test
    void requestHasAFiniteHeaderDeadline() throws Exception {
        byte[] expected = {1};
        CompanionRelease release = release(expected);
        DownloadClient client = new DownloadClient(new ByteArrayInputStream(expected), expected.length);
        installer(release, client, Duration.ofSeconds(1), Duration.ofSeconds(2), 4).resolveOrInstall();
        assertEquals(Optional.of(Duration.ofSeconds(3)), client.request.timeout());
    }

    @Test
    @org.junit.jupiter.api.Timeout(10)
    void deadlinesUnblockTheRealHttpClientBeforeHeadersAndDuringBodyReads() throws Exception {
        for (boolean sendHeaders : new boolean[]{false, true}) {
            CompanionRelease release = release(new byte[]{1, 2});
            var releaseResponse = new java.util.concurrent.CountDownLatch(1);
            var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try {
                    if (sendHeaders) {
                        exchange.sendResponseHeaders(200, 0);
                        exchange.getResponseBody().write(1);
                        exchange.getResponseBody().flush();
                    }
                    releaseResponse.await(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });
            server.start();
            try (HttpClient transport = HttpClient.newHttpClient()) {
                DownloadClient client = new DownloadClient(InputStream.nullInputStream(), -1);
                client.transport = transport;
                client.endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
                CompanionAppInstaller installer = new CompanionAppInstaller(this.directory, "", release, client,
                        new CompanionAppInstaller.DownloadLimits(Duration.ofMillis(300), Duration.ofMillis(300),
                                Duration.ofSeconds(3), 4));
                IOException failure = assertThrows(IOException.class, installer::resolveOrInstall);
                if (sendHeaders) assertTrue(failure.getMessage().contains("made no progress"), failure.toString());
                else assertInstanceOf(java.net.http.HttpTimeoutException.class, failure);
                assertEmptyDirectory();
            } finally {
                releaseResponse.countDown();
                server.stop(0);
            }
        }
    }

    private CompanionAppInstaller installer(CompanionRelease release, DownloadClient client, Duration idle,
                                            Duration total, long maxBytes) {
        return new CompanionAppInstaller(this.directory, "", release, client,
                new CompanionAppInstaller.DownloadLimits(Duration.ofSeconds(3), idle, total, maxBytes));
    }

    private void assertEmptyDirectory() throws IOException {
        try (var files = Files.list(this.directory)) { assertEquals(List.of(), files.toList()); }
    }

    private static class StalledBody extends InputStream {
        protected volatile boolean closed;
        @Override public synchronized int read() throws IOException {
            while (!this.closed) {
                try { wait(); } catch (InterruptedException failure) { throw new IOException(failure); }
            }
            throw new IOException("closed");
        }
        @Override public synchronized void close() { this.closed = true; notifyAll(); }
    }

    private CompanionRelease release(byte[] expected) throws IOException {
        Path source = Files.write(this.directory.resolve("hash-input"), expected);
        String hash = LaunchCache.sha256(source);
        Files.delete(source);
        return new CompanionRelease("test", "TotalDebugCompanion.jar",
                URI.create("https://example.invalid/TotalDebugCompanion.jar"), hash);
    }

    private static final class DownloadClient extends HttpClient {
        private InputStream body;
        private long length;
        private int requests;
        private HttpRequest request;
        private int status = 200;
        private HttpClient transport;
        private URI endpoint;

        private DownloadClient(InputStream body, long length) { this.body = body; this.length = length; }

        @Override @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
                throws IOException, InterruptedException {
            this.requests++;
            this.request = request;
            // Exercise real network I/O on loopback without adding a TLS certificate fixture.
            if (this.transport != null) {
                return this.transport.send(HttpRequest.newBuilder(this.endpoint)
                        .timeout(request.timeout().orElseThrow()).GET().build(), handler);
            }
            return (HttpResponse<T>) new HttpResponse<InputStream>() {
                @Override public int statusCode() { return status; }
                @Override public HttpRequest request() { return request; }
                @Override public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
                @Override public HttpHeaders headers() {
                    return HttpHeaders.of(length < 0 ? Map.of() : Map.of("Content-Length", List.of(Long.toString(length))), (a, b) -> true);
                }
                @Override public InputStream body() { return body; }
                @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
                @Override public URI uri() { return request.uri(); }
                @Override public Version version() { return Version.HTTP_1_1; }
            };
        }
        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
        @Override public Redirect followRedirects() { return Redirect.NORMAL; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { throw new UnsupportedOperationException(); }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) { throw new UnsupportedOperationException(); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> push) { throw new UnsupportedOperationException(); }
    }
}
