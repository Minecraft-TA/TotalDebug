package com.github.minecraft_ta.totalDebugCompanion.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;

/** Codex-facing MCP process that remains initialized while Companion is offline or restarting. */
public final class CompanionMcpSidecar {
    static final URI DEFAULT_ENDPOINT = URI.create("http://127.0.0.1:" + CompanionMcpServer.MCP_PORT + "/mcp");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private CompanionMcpSidecar() {
    }

    public static void main(String[] args) {
        URI endpoint = parseEndpoint(args);
        int exitCode = run(System.in, System.out, endpoint);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(InputStream input, OutputStream output, URI endpoint) {
        McpSyncServer server = null;
        try (RemoteCompanion remote = new RemoteCompanion(endpoint)) {
            CountDownLatch endOfInput = new CountDownLatch(1);
            StdioServerTransportProvider transport = new StdioServerTransportProvider(
                    McpJsonDefaults.getMapper(),
                    new EndOfInputStream(input, endOfInput),
                    output
            );
            server = McpServer.sync(transport)
                    .serverInfo("totaldebug-companion-sidecar", CompanionMcpToolCatalog.SERVER_VERSION)
                    .instructions(
                            CompanionMcpToolCatalog.INSTRUCTIONS
                                    + " Companion may start or restart after this MCP session initializes; call status again."
                    )
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                    .validateToolInputs(false)
                    .tools(CompanionMcpToolCatalog.specifications(remote::call))
                    .build();

            endOfInput.await();
            return 0;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return 1;
        } catch (Throwable throwable) {
            System.err.println("TotalDebug Companion MCP sidecar failed: " + safeMessage(throwable));
            return 1;
        } finally {
            if (server != null) {
                server.closeGracefully();
            }
        }
    }

    private static URI parseEndpoint(String[] args) {
        Objects.requireNonNull(args, "args");
        if (args.length == 0) {
            return DEFAULT_ENDPOINT;
        }
        if (args.length != 2 || !"--url".equals(args[0])) {
            throw new IllegalArgumentException("Expected no arguments or --url <http://127.0.0.1:port/mcp>");
        }
        return URI.create(args[1]);
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private static final class RemoteCompanion implements AutoCloseable {
        private final URI endpoint;
        private McpSyncClient client;

        private RemoteCompanion(URI endpoint) {
            this.endpoint = validateEndpoint(endpoint);
        }

        private synchronized McpSchema.CallToolResult call(McpSchema.CallToolRequest request) {
            Objects.requireNonNull(request, "request");
            try {
                CompanionMcpToolCatalog.validateRequest(request);
            } catch (IllegalArgumentException exception) {
                return CompanionMcpToolCatalog.result(
                        Map.of("error", Objects.requireNonNullElse(exception.getMessage(), exception.toString())),
                        true
                );
            }
            try {
                ensureConnected();
                try {
                    this.client.ping();
                } catch (RuntimeException staleConnection) {
                    disconnect();
                    connect();
                }
                return this.client.callTool(request);
            } catch (ConnectionFailureException exception) {
                disconnect();
                return CompanionMcpToolCatalog.companionUnavailable(request.name(), exception.failure());
            } catch (RuntimeException exception) {
                disconnect();
                return CompanionMcpToolCatalog.companionUnavailable(
                        request.name(),
                        forwardingFailure(this.endpoint, exception)
                );
            }
        }

        private void ensureConnected() {
            if (this.client == null) {
                connect();
            }
        }

        private void connect() {
            String baseUri;
            try {
                baseUri = new URI(
                        this.endpoint.getScheme(),
                        null,
                        this.endpoint.getHost(),
                        this.endpoint.getPort(),
                        null,
                        null,
                        null
                ).toString();
            } catch (URISyntaxException exception) {
                throw new IllegalStateException("Invalid Companion MCP endpoint", exception);
            }

            HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(baseUri)
                    .endpoint(this.endpoint.getRawPath())
                    .connectTimeout(CONNECT_TIMEOUT)
                    .build();
            McpSyncClient candidate = McpClient.sync(transport)
                    .clientInfo(McpSchema.Implementation.builder(
                            "totaldebug-companion-sidecar",
                            CompanionMcpToolCatalog.SERVER_VERSION
                    ).build())
                    .initializationTimeout(CONNECT_TIMEOUT)
                    .requestTimeout(REQUEST_TIMEOUT)
                    .build();
            try {
                candidate.initialize();
                this.client = candidate;
            } catch (RuntimeException exception) {
                candidate.closeGracefully();
                throw new ConnectionFailureException(connectionFailure(this.endpoint, exception), exception);
            }
        }

        private static CompanionMcpToolCatalog.ConnectionFailure connectionFailure(
                URI endpoint,
                RuntimeException exception
        ) {
            String health = endpointHealth(endpoint);
            if ("unreachable".equals(health)) {
                return new CompanionMcpToolCatalog.ConnectionFailure(
                        isTimeout(exception) ? "connection_timeout" : "companion_unreachable",
                        "tcp_connect",
                        health,
                        true
                );
            }
            return new CompanionMcpToolCatalog.ConnectionFailure(
                    "mcp_initialization_failed",
                    "mcp_initialize",
                    health,
                    true
            );
        }

        private static CompanionMcpToolCatalog.ConnectionFailure forwardingFailure(
                URI endpoint,
                RuntimeException exception
        ) {
            String health = endpointHealth(endpoint);
            return new CompanionMcpToolCatalog.ConnectionFailure(
                    isTimeout(exception) ? "request_timeout" : "forwarding_failed",
                    "tool_call",
                    health,
                    true
            );
        }

        private static String endpointHealth(URI endpoint) {
            try (Socket socket = new Socket()) {
                socket.connect(
                        new InetSocketAddress(endpoint.getHost(), endpoint.getPort()),
                        Math.toIntExact(CONNECT_TIMEOUT.toMillis())
                );
                return "reachable";
            } catch (IOException exception) {
                return "unreachable";
            }
        }

        private static boolean isTimeout(Throwable throwable) {
            for (Throwable current = throwable; current != null; current = current.getCause()) {
                if (current instanceof HttpTimeoutException || current instanceof SocketTimeoutException) {
                    return true;
                }
            }
            return false;
        }

        private static URI validateEndpoint(URI endpoint) {
            Objects.requireNonNull(endpoint, "endpoint");
            if (!"http".equals(endpoint.getScheme())
                    || !"127.0.0.1".equals(endpoint.getHost())
                    || endpoint.getPort() < 1
                    || !"/mcp".equals(endpoint.getPath())
                    || endpoint.getUserInfo() != null
                    || endpoint.getQuery() != null
                    || endpoint.getFragment() != null) {
                throw new IllegalArgumentException("Companion MCP sidecar requires an IPv4 loopback /mcp endpoint");
            }
            return endpoint;
        }

        private void disconnect() {
            McpSyncClient current = this.client;
            this.client = null;
            if (current != null) {
                try {
                    current.closeGracefully();
                } catch (RuntimeException ignored) {
                }
            }
        }

        @Override
        public synchronized void close() {
            disconnect();
        }
    }

    private static final class ConnectionFailureException extends RuntimeException {
        private final CompanionMcpToolCatalog.ConnectionFailure failure;

        private ConnectionFailureException(
                CompanionMcpToolCatalog.ConnectionFailure failure,
                Throwable cause
        ) {
            super(cause);
            this.failure = Objects.requireNonNull(failure, "failure");
        }

        private CompanionMcpToolCatalog.ConnectionFailure failure() {
            return this.failure;
        }
    }

    private static final class EndOfInputStream extends FilterInputStream {
        private final CountDownLatch endOfInput;

        private EndOfInputStream(InputStream input, CountDownLatch endOfInput) {
            super(Objects.requireNonNull(input, "input"));
            this.endOfInput = Objects.requireNonNull(endOfInput, "endOfInput");
        }

        @Override
        public int read() throws IOException {
            return observe(super.read());
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            return observe(super.read(bytes, offset, length));
        }

        private int observe(int read) {
            if (read < 0) {
                this.endOfInput.countDown();
            }
            return read;
        }
    }
}
