package com.github.minecraft_ta.totaldebug.script;

import com.github.minecraft_ta.totaldebug.tick.TickPhase;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ScriptRunnerTest {
    @Test
    void threadRunReturnsLogOutput() throws Exception {
        StatusRecorder statuses = new StatusRecorder();
        try (ScriptRunner runner = runner((phase, task) -> { }, statuses, Duration.ofMillis(50))) {
            runner.runScript(1, script("ThreadFixture", "logln(\"hello\");"), ScriptExecutionEnvironment.THREAD);

            Status terminal = statuses.awaitTerminal();

            assertEquals(ExecutionStatus.RUN_COMPLETED, terminal.type(), terminal.error());
            assertEquals("hello" + System.lineSeparator(), terminal.output());
            assertEquals(ExecutionValue.Kind.NULL, terminal.value().kind());
            assertEquals(
                    List.of(ExecutionStatus.COMPILATION_COMPLETED, ExecutionStatus.RUN_COMPLETED),
                    statuses.types()
            );
        }
    }

    @Test
    void threadRunReturnsStructuredResultSeparateFromOutput() throws Exception {
        StatusRecorder statuses = new StatusRecorder();
        try (ScriptRunner runner = runner((phase, task) -> { }, statuses, Duration.ofMillis(50))) {
            runner.runScript(
                    5,
                    normalEditorValueScript(),
                    ScriptExecutionEnvironment.THREAD
            );

            Status terminal = statuses.awaitTerminal();

            assertEquals(ExecutionStatus.RUN_COMPLETED, terminal.type());
            assertEquals("observed", terminal.output());
            assertAnswerMap(terminal.value());
        }
    }

    @Test
    void fallThroughSentinelProducesNoResult() throws Exception {
        StatusRecorder statuses = new StatusRecorder();
        try (ScriptRunner runner = runner((phase, task) -> { }, statuses, Duration.ofMillis(50))) {
            runner.runScript(
                    9,
                    """
                    public class NoResultFixture extends com.github.minecraft_ta.totaldebug.script.ScriptProgram {
                        public Object run() {
                            log("done");
                            return noResult();
                        }
                    }
                    """,
                    ScriptExecutionEnvironment.THREAD
            );

            Status terminal = statuses.awaitTerminal();

            assertEquals(ExecutionStatus.RUN_COMPLETED, terminal.type(), terminal.error());
            assertEquals("done", terminal.output());
            assertNull(terminal.value());
        }
    }

    @Test
    void threadRunReturnsValueFromGeneratedMcpWrapper() throws Exception {
        StatusRecorder statuses = new StatusRecorder();
        try (ScriptRunner runner = runner((phase, task) -> { }, statuses, Duration.ofMillis(50))) {
            runner.runScript(7, valueReturningScript(), ScriptExecutionEnvironment.THREAD);

            Status terminal = statuses.awaitTerminal();

            assertEquals(ExecutionStatus.RUN_COMPLETED, terminal.type());
            assertEquals("observed", terminal.output());
            assertAnswerMap(terminal.value());
        }
    }

    @Test
    void snippetCanReadWriteAndInvokePrivateApplicationMembers() throws Exception {
        StatusRecorder statuses = new StatusRecorder();
        try (ScriptRunner runner = runner((phase, task) -> { }, statuses, Duration.ofMillis(50))) {
            runner.runScript(
                    8,
                    script(
                            "PrivateAccessFixture",
                            """
                            String initial = Boolean.parseBoolean("true") ? "initial" : "unused";
                            var secret = new com.github.minecraft_ta.totaldebug.script.ScriptRunnerTest.SecretFixture(initial);
                            secret.value = "changed";
                            com.github.minecraft_ta.totaldebug.script.ScriptRunnerTest.SecretFixture.prefix = "static";
                            return com.github.minecraft_ta.totaldebug.script.ScriptRunnerTest.SecretFixture
                                    .combineStatic(secret.combine(2));
                            """
                    ),
                    ScriptExecutionEnvironment.THREAD
            );

            Status terminal = statuses.awaitTerminal();

            assertEquals(ExecutionStatus.RUN_COMPLETED, terminal.type(), terminal.error());
            assertEquals(ExecutionValue.Kind.STRING, terminal.value().kind());
            assertEquals("static:changed:2", terminal.value().value().text());
        }
    }

    @Test
    void oversizedResultRetainsUsefulTopLevelChildren() throws Exception {
        StatusRecorder statuses = new StatusRecorder();
        try (ScriptRunner runner = runner((phase, task) -> { }, statuses, Duration.ofMillis(50))) {
            runner.runScript(
                    9,
                    script(
                            "WideResultFixture",
                            """
                            var result = new java.util.ArrayList<Object>();
                            String value = "x".repeat(320);
                            for (int outer = 0; outer < 79; outer++) {
                                var nested = new java.util.ArrayList<String>();
                                for (int inner = 0; inner < 64; inner++) {
                                    nested.add(value);
                                }
                                result.add(nested);
                            }
                            return result;
                            """
                    ),
                    ScriptExecutionEnvironment.THREAD
            );

            Status terminal = statuses.awaitTerminal();
            ExecutionValue snapshot = terminal.value();

            assertEquals(ExecutionStatus.RUN_COMPLETED, terminal.type(), terminal.error());
            assertEquals(79, snapshot.totalChildren());
            assertEquals(79, snapshot.children().size(), "oversize fallback discarded top-level values");
            assertFalse(
                    snapshot.children().getFirst().value().children().isEmpty(),
                    "oversize fallback retained no inspectable nested values"
            );
        }
    }

    @Test
    void failedRunPreservesOutputWrittenBeforeTheException() throws Exception {
        StatusRecorder statuses = new StatusRecorder();
        try (ScriptRunner runner = runner((phase, task) -> { }, statuses, Duration.ofMillis(50))) {
            runner.runScript(
                    6,
                    script(
                            "FailureFixture",
                            "log(\"before failure\"); throw new IllegalStateException(\"boom\");"
                    ),
                    ScriptExecutionEnvironment.THREAD
            );

            Status terminal = statuses.awaitTerminal();

            assertEquals(ExecutionStatus.RUN_EXCEPTION, terminal.type());
            assertEquals("before failure", terminal.output());
            assertTrue(terminal.error().contains("boom"));
        }
    }

    @Test
    void boundsRenderedFailuresBeforeCreatingTheExecutionResult() {
        StackTraceProbe failure = new StackTraceProbe("x".repeat(1_000));
        failure.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("example.Helper", "invoke", "Helper.java", 12),
                new StackTraceElement("ExampleScript", "run", "ExampleScript.java", 4)
        });

        ExecutionText rendered = ScriptRunner.renderStackTrace(failure, "ExampleScript", 128);

        assertEquals(128, rendered.text().length());
        assertTrue(rendered.text().startsWith(failure.getClass().getName() + ": "));
        assertTrue(rendered.truncated());
        assertTrue(rendered.totalCharacters() > rendered.text().length());
        assertFalse(failure.stackTraceRead);
    }

    @Test
    void tickRunExecutesInlineOnTheDrainingThread() throws Exception {
        StatusRecorder statuses = new StatusRecorder();
        AtomicReference<Runnable> scheduled = new AtomicReference<>();
        AtomicReference<TickPhase> phase = new AtomicReference<>();
        try (ScriptRunner runner = runner((scheduledPhase, task) -> {
            phase.set(scheduledPhase);
            scheduled.set(task);
        }, statuses, Duration.ofMillis(50))) {
            runner.runScript(
                    2,
                    script("TickFixture", "log(Thread.currentThread().getName());"),
                    ScriptExecutionEnvironment.PRE_TICK
            );
            statuses.awaitCompilation();

            assertEquals(TickPhase.PRE, phase.get());
            assertNotNull(scheduled.get());
            scheduled.get().run();

            Status terminal = statuses.awaitTerminal();
            assertEquals(ExecutionStatus.RUN_COMPLETED, terminal.type());
            assertEquals(Thread.currentThread().getName(), terminal.output());
        }
    }

    @Test
    void queuedTickRunCanBeCancelledBeforeItStarts() throws Exception {
        StatusRecorder statuses = new StatusRecorder();
        AtomicReference<Runnable> scheduled = new AtomicReference<>();
        try (ScriptRunner runner = runner((phase, task) -> scheduled.set(task), statuses, Duration.ofMillis(50))) {
            runner.runScript(3, script("QueuedFixture", "log(\"ran\");"), ScriptExecutionEnvironment.POST_TICK);
            statuses.awaitCompilation();

            runner.stopScript(3);
            Status terminal = statuses.awaitTerminal();
            scheduled.get().run();

            assertEquals(ExecutionStatus.RUN_EXCEPTION, terminal.type());
            assertTrue(terminal.error().contains("before execution"));
            assertEquals(2, statuses.types().size());
        }
    }

    @Test
    void uninterruptibleThreadReportsThatItIsStillRunningAfterTheGracePeriod() throws Exception {
        StatusRecorder statuses = new StatusRecorder();
        String body = """
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(400);
                while (System.nanoTime() < deadline) {
                    Thread.interrupted();
                }
                """;
        try (ScriptRunner runner = runner((phase, task) -> { }, statuses, Duration.ofMillis(25))) {
            runner.runScript(4, script("StubbornFixture", body), ScriptExecutionEnvironment.THREAD);
            statuses.awaitCompilation();
            awaitExecutionStart(runner, 4);

            runner.stopScript(4);
            Status terminal = statuses.awaitTerminal();

            assertEquals(ExecutionStatus.RUN_EXCEPTION, terminal.type());
            assertTrue(terminal.error().contains("still running"));
        }
    }

    private static ScriptRunner runner(
            ScriptTickScheduler tickScheduler,
            ExecutionResultSink resultSink,
            Duration grace
    ) {
        ExecutorService compilerExecutor = Executors.newSingleThreadExecutor(ScriptRunnerTest::daemonThread);
        ScheduledExecutorService stopExecutor = Executors.newSingleThreadScheduledExecutor(ScriptRunnerTest::daemonThread);
        return new ScriptRunner(
                ScriptCompilerClasspath.fromSources(java.util.List.of()),
                ScriptRunnerTest.class.getClassLoader(),
                tickScheduler,
                resultSink,
                new InMemoryJavaCompiler(),
                grace,
                compilerExecutor,
                stopExecutor
        );
    }

    private static Thread daemonThread(Runnable runnable) {
        Thread thread = new Thread(runnable);
        thread.setDaemon(true);
        return thread;
    }

    private static void awaitExecutionStart(ScriptRunner runner, int scriptId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!runner.isExecutionStarted(scriptId) && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertTrue(runner.isExecutionStarted(scriptId), "script execution did not start");
    }

    private static String script(String className, String body) {
        return """
                public class %s extends com.github.minecraft_ta.totaldebug.script.ScriptProgram {
                    @Override
                    public Object run() throws Throwable {
                        if (Boolean.TRUE.booleanValue()) {
                            %s
                        }
                        return null;
                    }
                }
                """.formatted(className, body);
    }

    private static String normalEditorValueScript() {
        return """
                public class ResultFixture extends com.github.minecraft_ta.totaldebug.script.ScriptProgram {
                    @Override
                    public Object run() throws Throwable {
                        log("observed");
                        return java.util.Map.of("answer", 42);
                    }
                }
                """;
    }

    private static String valueReturningScript() {
        return """
                public class McpValueFixture extends com.github.minecraft_ta.totaldebug.script.ScriptProgram {
                    @Override
                    public Object run() throws Throwable {
                        log("observed");
                        return java.util.Map.of("answer", 42);
                    }
                }
                """;
    }

    private static void assertAnswerMap(ExecutionValue value) {
        assertEquals(ExecutionValue.Kind.MAP, value.kind());
        assertEquals(1, value.children().size());
        ExecutionValue.Child entry = value.children().getFirst();
        assertEquals(ExecutionValue.Kind.STRING, entry.key().kind());
        assertEquals("answer", entry.key().value().text());
        assertEquals(ExecutionValue.Kind.NUMBER, entry.value().kind());
        assertEquals("42", entry.value().value().text());
    }

    public static final class SecretFixture {
        private static String prefix = "initial";
        private String value;

        private SecretFixture(String value) {
            this.value = value;
        }

        private String combine(int suffix) {
            return this.value + ':' + suffix;
        }

        private static String combineStatic(String value) {
            return prefix + ':' + value;
        }
    }

    private static final class StackTraceProbe extends IllegalStateException {
        private boolean stackTraceRead;

        private StackTraceProbe(String message) {
            super(message);
        }

        @Override
        public StackTraceElement[] getStackTrace() {
            this.stackTraceRead = true;
            return super.getStackTrace();
        }
    }

    private record Status(int scriptId, ExecutionResult status) {
        private ExecutionStatus type() {
            return this.status.status();
        }

        private String output() {
            return this.status.logs().text();
        }

        private ExecutionValue value() {
            return this.status.value();
        }

        private String error() {
            return this.status.error().text();
        }
    }

    private static final class StatusRecorder implements ExecutionResultSink {
        private final List<Status> statuses = new CopyOnWriteArrayList<>();
        private final CountDownLatch compilation = new CountDownLatch(1);
        private final CountDownLatch terminal = new CountDownLatch(1);

        @Override
        public void send(int scriptId, ExecutionResult status) {
            this.statuses.add(new Status(scriptId, status));
            if (status.status() == ExecutionStatus.COMPILATION_COMPLETED) {
                this.compilation.countDown();
            } else {
                this.terminal.countDown();
            }
        }

        private void awaitCompilation() throws InterruptedException {
            assertTrue(this.compilation.await(10, TimeUnit.SECONDS), "script compilation did not complete");
        }

        private Status awaitTerminal() throws InterruptedException {
            assertTrue(this.terminal.await(10, TimeUnit.SECONDS), "script run did not report a terminal status");
            return this.statuses.getLast();
        }

        private List<ExecutionStatus> types() {
            return this.statuses.stream().map(Status::type).toList();
        }
    }
}
