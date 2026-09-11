package com.github.minecraft_ta.totalDebugCompanion.mcp;

import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResult;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import com.github.minecraft_ta.totalDebugCompanion.project.ProjectScope;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import com.github.minecraft_ta.totalDebugCompanion.storage.InstanceState;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

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
                Clock.systemUTC()
        );
        Path dataDirectory = this.temporaryDirectory.resolve("data");
        CompanionMcpServer server = new CompanionMcpServer(
                dataDirectory,
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
            assertTrue(tools.body().contains("client_code_execute"));
            assertTrue(tools.body().contains("server_code_execute"));
            assertTrue(tools.body().contains("job_wait"));
            assertTrue(tools.body().contains("runtime_source"));
            assertTrue(tools.body().contains("search_symbols"));
            assertTrue(tools.body().contains("find_usages"));
            assertTrue(tools.body().contains("search_literals"));
            assertTrue(tools.body().contains("outputSchema"));
            assertFalse(tools.body().contains("class_bytecode"));
            assertFalse(tools.body().contains("class_source"));
            assertFalse(tools.body().contains("class_origin"));
            assertFalse(tools.body().contains("class_members"));
            assertFalse(tools.body().contains("artifacts_read"));

            HttpResponse<String> status = post(
                    client,
                    descriptor.url(),
                    sessionId,
                    "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{" +
                            "\"name\":\"status\",\"arguments\":{}}}"
            );
            assertEquals(200, status.statusCode());
            assertTrue(status.body().contains("minecraft_connected"));
            assertTrue(status.body().contains("companion_available"));
            assertTrue(status.body().contains("debugger_connected"));
            assertTrue(status.body().contains("\"type\":\"text\""), status.body());
            assertFalse(status.body().contains("workspace_directory"));
            assertFalse(status.body().contains("class_index"));
            assertFalse(status.body().contains("mcp_url"));
            assertFalse(status.body().contains("retained_jobs"));

            HttpResponse<String> invalidSearch = post(
                    client,
                    descriptor.url(),
                    sessionId,
                    "{\"jsonrpc\":\"2.0\",\"id\":31,\"method\":\"tools/call\",\"params\":{" +
                            "\"name\":\"search_symbols\",\"arguments\":{}}}"
            );
            assertEquals(200, invalidSearch.statusCode());
            assertTrue(invalidSearch.body().contains("\"isError\":true"), invalidSearch.body());
            assertTrue(invalidSearch.body().contains("\"error\""), invalidSearch.body());
            assertFalse(invalidSearch.body().contains("\"type\":\"text\""), invalidSearch.body());

            HttpResponse<String> unavailableExecution = post(
                    client,
                    descriptor.url(),
                    sessionId,
                    "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{" +
                            "\"name\":\"client_code_execute\",\"arguments\":{" +
                            "\"code\":\"return 1;\",\"wait_ms\":0}}}"
            );
            assertEquals(200, unavailableExecution.statusCode());
            assertTrue(unavailableExecution.body().contains("No Minecraft project"));
            assertTrue(unavailableExecution.body().contains("\"isError\":true"));
        }
        assertFalse(Files.exists(server.endpointDescriptor()));
    }

    @Test
    void keepsJobResponsesLimitedToExecutionOutcome() throws Exception {
        CodeModeJobService jobs = new CodeModeJobService(
                () -> true,
                new NoOpTransport(),
                () -> Map.of(
                        "workspace_directory", this.temporaryDirectory.resolve("workspace").toString(),
                        "runtime_signature", "sha256:runtime",
                        "profile_id", "profile-a"
                ),
                Clock.fixed(Instant.parse("2026-08-30T12:00:00Z"), ZoneOffset.UTC)
        );
        CompanionMcpServer server = new CompanionMcpServer(
                this.temporaryDirectory.resolve("data"),
                jobs,
                0, new DebuggerMcpService(() -> null, name -> null),
                () -> new ProjectScope(new Object(), new CompanionProfile("test", temporaryDirectory, temporaryDirectory), InstanceState.inMemory())
        );
        try (server; HttpClient client = HttpClient.newHttpClient()) {
            server.start();
            String sessionId = initialize(client, server.endpointUrl());

            HttpResponse<String> submitted = post(
                    client,
                    server.endpointUrl(),
                    sessionId,
                    "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{" +
                            "\"name\":\"client_code_execute\",\"arguments\":{" +
                            "\"code\":\"logln(1); return 1;\",\"wait_ms\":0}}}"
            );
            assertEquals(200, submitted.statusCode());
            assertConciseJobResponse(submitted.body());

            String jobId = jobs.list(1).getFirst().jobId();
            HttpResponse<String> waitedJob = post(
                    client,
                    server.endpointUrl(),
                    sessionId,
                    "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{" +
                            "\"name\":\"job_wait\",\"arguments\":{" +
                            "\"job_id\":\"" + jobId + "\",\"wait_ms\":0}}}"
            );
            assertEquals(200, waitedJob.statusCode());
            assertConciseJobResponse(waitedJob.body());

            HttpResponse<String> source = post(
                    client,
                    server.endpointUrl(),
                    sessionId,
                    "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{" +
                            "\"name\":\"job_source\",\"arguments\":{\"job_id\":\"" + jobId + "\"}}}"
            );
            assertEquals(200, source.statusCode());
            assertTrue(source.body().contains("public Object run()"));
            assertFalse(source.body().contains("source_sha256"));
        }
    }

    private static void assertConciseJobResponse(String body) {
        assertTrue(body.contains("job_id"));
        assertTrue(body.contains("state"));
        assertFalse(body.contains("source_sha256"));
        assertFalse(body.contains("artifacts"));
        assertFalse(body.contains("runtime_signature"));
        assertFalse(body.contains("workspace_directory"));
        assertFalse(body.contains("profile_id"));
    }

    private static String initialize(HttpClient client, String url) throws Exception {
        HttpResponse<String> initialized = post(client, url, null, initializeRequest());
        assertEquals(200, initialized.statusCode());
        String sessionId = initialized.headers().firstValue("mcp-session-id").orElseThrow();
        HttpResponse<String> notification = post(
                client,
                url,
                sessionId,
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"
        );
        assertTrue(notification.statusCode() == 200 || notification.statusCode() == 202);
        return sessionId;
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
                CodeModeJobService.ExecutionEnvironment environment, Consumer<ExecutionResult> failureHandler
        ) {
        }

        @Override
        public void cancel(int scriptId) {
        }
    }
}
