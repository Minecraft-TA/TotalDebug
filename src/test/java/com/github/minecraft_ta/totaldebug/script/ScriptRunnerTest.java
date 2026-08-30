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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptRunnerTest {
    @Test
    void threadRunReturnsLogOutput() throws Exception {
        StatusRecorder statuses = new StatusRecorder();
        try (ScriptRunner runner = runner((phase, task) -> { }, statuses, Duration.ofMillis(50))) {
            runner.runScript(1, script("ThreadFixture", "logln(\"hello\");"), ScriptExecutionEnvironment.THREAD);

            Status terminal = statuses.awaitTerminal();

            assertEquals(ScriptStatusType.RUN_COMPLETED, terminal.type());
            assertEquals("hello" + System.lineSeparator(), terminal.output());
            assertEquals(
                    List.of(ScriptStatusType.COMPILATION_COMPLETED, ScriptStatusType.RUN_COMPLETED),
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
                    script(
                            "ResultFixture",
                            "log(\"observed\"); result(java.util.Map.of(\"answer\", 42));"
                    ),
                    ScriptExecutionEnvironment.THREAD
            );

            Status terminal = statuses.awaitTerminal();

            assertEquals(ScriptStatusType.RUN_COMPLETED, terminal.type());
            assertEquals("observed", terminal.output());
            assertEquals("{\"answer\":42}", terminal.resultJson());
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

            assertEquals(ScriptStatusType.RUN_EXCEPTION, terminal.type());
            assertEquals("before failure", terminal.output());
            assertTrue(terminal.error().contains("boom"));
        }
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
            assertEquals(ScriptStatusType.RUN_COMPLETED, terminal.type());
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

            assertEquals(ScriptStatusType.RUN_EXCEPTION, terminal.type());
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

            assertEquals(ScriptStatusType.RUN_EXCEPTION, terminal.type());
            assertTrue(terminal.error().contains("still running"));
        }
    }

    private static ScriptRunner runner(
            ScriptTickScheduler tickScheduler,
            ScriptStatusSink statusSink,
            Duration grace
    ) {
        ExecutorService compilerExecutor = Executors.newSingleThreadExecutor(ScriptRunnerTest::daemonThread);
        ScheduledExecutorService stopExecutor = Executors.newSingleThreadScheduledExecutor(ScriptRunnerTest::daemonThread);
        return new ScriptRunner(
                "",
                ScriptRunnerTest.class.getClassLoader(),
                tickScheduler,
                statusSink,
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
                import java.io.StringWriter;
                public class %s extends BaseScript {
                    @Override
                    public void run() throws Throwable {
                        %s
                    }
                }
                abstract class BaseScript {
                    private final StringWriter logWriter = new StringWriter();
                    private Object resultValue;
                    private boolean resultSet;
                    public void log(Object value) { this.logWriter.append(String.valueOf(value)); }
                    public void logln(Object value) { log(String.format("%%s%%n", value)); }
                    public void result(Object value) { this.resultValue = value; this.resultSet = true; }
                    public abstract void run() throws Throwable;
                }
                """.formatted(className, body);
    }

    private record Status(int scriptId, ScriptStatus status) {
        private ScriptStatusType type() {
            return this.status.type();
        }

        private String output() {
            return this.status.output();
        }

        private String resultJson() {
            return this.status.resultJson();
        }

        private String error() {
            return this.status.error();
        }
    }

    private static final class StatusRecorder implements ScriptStatusSink {
        private final List<Status> statuses = new CopyOnWriteArrayList<>();
        private final CountDownLatch compilation = new CountDownLatch(1);
        private final CountDownLatch terminal = new CountDownLatch(1);

        @Override
        public void send(int scriptId, ScriptStatus status) {
            this.statuses.add(new Status(scriptId, status));
            if (status.type() == ScriptStatusType.COMPILATION_COMPLETED) {
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

        private List<ScriptStatusType> types() {
            return this.statuses.stream().map(Status::type).toList();
        }
    }
}
