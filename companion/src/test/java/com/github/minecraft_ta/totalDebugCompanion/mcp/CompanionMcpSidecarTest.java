package com.github.minecraft_ta.totalDebugCompanion.mcp;

import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResult;
import java.util.function.Consumer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionMcpSidecarTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void initializesOfflineForwardsConcurrentWaitsAndReconnects() throws Exception {
        int port = availablePort();
        URI endpoint = URI.create("http://127.0.0.1:" + port + "/mcp");

        try (PipedInputStream sidecarInput = new PipedInputStream();
             PipedOutputStream clientOutput = new PipedOutputStream(sidecarInput);
             PipedInputStream clientInput = new PipedInputStream();
             PipedOutputStream sidecarOutput = new PipedOutputStream(clientInput);
             BufferedWriter writer = new BufferedWriter(new java.io.OutputStreamWriter(clientOutput, StandardCharsets.UTF_8));
             BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(clientInput, StandardCharsets.UTF_8));
             ExecutorService executor = Executors.newCachedThreadPool()) {
            Future<Integer> sidecar = executor.submit(() ->
                    CompanionMcpSidecar.run(sidecarInput, sidecarOutput, endpoint));

            send(writer, """
                    {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                      "protocolVersion":"2025-11-25",
                      "capabilities":{},
                      "clientInfo":{"name":"sidecar-test","version":"1.0.0"}
                    }}
                    """);
            JsonObject initialized = response(reader, executor, 1);
            assertEquals("totaldebug-companion-sidecar",
                    initialized.getAsJsonObject("result").getAsJsonObject("serverInfo").get("name").getAsString());

            send(writer, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            send(writer, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
            JsonObject tools = response(reader, executor, 2);
            assertTrue(tools.toString().contains("client_code_execute"));
            assertTrue(tools.toString().contains("server_code_execute"));
            assertTrue(tools.toString().contains("job_source"));
            assertTrue(tools.toString().contains("runtime_source"));
            assertFalse(tools.toString().contains("class_source"));
            assertTrue(tools.toString().contains("outputSchema"));
            assertTrue(tools.toString().contains("debugger_control"));
            assertTrue(tools.toString().contains("debugger_evaluate"));
            assertFalse(tools.toString().contains("artifacts_read"));

            send(writer, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{" +
                    "\"name\":\"status\",\"arguments\":{}}}");
            JsonObject offlineStatus = response(reader, executor, 3);
            assertTrue(offlineStatus.toString().contains("companion_available"));
            assertTrue(offlineStatus.toString().contains("debugger_connected"));
            assertTrue(offlineStatus.toString().contains("false"));
            assertFalse(offlineStatus.toString().contains("sidecar_process_id"));
            assertFalse(offlineStatus.toString().contains("mcp_url"));
            assertFalse(offlineStatus.toString().contains("error"));
            assertTrue(offlineStatus.toString().contains("\"type\":\"text\""));

            send(writer, "{\"jsonrpc\":\"2.0\",\"id\":29,\"method\":\"tools/call\",\"params\":{" +
                    "\"name\":\"search_symbols\",\"arguments\":{}}}");
            JsonObject invalidSearch = response(reader, executor, 29);
            assertTrue(invalidSearch.getAsJsonObject("result").get("isError").getAsBoolean());
            assertTrue(invalidSearch.toString().contains("error"));
            assertFalse(invalidSearch.toString().contains("companion_unreachable"));
            assertFalse(invalidSearch.toString().contains("\"type\":\"text\""));

            send(writer, "{\"jsonrpc\":\"2.0\",\"id\":30,\"method\":\"tools/call\",\"params\":{" +
                    "\"name\":\"client_code_execute\",\"arguments\":{\"code\":\"return 1;\"}}}");
            assertUnreachableFailure(response(reader, executor, 30));

            send(writer, "{\"jsonrpc\":\"2.0\",\"id\":31,\"method\":\"tools/call\",\"params\":{" +
                    "\"name\":\"debugger_status\",\"arguments\":{}}}");
            assertUnreachableFailure(response(reader, executor, 31));

            CompletableFuture<Void> submitted = new CompletableFuture<>();
            CodeModeJobService.Transport waitingTransport = new CodeModeJobService.Transport() {
                @Override
                public void execute(int id, String source, CodeModeJobService.ExecutionSide side,
                                    CodeModeJobService.ExecutionEnvironment environment, Consumer<ExecutionResult> failureHandler) {
                    submitted.complete(null);
                }

                @Override
                public void cancel(int id) { }
            };
            try (CompanionMcpServer companion = companion(port, "first", waitingTransport)) {
                companion.start();
                send(writer, "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{" +
                        "\"name\":\"status\",\"arguments\":{}}}");
                JsonObject onlineStatus = response(reader, executor, 4);
                assertTrue(onlineStatus.toString().contains("companion_available"));
                assertTrue(onlineStatus.toString().contains("minecraft_connected"));
                assertFalse(onlineStatus.toString().contains("companion_process_id"));
                send(writer, """
                        {"jsonrpc":"2.0","id":40,"method":"tools/call","params":{
                          "name":"client_code_execute","arguments":{"code":"return 1;","wait_ms":12000}}}
                        """);
                submitted.get(5, TimeUnit.SECONDS);
                send(writer, """
                        {"jsonrpc":"2.0","id":41,"method":"tools/call","params":{"name":"status","arguments":{}}}
                        """);
                assertFalse(response(reader, executor, 41).getAsJsonObject("result").get("isError").getAsBoolean());
                JsonObject waited = response(reader, executor, 40, 20).getAsJsonObject("result");
                assertFalse(waited.get("isError").getAsBoolean());
                assertTrue(waited.getAsJsonObject("structuredContent").has("job_id"));
            }

            send(writer, "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{" +
                    "\"name\":\"client_code_execute\",\"arguments\":{\"code\":\"return 1;\"}}}");
            JsonObject stoppedExecution = response(reader, executor, 5);
            assertUnreachableFailure(stoppedExecution);

            try (CompanionMcpServer companion = companion(port, "second")) {
                companion.start();
                send(writer, "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":{" +
                        "\"name\":\"status\",\"arguments\":{}}}");
                JsonObject restartedStatus = response(reader, executor, 6);
                assertTrue(restartedStatus.toString().contains("companion_available"));
                assertTrue(restartedStatus.toString().contains("minecraft_connected"));
                assertFalse(restartedStatus.toString().contains("companion_process_id"));
            }

            clientOutput.close();
            assertEquals(0, sidecar.get(5, TimeUnit.SECONDS));
        }
    }

    private static void assertUnreachableFailure(JsonObject response) {
        assertTrue(response.toString().contains("companion_unreachable"));
        assertTrue(response.toString().contains("tcp_connect"));
        assertTrue(response.toString().contains("endpoint_health"));
        assertTrue(response.toString().contains("unreachable"));
        assertTrue(response.toString().contains("retryable"));
        assertFalse(response.toString().contains("hint"));
        assertFalse(response.toString().contains("companion_available"));
        assertTrue(response.getAsJsonObject("result").get("isError").getAsBoolean());
    }

    private CompanionMcpServer companion(int port, String instance) {
        return companion(port, instance, new NoOpTransport());
    }

    private CompanionMcpServer companion(int port, String instance, CodeModeJobService.Transport transport) {
        CodeModeJobService jobs = new CodeModeJobService(
                () -> true,
                transport,
                Clock.systemUTC()
        );
        return new CompanionMcpServer(
                this.temporaryDirectory.resolve(instance).resolve("data"),
                jobs,
                port
        );
    }

    private static int availablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static void send(BufferedWriter writer, String message) throws Exception {
        writer.write(message.replace("\r", "").replace("\n", ""));
        writer.newLine();
        writer.flush();
    }

    private static JsonObject response(BufferedReader reader, ExecutorService executor, int id) throws Exception {
        return response(reader, executor, id, 5);
    }

    private static JsonObject response(BufferedReader reader, ExecutorService executor, int id, int timeoutSeconds) throws Exception {
        for (int index = 0; index < 20; index++) {
            Future<String> line = executor.submit(reader::readLine);
            String value = line.get(timeoutSeconds, TimeUnit.SECONDS);
            if (value == null) {
                throw new AssertionError("Sidecar output closed before response " + id);
            }
            JsonObject message = JsonParser.parseString(value).getAsJsonObject();
            if (message.has("id") && message.get("id").getAsInt() == id) {
                return message;
            }
        }
        throw new AssertionError("Sidecar did not return response " + id);
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
