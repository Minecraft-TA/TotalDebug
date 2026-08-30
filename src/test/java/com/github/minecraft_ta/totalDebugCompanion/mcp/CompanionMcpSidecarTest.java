package com.github.minecraft_ta.totalDebugCompanion.mcp;

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
    void initializesOfflineThenForwardsAfterCompanionAppears() throws Exception {
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
            assertTrue(tools.toString().contains("code_execute"));
            assertTrue(tools.toString().contains("artifacts_read"));

            send(writer, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{" +
                    "\"name\":\"status\",\"arguments\":{}}}");
            JsonObject offlineStatus = response(reader, executor, 3);
            assertTrue(offlineStatus.toString().contains("companion_available"));
            assertTrue(offlineStatus.toString().contains("false"));
            assertFalse(offlineStatus.toString().contains("sidecar_process_id"));
            assertFalse(offlineStatus.toString().contains("mcp_url"));

            try (CompanionMcpServer companion = companion(port, "first")) {
                companion.start();
                send(writer, "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{" +
                        "\"name\":\"status\",\"arguments\":{}}}");
                JsonObject onlineStatus = response(reader, executor, 4);
                assertTrue(onlineStatus.toString().contains("companion_available"));
                assertTrue(onlineStatus.toString().contains("minecraft_connected"));
                assertFalse(onlineStatus.toString().contains("companion_process_id"));

            }

            send(writer, "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{" +
                    "\"name\":\"code_execute\",\"arguments\":{\"code\":\"logln(1);\"}}}");
            JsonObject stoppedExecution = response(reader, executor, 5);
            assertTrue(stoppedExecution.toString().contains("companion_available"));
            assertTrue(stoppedExecution.getAsJsonObject("result").get("isError").getAsBoolean());

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

    private CompanionMcpServer companion(int port, String instance) {
        CodeModeJobService jobs = new CodeModeJobService(
                () -> false,
                new NoOpTransport(),
                source -> source,
                this.temporaryDirectory.resolve(instance).resolve("artifacts"),
                Clock.systemUTC()
        );
        return new CompanionMcpServer(
                this.temporaryDirectory.resolve(instance).resolve("data"),
                () -> this.temporaryDirectory.resolve("workspace"),
                () -> this.temporaryDirectory.resolve("index.bin"),
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
        for (int index = 0; index < 20; index++) {
            Future<String> line = executor.submit(reader::readLine);
            String value = line.get(5, TimeUnit.SECONDS);
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
                CodeModeJobService.ExecutionEnvironment environment
        ) {
        }

        @Override
        public void cancel(int scriptId) {
        }
    }
}
