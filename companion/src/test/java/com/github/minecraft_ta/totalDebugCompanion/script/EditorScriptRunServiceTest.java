package com.github.minecraft_ta.totalDebugCompanion.script;

import com.github.minecraft_ta.totalDebugCompanion.testui.UiTest;
import com.github.minecraft_ta.totalDebugCompanion.CompanionApplication;
import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.GlobalConfig;
import com.github.minecraft_ta.totalDebugCompanion.model.ScriptView;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionLaunchConfiguration;
import com.github.minecraft_ta.totalDebugCompanion.ui.EditorContext;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.FileTreeView;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.ScriptFileActions;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.MainWindow;
import com.github.minecraft_ta.totalDebugCompanion.notification.NotificationCenter;
import com.github.minecraft_ta.totalDebugCompanion.notification.NotificationCenter.Severity;
import com.github.minecraft_ta.totalDebugCompanion.notification.NotificationCenter.Source;
import com.github.minecraft_ta.totalDebugCompanion.project.ProjectScope;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionSession;
import com.github.minecraft_ta.totalDebugCompanion.storage.InstanceState;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeIndexService.ReadySnapshot;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResult;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionStatus;
import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptExecutionEnvironment;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ExecutionResultMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.RunScriptMessage;
import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.message.impl.DefaultMessageBus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import javax.swing.SwingUtilities;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import com.github.minecraft_ta.totaldebug.storage.CacheFiles;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class EditorScriptRunServiceTest {
    @TempDir Path directory;
    private static final String CODE = "import fixture.ScriptProgram; public class Probe extends ScriptProgram { public Object run() { return 1; } }";

    @UiTest
    @Test void closedEditorDoesNotPermitMovingSourcesOfAnUnconfirmedRun() throws Exception {
        Path home = Files.createDirectories(directory.resolve("app"));
        GlobalConfig.getInstance().loadFrom(home);
        var configure = CompanionApp.class.getDeclaredMethod("configureLookAndFeel");
        configure.setAccessible(true);
        edt(() -> { configure.invoke(null); return null; });
        try (var fixture = new Fixture(true);
             var app = new CompanionApplication(new CompanionLaunchConfiguration(home), "test-token")) {
            app.openProject(CompanionProfile.forGame(Files.createDirectories(directory.resolve("game")))).get(10, TimeUnit.SECONDS);
            var project = app.requireProject();
            var files = project.scriptFiles();
            Path folder = files.create(files.root(), "Folder", true, "");
            Path script = files.create(folder, "Test", false, "return 1;");
            Path destination = files.create(files.root(), "Destination", true, "");
            MainWindow window = edt(app::createWindow);
            var context = window.editorContext();
            var runContext = new EditorContext(context.astCache(), context.analysisExecutor(), window, project,
                    context.insights(), context.debugger(), context.navigation(), context.scripts(),
                    fixture.notifications, fixture.runs, context.inspectVariable());
            var treeField = MainWindow.class.getDeclaredField("fileTreeView");
            treeField.setAccessible(true);
            var actions = edt(() -> new ScriptFileActions(window, window.getEditorTabs(),
                    (FileTreeView) treeField.get(window), () -> runContext));
            var view = edt(() -> new ScriptView(runContext, script));
            edt(() -> window.getEditorTabs().openEditorTab(view)).get(10, TimeUnit.SECONDS);
            var run = fixture.runs.start(project, Source.capture(project, "Test.tdscript", new NavigationTarget.LocalFile(script)),
                    CODE, false, ScriptExecutionEnvironment.THREAD);
            fixture.awaitSubmission(run);
            run.stop();
            assertFalse(run.state().terminal(), "A failed cancellation does not confirm that the run ended");
            edt(() -> { window.getEditorTabs().closeMatching(editor -> editor == view); return null; });
            assertTrue(edt(() -> window.getEditorTabs().editors().isEmpty()));
            for (Path source : List.of(script, folder)) {
                Path renamed = source.resolveSibling(source.equals(script) ? "Renamed.tdscript" : "RenamedFolder");
                assertThrows(ExecutionException.class, () -> edt(() -> actions.rename(source, renamed)).get(10, TimeUnit.SECONDS));
                assertThrows(ExecutionException.class, () -> edt(() -> actions.move(List.of(source), destination)).get(10, TimeUnit.SECONDS));
                assertThrows(ExecutionException.class, () -> edt(() -> actions.delete(List.of(source), false)).get(10, TimeUnit.SECONDS));
                assertTrue(Files.exists(source));
            }
            fixture.result(run, ExecutionStatus.RUN_COMPLETED);
            edt(() -> actions.move(List.of(folder), destination)).get(10, TimeUnit.SECONDS);
            assertTrue(Files.isRegularFile(destination.resolve("Folder/Test.tdscript")));
        }
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    @Test void cancellingAQueuedCompileIsInformationalAndCloseDuringProjectSwitchIsSilent() throws Exception {
        try (var fixture = new Fixture(true); var worker = Executors.newSingleThreadExecutor()) {
            var locked = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var holding = worker.submit(() -> {
                try { CacheFiles.locked(directory, () -> { locked.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)); return null; }); }
                catch (Exception failure) { throw new AssertionError(failure); }
            });
            assertTrue(locked.await(5, TimeUnit.SECONDS));
            try {
                var run = fixture.start(CODE);
                run.stop();
                assertTrue(run.state().terminal());
                assertEquals(EditorScriptRunService.Phase.CANCELLED, run.state().phase());
                assertEquals(Severity.INFORMATION, fixture.notifications.snapshot().entries().getFirst().severity());
                fixture.notifications.clear();
                var closing = fixture.start(CODE);
                fixture.project.beginSwitch();
                closing.stop();
                assertTrue(closing.state().terminal());
                assertTrue(fixture.notifications.snapshot().entries().isEmpty());
            } finally { release.countDown(); }
            holding.get(5, TimeUnit.SECONDS);
            // Future.cancel may leave the worker unwinding CacheFiles.locked. Drain that worker
            // before JUnit removes its cache directory, rather than racing a recreated .lock file.
            fixture.compiler.compile("public class Drain {}", "Drain").get(10, TimeUnit.SECONDS);
        }
    }

    @Test void immediateCompilerFailureIsReplayedOnceAndOldResultsCannotCompleteTheNextRun() throws Exception {
        try (var fixture = new Fixture(false)) {
            var first = fixture.start(CODE);
            assertTrue(first.state().terminal());
            assertEquals(ExecutionStatus.COMPILATION_FAILED, first.state().result().result().status());
            var replayed = new AtomicInteger();
            first.subscribe(state -> { if (state.terminal()) replayed.incrementAndGet(); }).run();
            assertEquals(1, replayed.get());
            var second = fixture.start(CODE);
            assertNotEquals(first.id(), second.id());
            fixture.result(first, ExecutionStatus.RUN_COMPLETED);
            assertEquals(2, fixture.notifications.snapshot().entries().size());
        }
    }

    @Test void detachedViewAndUnrelatedExecutionDoNotLoseOrDuplicateEditorOutcome() throws Exception {
        try (var fixture = new Fixture(true)) {
            var run = fixture.start(CODE);
            fixture.awaitSubmission(run);
            var changes = new AtomicInteger();
            var detach = run.subscribe(state -> changes.incrementAndGet());
            detach.run();
            int before = changes.get();
            fixture.bus.deliver(new ExecutionResultMessage(-1, ExecutionResult.fromStatus(ExecutionStatus.RUN_COMPLETED, "")));
            fixture.bus.deliver(new ExecutionResultMessage(Integer.MAX_VALUE, ExecutionResult.fromStatus(ExecutionStatus.RUN_COMPLETED, "")));
            assertTrue(fixture.notifications.snapshot().entries().isEmpty());
            fixture.result(run, ExecutionStatus.RUN_COMPLETED);
            fixture.result(run, ExecutionStatus.RUN_COMPLETED);
            assertEquals(before, changes.get());
            assertTrue(fixture.runs.activeRuns().isEmpty());
            assertEquals(1, fixture.notifications.snapshot().entries().size());
            assertEquals(Severity.SUCCESS, fixture.notifications.snapshot().entries().getFirst().severity());
        }
    }

    @Test void realCompilationDiagnosticsReachTheViewAndHistory() throws Exception {
        try (var fixture = new Fixture(true)) {
            var run = fixture.start(CODE.replace("return 1", "return missing"));
            var complete = new CompletableFuture<EditorScriptRunService.State>();
            run.subscribe(state -> { if (state.terminal()) complete.complete(state); });
            var state = complete.get(10, TimeUnit.SECONDS);
            assertFalse(state.diagnostics().isEmpty());
            assertEquals(Severity.ERROR, fixture.notifications.snapshot().entries().getFirst().severity());
        }
    }

    @Test void disconnectFinishesEveryViewEvenWhenItsNotificationIsSuppressed() throws Exception {
        try (var fixture = new Fixture(true)) {
            var first = fixture.start(CODE);
            var second = fixture.start(CODE);
            fixture.awaitSubmission(first);
            fixture.awaitSubmission(second);
            fixture.runs.disconnected(true);
            assertTrue(first.state().terminal());
            assertTrue(second.state().terminal());
            assertTrue(fixture.runs.activeRuns().isEmpty());
            assertTrue(fixture.notifications.snapshot().entries().isEmpty());
            var third = fixture.start(CODE);
            fixture.awaitSubmission(third);
            fixture.runs.disconnected(false);
            assertTrue(third.state().terminal());
            assertEquals(1, fixture.notifications.snapshot().entries().size());
            assertTrue(fixture.notifications.snapshot().entries().getFirst().details().contains("could not be confirmed"));
            fixture.result(third, ExecutionStatus.RUN_COMPLETED);
            assertEquals(1, fixture.notifications.snapshot().entries().size());
        }
    }

    @Test void stopRemainsPendingUntilAnActualResultAndRetirementIsQuiet() throws Exception {
        try (var fixture = new Fixture(true)) {
            var run = fixture.start(CODE);
            fixture.awaitSubmission(run);
            run.stop();
            assertEquals("Unable to stop script", fixture.notifications.snapshot().entries().getFirst().message(), "The fixture has no live transport");
            fixture.notifications.clear();
            fixture.result(run, ExecutionStatus.CANCELLATION_PENDING);
            assertEquals(EditorScriptRunService.Phase.STOPPING, run.state().phase());
            assertFalse(run.state().terminal());
            assertTrue(fixture.notifications.snapshot().entries().isEmpty());
            fixture.result(run, ExecutionStatus.RUN_EXCEPTION);
            assertEquals(Severity.ERROR, fixture.notifications.snapshot().entries().getFirst().severity(), "Runtime failure remains factual, not guessed cancellation");
            fixture.notifications.clear();
            var retired = fixture.start(CODE);
            fixture.awaitSubmission(retired);
            fixture.project.beginSwitch();
            retired.stop();
            fixture.result(retired, ExecutionStatus.RUN_EXCEPTION);
            assertTrue(fixture.notifications.snapshot().entries().isEmpty());
        }
    }

    private final class Fixture implements AutoCloseable {
        final NotificationCenter notifications = new NotificationCenter();
        final CompanionSession session = new CompanionSession("editor-run-test-token");
        final ResultBus bus = new ResultBus();
        final LinkedBlockingQueue<RunScriptMessage> sent = new LinkedBlockingQueue<>();
        final ScriptCompilationService compiler = new ScriptCompilationService(sent::add, message -> false);
        final ProjectScope project = new ProjectScope(new Object(), new CompanionProfile("project", directory, directory), InstanceState.inMemory());
        final EditorScriptRunService runs;
        final ReadySnapshot snapshot;
        Fixture(boolean runtime) throws Exception {
            session.server().setMessageBus(bus);
            snapshot = runtime ? ScriptCompilationServiceTest.fixture(directory) : null;
            if (snapshot != null) compiler.bind(snapshot);
            runs = new EditorScriptRunService(new ScriptExecutionService(session, compiler, () -> true), session, notifications);
        }
        EditorScriptRunService.Run start(String code) { return runs.start(project, Source.capture(project, "Test", null), code, false, ScriptExecutionEnvironment.THREAD); }
        void awaitSubmission(EditorScriptRunService.Run run) throws Exception {
            RunScriptMessage message = sent.poll(10, TimeUnit.SECONDS);
            assertNotNull(message);
            assertEquals(run.id(), message.scriptId());
        }
        void result(EditorScriptRunService.Run run, ExecutionStatus status) { bus.deliver(new ExecutionResultMessage(run.id(), ExecutionResult.fromStatus(status, "fixture result"))); }
        @Override public void close() throws Exception {
            runs.close(); compiler.close(); session.close(); notifications.close();
            project.retire(); project.close(); if (snapshot != null) snapshot.close();
            assertTrue(bus.results.isEmpty());
        }
    }

    private static final class ResultBus extends DefaultMessageBus {
        final List<Consumer<ExecutionResultMessage>> results = new CopyOnWriteArrayList<>();
        @Override public <T extends AbstractMessage> void listenAlways(Class<T> type, Object owner, Consumer<T> listener) {
            super.listenAlways(type, owner, listener);
            if (type == ExecutionResultMessage.class) results.add(message -> listener.accept(type.cast(message)));
        }
        @Override public <T extends AbstractMessage> void unregister(Class<T> type, Consumer<T> listener) {
            super.unregister(type, listener);
            if (type == ExecutionResultMessage.class) results.clear();
        }
        void deliver(ExecutionResultMessage message) { results.forEach(listener -> listener.accept(message)); }
    }
}
