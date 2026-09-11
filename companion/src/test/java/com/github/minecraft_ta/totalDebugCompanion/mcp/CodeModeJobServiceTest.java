package com.github.minecraft_ta.totalDebugCompanion.mcp;

import java.util.function.Consumer;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionStatus;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResult;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionText;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigInteger;
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
            assertTrue(transport.executions.getFirst().source.contains(
                    "import com.github.minecraft_ta.totaldebug.script.ScriptProgram;"
            ));
            assertTrue(transport.executions.getFirst().source.contains(
                    "class McpCodeJob1 extends ScriptProgram"
            ));
            assertTrue(transport.executions.getFirst().source.contains("public Object run() throws Throwable"));
            assertTrue(transport.executions.getFirst().source.contains("return java.util.Map.of"));
            assertFalse(transport.executions.getFirst().source.contains("resultValue"));
            assertEquals(transport.executions.getFirst().source, service.source(submitted.jobId()));
            assertFalse(service.source(submitted.jobId()).isBlank());
            try (var files = Files.list(this.temporaryDirectory)) {
                assertEquals(0, files.count());
            }

            service.acceptResult(-1, progress());
            assertEquals(
                    CodeModeJobService.JobState.RUNNING,
                    service.get(submitted.jobId()).orElseThrow().state()
            );

            service.acceptResult(
                    -1,
                    completed("[proof]\n", stringMap("values", sequence(number("1"), number("2"))))
            );
            CodeModeJobService.JobSnapshot completed = service.get(submitted.jobId()).orElseThrow();
            assertEquals(CodeModeJobService.JobState.SUCCEEDED, completed.state());
            assertEquals("[proof]\n", completed.output());
            assertTrue(completed.resultPresent());
            assertEquals(
                    java.util.Map.of("values", List.of(BigInteger.ONE, BigInteger.TWO)),
                    completed.result()
            );
            assertTrue(service.source(submitted.jobId()).contains("logln"));
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

            service.acceptResult(-1, progress());
            service.acceptResult(
                    -1,
                    failed("before", null, "java.lang.RuntimeException: boom")
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

            service.acceptResult(-1, progress());
            service.acceptResult(-1, completed("", number("42")));
            waiter.join();

            assertEquals(CodeModeJobService.JobState.SUCCEEDED, waited.get().state());
            assertEquals(BigInteger.valueOf(42), waited.get().result());
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

            service.acceptResult(
                    -1,
                    failed("partial output", null, "Script run cancelled")
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
                () -> java.util.Map.of(
                        "profile_id", "instance-a",
                        "runtime_signature", "sha256:abc"
                ),
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

    @Test
    void pendingCancellationKeepsWaitingForActualCompletion() {
        FakeTransport transport = new FakeTransport();
        try (CodeModeJobService service = service(transport, true)) {
            var submitted = service.submit("return 42;", List.of(), CodeModeJobService.ExecutionSide.CLIENT,
                    CodeModeJobService.ExecutionEnvironment.THREAD);
            service.acceptResult(submitted.scriptId(), new ExecutionResult(ExecutionStatus.CANCELLATION_PENDING,
                    ExecutionText.empty(), null, ExecutionText.complete("Stop timed out; script is still running")));
            var pending = service.waitFor(submitted.jobId(), 1);
            assertEquals(CodeModeJobService.JobState.CANCELLING, pending.state());
            assertEquals(null, pending.completedAt());
            assertTrue(pending.error().contains("still running"));
            service.acceptResult(submitted.scriptId(), failed("final output", null, "Script run cancelled"));
            var completed = service.get(submitted.jobId()).orElseThrow();
            assertEquals(CodeModeJobService.JobState.CANCELLED, completed.state());
            assertEquals("final output", completed.output());
        }
    }

    @Test
    void switchDisconnectsCompilingJobsBeforeSynchronousCancellationFailure() {
        FakeTransport transport = new FakeTransport();
        try (CodeModeJobService service = service(transport, true)) {
            var submitted = service.submit("return 42;", List.of(), CodeModeJobService.ExecutionSide.CLIENT,
                    CodeModeJobService.ExecutionEnvironment.THREAD);
            transport.onCancel = scriptId -> service.acceptResult(scriptId,
                    new ExecutionResult(ExecutionStatus.COMPILATION_FAILED, ExecutionText.empty(), null,
                            ExecutionText.complete("Compilation cancelled")));
            service.prepareProjectSwitch();
            assertEquals(List.of(submitted.scriptId()), transport.cancelledScriptIds);
            assertEquals(CodeModeJobService.JobState.DISCONNECTED, service.get(submitted.jobId()).orElseThrow().state());
        }
    }

    @Test
    void cancellationTransportFailureDoesNotLoseTheLiveJob() {
        FakeTransport transport = new FakeTransport();
        try (CodeModeJobService service = service(transport, true)) {
            var submitted = service.submit("return 42;", List.of(), CodeModeJobService.ExecutionSide.CLIENT,
                    CodeModeJobService.ExecutionEnvironment.THREAD);
            transport.cancelFailure = new IllegalStateException("fixture transport failure");
            assertTrue(service.cancel(submitted.jobId()));
            var pending = service.waitFor(submitted.jobId(), 1);
            assertEquals(CodeModeJobService.JobState.CANCELLING, pending.state());
            assertEquals(null, pending.completedAt());
            transport.cancelFailure = null;
            assertTrue(service.cancel(submitted.jobId()), "An explicit retry must remain possible");
            service.acceptResult(submitted.scriptId(), completed("completed despite stop", number("42")));
            assertEquals(CodeModeJobService.JobState.SUCCEEDED, service.get(submitted.jobId()).orElseThrow().state());
        }
    }

    private CodeModeJobService service(FakeTransport transport, boolean available) {
        return new CodeModeJobService(
                () -> available,
                transport,
                Clock.fixed(Instant.parse("2026-08-23T12:00:00Z"), ZoneOffset.UTC)
        );
    }

    @Test
    void keepsTransportTextAndItsTruncationMetadataWithoutAnotherCutoff() {
        FakeTransport transport = new FakeTransport();
        try (CodeModeJobService service = service(transport, true)) {
            CodeModeJobService.JobSnapshot submitted = service.submit(
                    "return null;",
                    List.of(),
                    CodeModeJobService.ExecutionSide.CLIENT,
                    CodeModeJobService.ExecutionEnvironment.THREAD
            );
            String retained = "x".repeat(300_000);
            service.acceptResult(-1, new ExecutionResult(
                    ExecutionStatus.RUN_COMPLETED,
                    new ExecutionText(retained, 400_000, true),
                    null,
                    text("")
            ));

            CodeModeJobService.JobSnapshot completed = service.get(submitted.jobId()).orElseThrow();
            assertEquals(retained, completed.output());
            assertTrue(completed.outputTruncated());
            assertEquals(400_000, completed.outputTotalCharacters());
            assertEquals(true, completed.responseMap().get("logs_truncated"));
            assertEquals(400_000, completed.responseMap().get("logs_total_characters"));
        }
    }

    @Test
    void sourceHasTheSameLifetimeAsTheRetainedJobAndActiveWorkIsNotEvicted() {
        FakeTransport transport = new FakeTransport();
        String expired;
        try (CodeModeJobService service = service(transport, true)) {
            var active = service.submit("return 0;", List.of(), CodeModeJobService.ExecutionSide.CLIENT,
                    CodeModeJobService.ExecutionEnvironment.THREAD);
            var first = service.submit("return 1;", List.of(), CodeModeJobService.ExecutionSide.CLIENT,
                    CodeModeJobService.ExecutionEnvironment.THREAD);
            expired = first.jobId();
            service.acceptResult(first.scriptId(), completed("", null));
            for (int i = 0; i < CodeModeJobService.MAX_RETAINED_JOBS; i++) {
                var job = service.submit("return 2;", List.of(), CodeModeJobService.ExecutionSide.CLIENT,
                        CodeModeJobService.ExecutionEnvironment.THREAD);
                service.acceptResult(job.scriptId(), completed("", null));
            }
            assertTrue(service.get(active.jobId()).isPresent());
            assertFalse(service.source(active.jobId()).isBlank());
            assertTrue(service.get(expired).isEmpty());
            assertTrue(assertThrows(IllegalArgumentException.class, () -> service.source(expired))
                    .getMessage().contains("Unknown or expired"));
        }
        try (CodeModeJobService restarted = service(new FakeTransport(), true)) {
            assertThrows(IllegalArgumentException.class, () -> restarted.source(expired));
        }
    }

    private static ExecutionResult progress() {
        return new ExecutionResult(
                ExecutionStatus.COMPILATION_COMPLETED,
                text(""),
                null,
                text("")
        );
    }

    private static ExecutionResult completed(String logs, ExecutionValue value) {
        return new ExecutionResult(
                ExecutionStatus.RUN_COMPLETED,
                text(logs),
                value,
                text("")
        );
    }

    private static ExecutionResult failed(String logs, ExecutionValue value, String error) {
        return new ExecutionResult(
                ExecutionStatus.RUN_EXCEPTION,
                text(logs),
                value,
                text(error)
        );
    }

    private static ExecutionText text(String value) {
        return new ExecutionText(value, value.length(), false);
    }

    private static ExecutionValue number(String value) {
        return value("java.lang.Integer", value, ExecutionValue.Kind.NUMBER, List.of());
    }

    private static ExecutionValue sequence(ExecutionValue... values) {
        List<ExecutionValue.Child> children = java.util.stream.IntStream.range(0, values.length)
                .mapToObj(index -> new ExecutionValue.Child(
                        text(Integer.toString(index)),
                        ExecutionValue.ChildKind.COLLECTION_ELEMENT,
                        null,
                        values[index]
                ))
                .toList();
        return value("java.util.List", "size = " + values.length, ExecutionValue.Kind.COLLECTION, children);
    }

    private static ExecutionValue stringMap(String key, ExecutionValue mapValue) {
        ExecutionValue keyValue = value("java.lang.String", key, ExecutionValue.Kind.STRING, List.of());
        return value(
                "java.util.Map",
                "size = 1",
                ExecutionValue.Kind.MAP,
                List.of(new ExecutionValue.Child(
                        text("0"),
                        ExecutionValue.ChildKind.MAP_ENTRY,
                        keyValue,
                        mapValue
                ))
        );
    }

    private static ExecutionValue value(
            String type,
            String value,
            ExecutionValue.Kind kind,
            List<ExecutionValue.Child> children
    ) {
        int identity = switch (kind) {
            case OPTIONAL, ARRAY, COLLECTION, MAP, OBJECT, REFERENCE -> 1;
            default -> 0;
        };
        return new ExecutionValue(
                text(type),
                text(value),
                text(""),
                kind,
                identity,
                children.size(),
                false,
                children
        );
    }

    private static final class FakeTransport implements CodeModeJobService.Transport {
        private final List<Execution> executions = new ArrayList<>();
        private final List<Integer> cancelledScriptIds = new ArrayList<>();
        private RuntimeException cancelFailure;
        private java.util.function.IntConsumer onCancel = ignored -> { };

        @Override
        public void execute(
                int scriptId,
                String source,
                CodeModeJobService.ExecutionSide side,
                CodeModeJobService.ExecutionEnvironment environment, Consumer<ExecutionResult> failureHandler
        ) {
            this.executions.add(new Execution(scriptId, source, side, environment));
        }

        @Override
        public void cancel(int scriptId) {
            if (this.cancelFailure != null) throw this.cancelFailure;
            this.cancelledScriptIds.add(scriptId);
            this.onCancel.accept(scriptId);
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
