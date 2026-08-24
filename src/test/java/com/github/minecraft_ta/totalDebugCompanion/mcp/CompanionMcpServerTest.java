package com.github.minecraft_ta.totalDebugCompanion.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionMcpServerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void servesMcpInitializeAndToolDiscoveryOnTheStableLoopbackEndpoint() throws Exception {
        CodeModeJobService jobs = new CodeModeJobService(
                () -> false,
                new NoOpTransport(),
                source -> source,
                this.temporaryDirectory.resolve("artifacts"),
                Clock.systemUTC()
        );
        Path dataDirectory = this.temporaryDirectory.resolve("data");
        CompanionMcpServer server = new CompanionMcpServer(
                dataDirectory,
                () -> this.temporaryDirectory.resolve("workspace"),
                () -> this.temporaryDirectory.resolve("index.bin"),
                jobs,
                0
        );
        try (server; HttpClient client = HttpClient.newHttpClient()) {
            server.start();
            assertTrue(Files.isRegularFile(server.endpointDescriptor()));
            McpEndpointDescriptor descriptor = McpEndpointDescriptor.read(server.endpointDescriptor());
            assertEquals(server.endpointUrl(), descriptor.url());
            assertEquals(32_123, CompanionMcpServer.MCP_PORT);
            assertTrue(descriptor.url().startsWith("http://127.0.0.1:"));
            assertTrue(descriptor.url().endsWith("/mcp"));

            HttpResponse<String> initialized = post(
                    client,
                    descriptor.url(),
                    null,
                    initializeRequest()
            );
            assertEquals(200, initialized.statusCode());
            assertTrue(initialized.body().contains("totaldebug-companion"));
            String sessionId = initialized.headers().firstValue("mcp-session-id").orElse(null);
            assertNotNull(sessionId);

            HttpResponse<String> notification = post(
                    client,
                    descriptor.url(),
                    sessionId,
                    "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"
            );
            assertTrue(notification.statusCode() == 200 || notification.statusCode() == 202);

            HttpResponse<String> tools = post(
                    client,
                    descriptor.url(),
                    sessionId,
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}"
            );
            assertEquals(200, tools.statusCode());
            assertTrue(tools.body().contains("code_execute"));
            assertTrue(tools.body().contains("artifacts_read"));

            HttpResponse<String> status = post(
                    client,
                    descriptor.url(),
                    sessionId,
                    "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{" +
                            "\"name\":\"status\",\"arguments\":{}}}"
            );
            assertEquals(200, status.statusCode());
            assertTrue(status.body().contains("minecraft_connected"));
            assertTrue(status.body().contains("streamable-http"));

            HttpResponse<String> unavailableExecution = post(
                    client,
                    descriptor.url(),
                    sessionId,
                    "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{" +
                            "\"name\":\"code_execute\",\"arguments\":{\"code\":\"logln(1);\"}}}"
            );
            assertEquals(200, unavailableExecution.statusCode());
            assertTrue(unavailableExecution.body().contains("not available"));
            assertTrue(unavailableExecution.body().contains("\"isError\":true"));
        }
        assertFalse(Files.exists(server.endpointDescriptor()));
    }

    private static HttpResponse<String> post(
            HttpClient client,
            String url,
            String sessionId,
            String body
    ) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2025-11-25")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (sessionId != null) {
            request.header("Mcp-Session-Id", sessionId);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String initializeRequest() {
        return """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":"2025-11-25",
                  "capabilities":{},
                  "clientInfo":{"name":"companion-test","version":"1.0.0"}
                }}
                """;
    }

    private static final class NoOpTransport implements CodeModeJobService.Transport {
        @Override
        public void execute(
                int scriptId,
                String source,
                CodeModeJobService.ExecutionSide side,
                CodeModeJobService.ExecutionEnvironment environment
        ) {
        }

        @Override
        public void cancel(int scriptId) {
        }
    }
}
