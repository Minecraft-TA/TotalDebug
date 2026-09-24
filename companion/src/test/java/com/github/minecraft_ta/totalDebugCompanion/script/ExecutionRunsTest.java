package com.github.minecraft_ta.totalDebugCompanion.script;

import com.github.minecraft_ta.totalDebugCompanion.project.ProjectScope;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionSession;
import com.github.minecraft_ta.totalDebugCompanion.storage.InstanceState;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResult;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionStatus;
import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptExecutionEnvironment;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ExecutionResultMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionRunsTest {
    @TempDir Path directory;

    @Test void allocatesOneIdSpaceAndRoutesEachResultOnlyToItsOpener() throws Exception {
        try (var fixture = new Fixture(true)) {
            var first = new Recorder();
            var second = new Recorder();
            int firstId = fixture.runs.open(first);
            int secondId = fixture.runs.open(second);
            assertEquals(firstId + 1, secondId);

            fixture.deliver(secondId, ExecutionStatus.COMPILATION_COMPLETED);
            fixture.deliver(firstId, ExecutionStatus.RUN_COMPLETED);
            fixture.deliver(firstId, ExecutionStatus.RUN_COMPLETED);
            fixture.deliver(secondId + 100, ExecutionStatus.RUN_COMPLETED);

            assertEquals(List.of("result " + firstId + " RUN_COMPLETED"), first.events,
                    "A terminal result ends the run, so a repeated result is not delivered");
            assertEquals(List.of("result " + secondId + " COMPILATION_COMPLETED"), second.events);
        }
    }

    @Test void lateDisconnectOfAnEarlierConnectionKeepsRunsOfTheCurrentOne() throws Exception {
        try (var fixture = new Fixture(true)) {
            var recorder = new Recorder();
            int id = fixture.runs.open(recorder);
            long current = fixture.session.connection();

            fixture.runs.disconnected(current - 1, false);
            assertTrue(recorder.events.isEmpty());

            fixture.runs.disconnected(current, false);
            fixture.deliver(id, ExecutionStatus.RUN_COMPLETED);
            assertEquals(List.of("disconnected " + id + " false"), recorder.events);
        }
    }

    @Test void localFailureEndsTheRunAndLaterResultsAreIgnored() throws Exception {
        try (var fixture = new Fixture(true)) {
            var recorder = new Recorder();
            int id = fixture.runs.open(recorder);
            // The compiler has no runtime, so it reports the failure while the run is submitted.
            assertTrue(fixture.runs.submit(id, fixture.project, "public class Probe {}", false, ScriptExecutionEnvironment.THREAD));
            fixture.deliver(id, ExecutionStatus.RUN_COMPLETED);
            assertEquals(List.of("failed " + id + " COMPILATION_FAILED"), recorder.events);
        }
    }

    @Test void unacceptedSubmissionForgetsTheRun() throws Exception {
        try (var fixture = new Fixture(false)) {
            var recorder = new Recorder();
            int id = fixture.runs.open(recorder);
            assertFalse(fixture.runs.submit(id, fixture.project, "public class Probe {}", false, ScriptExecutionEnvironment.THREAD));
            fixture.deliver(id, ExecutionStatus.RUN_COMPLETED);
            fixture.runs.disconnectAll(false);
            assertTrue(recorder.events.isEmpty());
        }
    }

    @Test void closeEndsEveryRunAsExpectedAndStopsListening() throws Exception {
        var fixture = new Fixture(true);
        var recorder = new Recorder();
        int first = fixture.runs.open(recorder);
        int second = fixture.runs.open(recorder);
        fixture.close();
        assertEquals(2, recorder.events.size());
        assertEquals(Set.of("disconnected " + first + " true", "disconnected " + second + " true"), Set.copyOf(recorder.events));
        assertTrue(fixture.bus.results.isEmpty());
        assertThrows(IllegalStateException.class, () -> fixture.runs.open(recorder));
    }

    private static final class Recorder implements ExecutionRuns.Observer {
        final List<String> events = new CopyOnWriteArrayList<>();

        @Override public void result(int id, ExecutionResult result) {
            events.add("result " + id + " " + result.status());
        }

        @Override public void failed(int id, ScriptCompilationService.Failure failure) {
            events.add("failed " + id + " " + failure.result().status());
        }

        @Override public void disconnected(int id, boolean expected) {
            events.add("disconnected " + id + " " + expected);
        }
    }

    private final class Fixture implements AutoCloseable {
        final CompanionSession session = new CompanionSession("execution-runs-test-token");
        final EditorScriptRunServiceTest.ResultBus bus = new EditorScriptRunServiceTest.ResultBus();
        final ScriptCompilationService compiler = new ScriptCompilationService(message -> false, message -> false);
        final ProjectScope project = new ProjectScope(new Object(), new CompanionProfile("project", directory, directory), InstanceState.inMemory());
        final ExecutionRuns runs;

        Fixture(boolean connected) {
            session.server().setMessageBus(bus);
            runs = new ExecutionRuns(session, new ScriptExecutionService(session, compiler, () -> connected));
        }

        void deliver(int id, ExecutionStatus status) {
            bus.deliver(new ExecutionResultMessage(id, ExecutionResult.fromStatus(status, "fixture result")));
        }

        @Override public void close() throws Exception {
            runs.close();
            compiler.close();
            session.close();
            project.retire();
            project.close();
        }
    }
}
