package com.github.minecraft_ta.totalDebugCompanion.mcp;

import com.github.minecraft_ta.totalDebugCompanion.messages.script.ScriptStatusMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeModeJobServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void recordsExactSourceAndTerminalOutput() throws Exception {
        FakeTransport transport = new FakeTransport();
        try (CodeModeJobService service = service(transport, true)) {
            CodeModeJobService.JobSnapshot submitted = service.submit(
                    "        logln(List.of(\"proof\")); return java.util.Map.of(\"values\", List.of(1, 2));",
                    List.of("java.util.List"),
                    CodeModeJobService.ExecutionSide.CLIENT,
                    CodeModeJobService.ExecutionEnvironment.THREAD
            );

            assertEquals(CodeModeJobService.JobState.COMPILING, submitted.state());
            assertEquals(-1, submitted.scriptId());
            assertEquals(1, transport.executions.size());
            assertTrue(transport.executions.getFirst().source.contains("import java.util.List;"));
            assertTrue(transport.executions.getFirst().source.contains("class McpCodeJob1 extends BaseScript"));
            assertTrue(transport.executions.getFirst().source.contains("public Object run() throws Throwable"));
            assertTrue(transport.executions.getFirst().source.contains("return java.util.Map.of"));
            assertFalse(transport.executions.getFirst().source.contains("resultValue"));
            assertEquals(64, submitted.sourceSha256().length());
            assertTrue(Files.isRegularFile(Path.of((String) submitted.artifacts().get("source"))));

            service.acceptStatus(-1, ScriptStatusMessage.Type.COMPILATION_COMPLETED, "", null, "");
            assertEquals(
                    CodeModeJobService.JobState.RUNNING,
                    service.get(submitted.jobId()).orElseThrow().state()
            );

            service.acceptStatus(
                    -1,
                    ScriptStatusMessage.Type.RUN_COMPLETED,
                    "[proof]\n",
                    "{\"values\":[1,2]}",
                    ""
            );
            CodeModeJobService.JobSnapshot completed = service.get(submitted.jobId()).orElseThrow();
            assertEquals(CodeModeJobService.JobState.SUCCEEDED, completed.state());
            assertEquals("[proof]\n", completed.output());
            assertTrue(completed.resultPresent());
            assertEquals(java.util.Map.of("values", List.of(1.0, 2.0)), completed.result());
            assertTrue(service.readArtifact(submitted.jobId(), "source").contains("logln"));
            assertTrue(service.readArtifact(submitted.jobId(), "job").contains("\"state\": \"succeeded\""));
        }
    }

    @Test
    void preservesLogsWhenExecutionFails() {
        FakeTransport transport = new FakeTransport();
        try (CodeModeJobService service = service(transport, true)) {
            CodeModeJobService.JobSnapshot submitted = service.submit(
                    "log(\"before\"); throw new RuntimeException();",
                    List.of(),
                    CodeModeJobService.ExecutionSide.CLIENT,
                    CodeModeJobService.ExecutionEnvironment.THREAD
            );

            service.acceptStatus(-1, ScriptStatusMessage.Type.COMPILATION_COMPLETED, "", null, "");
            service.acceptStatus(
                    -1,
                    ScriptStatusMessage.Type.RUN_EXCEPTION,
                    "before",
                    null,
                    "java.lang.RuntimeException: boom"
            );

            CodeModeJobService.JobSnapshot failed = service.get(submitted.jobId()).orElseThrow();
            assertEquals(CodeModeJobService.JobState.FAILED, failed.state());
            assertEquals("before", failed.output());
            assertFalse(failed.resultPresent());
            assertEquals("java.lang.RuntimeException: boom", failed.error());
            assertEquals(
                    java.util.Set.of("job_id", "state", "logs", "error"),
                    failed.responseMap().keySet()
            );
        }
    }

    @Test
    void waitsForTerminalStateWithoutPolling() throws Exception {
        FakeTransport transport = new FakeTransport();
        try (CodeModeJobService service = service(transport, true)) {
            CodeModeJobService.JobSnapshot submitted = service.submit(
                    "return 42;",
                    List.of(),
                    CodeModeJobService.ExecutionSide.CLIENT,
                    CodeModeJobService.ExecutionEnvironment.THREAD
            );
            AtomicReference<CodeModeJobService.JobSnapshot> waited = new AtomicReference<>();
            Thread waiter = Thread.startVirtualThread(() -> waited.set(service.waitFor(submitted.jobId(), 5_000)));

            service.acceptStatus(-1, ScriptStatusMessage.Type.COMPILATION_COMPLETED, "", null, "");
            service.acceptStatus(-1, ScriptStatusMessage.Type.RUN_COMPLETED, "", "42", "");
            waiter.join();

            assertEquals(CodeModeJobService.JobState.SUCCEEDED, waited.get().state());
            assertEquals(42.0, waited.get().result());
            assertEquals(
                    CodeModeJobService.JobState.SUCCEEDED,
                    service.waitFor(submitted.jobId(), 0).state()
            );
        }
    }

    @Test
    void cancellationUsesTheSameNegativeScriptId() {
        FakeTransport transport = new FakeTransport();
        try (CodeModeJobService service = service(transport, true)) {
            CodeModeJobService.JobSnapshot submitted = service.submit(
                    "        Thread.sleep(10_000L); return null;",
                    List.of(),
                    CodeModeJobService.ExecutionSide.CLIENT,
                    CodeModeJobService.ExecutionEnvironment.THREAD
            );

            assertTrue(service.cancel(submitted.jobId()));
            assertEquals(List.of(-1), transport.cancelledScriptIds);
            assertEquals(
                    CodeModeJobService.JobState.CANCELLING,
                    service.get(submitted.jobId()).orElseThrow().state()
            );

            service.acceptStatus(
                    -1,
                    ScriptStatusMessage.Type.RUN_EXCEPTION,
                    "partial output",
                    null,
                    "Script run cancelled"
            );
            CodeModeJobService.JobSnapshot cancelled = service.get(submitted.jobId()).orElseThrow();
            assertEquals(CodeModeJobService.JobState.CANCELLED, cancelled.state());
            assertEquals("partial output", cancelled.output());
            assertEquals("Script run cancelled", cancelled.error());
            assertFalse(service.cancel(submitted.jobId()));
        }
    }

    @Test
    void rejectsCodeWhenMinecraftExecutionIsUnavailable() {
        try (CodeModeJobService service = service(new FakeTransport(), false)) {
            IllegalStateException exception = assertThrows(
                    IllegalStateException.class,
                    () -> service.submit(
                            "return 1;",
                            List.of(),
                            CodeModeJobService.ExecutionSide.CLIENT,
                            CodeModeJobService.ExecutionEnvironment.THREAD
                    )
            );
            assertTrue(exception.getMessage().contains("not available"));
        }
    }

    @Test
    void capturesRuntimeIdentityAndFinishesActiveJobsOnDisconnect() {
        FakeTransport transport = new FakeTransport();
        try (CodeModeJobService service = new CodeModeJobService(
                () -> true,
                transport,
                source -> "abstract class BaseScript { abstract Object run() throws Throwable; }\n" + source,
                () -> java.util.Map.of(
                        "profile_id", "instance-a",
                        "runtime_signature", "sha256:abc"
                ),
                this.temporaryDirectory.resolve("artifacts"),
                Clock.fixed(Instant.parse("2026-08-23T12:00:00Z"), ZoneOffset.UTC)
        )) {
            CodeModeJobService.JobSnapshot submitted = service.submit(
                    "return 1;",
                    List.of(),
                    CodeModeJobService.ExecutionSide.CLIENT,
                    CodeModeJobService.ExecutionEnvironment.THREAD
            );

            assertEquals("instance-a", submitted.runtime().get("profile_id"));
            service.runtimeDisconnected();
            CodeModeJobService.JobSnapshot disconnected = service.get(submitted.jobId()).orElseThrow();
            assertEquals(CodeModeJobService.JobState.DISCONNECTED, disconnected.state());
            assertTrue(disconnected.error().contains("disconnected"));
        }
    }

    private CodeModeJobService service(FakeTransport transport, boolean available) {
        return new CodeModeJobService(
                () -> available,
                transport,
                source -> "abstract class BaseScript { abstract Object run() throws Throwable; }\n" + source,
                this.temporaryDirectory.resolve("artifacts"),
                Clock.fixed(Instant.parse("2026-08-23T12:00:00Z"), ZoneOffset.UTC)
        );
    }

    private static final class FakeTransport implements CodeModeJobService.Transport {
        private final List<Execution> executions = new ArrayList<>();
        private final List<Integer> cancelledScriptIds = new ArrayList<>();

        @Override
        public void execute(
                int scriptId,
                String source,
                CodeModeJobService.ExecutionSide side,
                CodeModeJobService.ExecutionEnvironment environment
        ) {
            this.executions.add(new Execution(scriptId, source, side, environment));
        }

        @Override
        public void cancel(int scriptId) {
            this.cancelledScriptIds.add(scriptId);
        }
    }

    private record Execution(
            int scriptId,
            String source,
            CodeModeJobService.ExecutionSide side,
            CodeModeJobService.ExecutionEnvironment environment
    ) {
    }
}
