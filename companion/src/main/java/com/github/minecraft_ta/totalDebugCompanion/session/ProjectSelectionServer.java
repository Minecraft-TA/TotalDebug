package com.github.minecraft_ta.totalDebugCompanion.session;

import com.github.minecraft_ta.totaldebug.protocol.ProjectSelectionRequest;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Independent of the occupied game socket and of the optional MCP host. */
final class ProjectSelectionServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            runnable -> Thread.ofPlatform().daemon().name("companion-project-request").unstarted(runnable));

    ProjectSelectionServer(SessionAuthenticator authenticator, CompanionSession.AttachmentHandler select) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        this.server.setExecutor(this.worker);
        this.server.createContext(ProjectSelectionRequest.PATH, exchange -> {
            try (exchange) {
                int status;
                String detail;
                if (!exchange.getRequestMethod().equals("POST")
                        || !exchange.getRequestURI().toString().equals(ProjectSelectionRequest.PATH)
                        || exchange.getRequestHeaders().containsKey("Origin")
                        || !"application/octet-stream".equals(exchange.getRequestHeaders().getFirst("Content-Type"))) {
                    status = 400;
                    detail = "Expected a local project selection request";
                } else {
                    try {
                        var hello = ProjectSelectionRequest.decode(exchange.getRequestBody().readNBytes(ProjectSelectionRequest.MAX_BYTES + 1));
                        var authentication = authenticator.authenticate(hello);
                        if (!authentication.accepted()) {
                            status = 403;
                            detail = authentication.rejectionReason();
                        } else {
                            select.attach(hello);
                            exchange.sendResponseHeaders(204, -1);
                            return;
                        }
                    } catch (IOException | RuntimeException failure) {
                        status = 409;
                        detail = failure.getMessage() == null ? "Unable to switch project" : failure.getMessage();
                    }
                }
                byte[] bytes = detail.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        this.server.start();
    }

    int port() { return this.server.getAddress().getPort(); }

    @Override public void close() {
        this.server.stop(0);
        this.worker.shutdownNow();
    }
}
