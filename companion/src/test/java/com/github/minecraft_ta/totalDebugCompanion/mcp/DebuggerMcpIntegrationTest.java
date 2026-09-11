package com.github.minecraft_ta.totalDebugCompanion.mcp;

import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResult;
import java.util.function.Consumer;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugTargetDescriptor;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerSessionController;
import com.github.minecraft_ta.totalDebugCompanion.debugger.fixture.McpDebuggeeMain;
import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import com.github.tth05.jindex.ClassIndex;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises all debugger tools over the actual HTTP MCP wire against a real JDWP child JVM. */
class DebuggerMcpIntegrationTest {
    @TempDir Path temporaryDirectory;

    @BeforeAll
    static void initializeIndex() throws Exception {
        var bytes = new java.util.ArrayList<byte[]>();
        for (Class<?> type : List.of(Object.class, String.class, McpDebuggeeMain.class)) {
            try (var stream = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                bytes.add(stream.readAllBytes());
            }
        }
        CompanionClassIndex.set(ClassIndex.fromBytes(bytes));
    }

    @AfterAll
    static void closeIndex() {
        CompanionClassIndex.get().close();
        CompanionClassIndex.clear();
    }

    @Test
    @Timeout(90)
    void controlsAndInspectsTheSharedDebuggerOverMcp() throws Exception {
        Class<?> fixture = McpDebuggeeMain.class;
        Path sourceFile = Path.of("src/test/java", fixture.getName().replace('.', '/') + ".java").toAbsolutePath();
        String sourceText = Files.readString(sourceFile);
        DebugEngine.Source source = new DebugEngine.Source(sourceFile.toUri(), fixture.getName(), sourceText);
        int line = 1;
        for (String sourceLine : sourceText.lines().toList()) {
            if (sourceLine.contains("MCP_BREAK")) break;
            line++;
        }
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        if (!System.getProperty("os.name").startsWith("Windows")) {
            java = Path.of(System.getProperty("java.home"), "bin", "java");
        }
        Path classes = Path.of(fixture.getProtectionDomain().getCodeSource().getLocation().toURI());
        Process child = new ProcessBuilder(java.toString(),
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:0",
                "-cp", classes.toString(), fixture.getName()).redirectErrorStream(true).start();
        try (BufferedReader output = new BufferedReader(new InputStreamReader(child.getInputStream()));
             DebuggerSessionController controller = new DebuggerSessionController(name ->
                     name.startsWith(fixture.getName()) ? source : null)) {
            CompletableFuture.runAsync(() -> {
                try {
                    String read;
                    while ((read = output.readLine()) != null) if (read.equals("ready")) return;
                    throw new AssertionError("Debuggee exited before ready");
                } catch (Exception e) { throw new RuntimeException(e); }
            }).get(10, TimeUnit.SECONDS);
            CodeModeJobService jobs = new CodeModeJobService(() -> false, new NoOpTransport(),
                    Clock.systemUTC());
            DebuggerMcpService debugger = new DebuggerMcpService(() -> controller, name -> source);
            try (CompanionMcpServer server = new CompanionMcpServer(temporaryDirectory.resolve("data"), jobs, 0, debugger)) {
                server.start();
                String base = server.endpointUrl().substring(0, server.endpointUrl().length() - 4);
                var transport = HttpClientStreamableHttpTransport.builder(base).endpoint("/mcp").build();
                try (McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(35)).build()) {
                    client.initialize();
                    assertEquals(12, client.listTools().tools().stream().filter(t -> t.name().startsWith("debugger_")).count());
                    Map<String, Object> initial = call(client, "debugger_status", Map.of());
                    assertEquals("unavailable", initial.get("phase"));
                    error(client, "debugger_control", Map.of("action", "attach"));
                    error(client, "debugger_variables", Map.of("pause_id", "bad", "frame_id", 1, "value_ref", 2));
                    controller.acceptTarget(new DebugTargetDescriptor("fixture", "MCP debuggee", child.pid()));
                    Map<String, Object> detached = call(client, "debugger_wait", Map.of(
                            "after_revision", initial.get("revision"), "wait_ms", 5000));
                    assertEquals("detached", detached.get("phase"));
                    Map<String, Object> bpArgs = Map.of("binary_name", fixture.getName(), "line", line);
                    assertEquals("unbound", object(call(client, "debugger_breakpoint_set", bpArgs), "breakpoint").get("state"));
                    call(client, "debugger_control", Map.of("action", "attach"));
                    List<Map<String, Object>> threads = array(call(client, "debugger_threads", Map.of()), "threads");
                    Object mainThread = threads.stream().filter(t -> "main".equals(t.get("name"))).findFirst().orElseThrow().get("id");
                    Map<String, Object> manualPause = call(client, "debugger_control", Map.of(
                            "action", "pause", "thread_id", mainThread, "wait_ms", 5000));
                    assertEquals("paused", manualPause.get("phase"));
                    call(client, "debugger_control", Map.of("action", "continue", "pause_id", object(manualPause, "pause").get("id")));
                    child.getOutputStream().write(1);
                    child.getOutputStream().flush();
                    Map<String, Object> stopped = awaitPause(client);
                    String pauseId = (String) object(stopped, "pause").get("id");
                    List<Map<String, Object>> frames = array(call(client, "debugger_frames", Map.of("pause_id", pauseId)), "frames");
                    Object frameId = frames.getFirst().get("id");
                    assertEquals(fixture.getName(), frames.getFirst().get("binary_name"));
                    List<Map<String, Object>> locals = array(call(client, "debugger_variables", Map.of(
                            "pause_id", pauseId, "frame_id", frameId)), "variables");
                    Map<String, Object> payload = named(locals, "payload");
                    List<Map<String, Object>> fields = array(call(client, "debugger_variables", Map.of(
                            "pause_id", pauseId, "value_ref", payload.get("value_ref"))), "variables");
                    assertEquals("5", named(fields, "amount").get("value"));
                    Map<String, Object> page = call(client, "debugger_variables", Map.of(
                            "pause_id", pauseId, "value_ref", named(locals, "values").get("value_ref"), "start", 1, "count", 1));
                    assertEquals("4", array(page, "variables").getFirst().get("value"));
                    assertEquals(true, page.get("truncated"));
                    Map<String, Object> evaluated = call(client, "debugger_evaluate", Map.of(
                            "pause_id", pauseId, "frame_id", frameId, "source", "counter + payload.amount", "wait_ms", 5000));
                    assertEquals("succeeded", evaluated.get("state"));
                    assertEquals("46", object(evaluated, "result").get("value"));
                    assertEquals("46", object(call(client, "debugger_evaluation_wait", Map.of(
                            "operation_id", evaluated.get("operation_id"), "wait_ms", 0)), "result").get("value"));
                    error(client, "debugger_evaluate", Map.of("pause_id", "stale", "frame_id", frameId, "source", "counter++"));
                    error(client, "debugger_variables", Map.of("pause_id", pauseId, "value_ref", 999999));
                    Map<String, Object> step = call(client, "debugger_control", Map.of(
                            "action", "step_into", "pause_id", pauseId, "wait_ms", 5000));
                    assertEquals("paused", step.get("phase"));
                    assertNotEquals(pauseId, object(step, "pause").get("id"));
                    error(client, "debugger_control", Map.of("action", "continue", "pause_id", pauseId));
                    error(client, "debugger_frames", Map.of("pause_id", pauseId));
                    step = call(client, "debugger_control", Map.of("action", "step_out",
                            "pause_id", object(step, "pause").get("id"), "wait_ms", 5000));
                    assertEquals("paused", step.get("phase"));
                    step = call(client, "debugger_control", Map.of("action", "step_over",
                            "pause_id", object(step, "pause").get("id"), "wait_ms", 5000));
                    assertEquals("paused", step.get("phase"));
                    call(client, "debugger_breakpoint_set", Map.of("binary_name", fixture.getName(), "line", line, "enabled", false));
                    assertEquals("disabled", array(call(client, "debugger_breakpoints", Map.of()), "breakpoints").getFirst().get("state"));
                    assertEquals(true, call(client, "debugger_breakpoint_remove", bpArgs).get("removed"));
                    assertEquals(false, call(client, "debugger_breakpoint_remove", bpArgs).get("removed"));
                    assertEquals("detached", call(client, "debugger_control", Map.of("action", "detach")).get("phase"));
                    assertTrue(child.isAlive(), "Detach must not terminate Minecraft");
                }
            }
        } finally {
            child.getOutputStream().close();
            if (!child.waitFor(3, TimeUnit.SECONDS)) {
                child.destroyForcibly();
                assertTrue(child.waitFor(5, TimeUnit.SECONDS));
            }
        }
    }

    private static Map<String, Object> awaitPause(McpSyncClient client) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        Map<String, Object> state = call(client, "debugger_status", Map.of());
        while (!"paused".equals(state.get("phase")) && System.nanoTime() < deadline) {
            state = call(client, "debugger_wait", Map.of("after_revision", state.get("revision"), "wait_ms", 2000));
        }
        assertEquals("paused", state.get("phase"), state.toString());
        return state;
    }

    private static Map<String, Object> call(McpSyncClient client, String name, Map<String, Object> args) {
        var result = client.callTool(McpSchema.CallToolRequest.builder(name).arguments(args).build());
        assertFalse(Boolean.TRUE.equals(result.isError()), result.toString());
        Map<String, Object> value = cast(result.structuredContent());
        DebuggerMcpToolCatalogTest.assertOutput(name, value);
        assertEquals(1, result.content().size());
        McpSchema.TextContent text = assertInstanceOf(McpSchema.TextContent.class, result.content().getFirst());
        assertEquals(new Gson().toJsonTree(value), JsonParser.parseString(text.text()));
        return value;
    }

    private static void error(McpSyncClient client, String name, Map<String, Object> args) {
        var result = client.callTool(McpSchema.CallToolRequest.builder(name).arguments(args).build());
        assertTrue(Boolean.TRUE.equals(result.isError()), result.toString());
        DebuggerMcpToolCatalogTest.assertOutput(name, cast(result.structuredContent()));
    }

    @SuppressWarnings("unchecked") private static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }
    private static Map<String, Object> object(Map<String, Object> map, String key) { return cast(map.get(key)); }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> array(Map<String, Object> map, String key) {
        return (List<Map<String, Object>>) map.get(key);
    }
    private static Map<String, Object> named(List<Map<String, Object>> values, String name) {
        return values.stream().filter(v -> name.equals(v.get("name"))).findFirst().orElseThrow();
    }
    private static final class NoOpTransport implements CodeModeJobService.Transport {
        public void execute(int id, String source, CodeModeJobService.ExecutionSide side, CodeModeJobService.ExecutionEnvironment environment, Consumer<ExecutionResult> failureHandler) { }
        public void cancel(int id) { }
    }
}
