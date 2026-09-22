package com.github.minecraft_ta.totalDebugCompanion;

import com.github.minecraft_ta.totalDebugCompanion.model.ServiceStatus;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.minecraft_ta.totalDebugCompanion.mcp.CodeModeJobService;
import com.github.minecraft_ta.totalDebugCompanion.mcp.ProjectSwitchJobs;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationService;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeIndexService;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionLaunchConfiguration;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import com.github.minecraft_ta.totalDebugCompanion.ui.CompanionUi;
import com.github.minecraft_ta.totaldebug.protocol.CompanionProtocol;
import com.github.minecraft_ta.totaldebug.protocol.ProjectSelectionRequest;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ClientHelloMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ServerHelloMessage;
import com.github.minecraft_ta.totaldebug.storage.CompanionSessionDescriptor;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class CompanionReconnectTest {
    @TempDir Path root;
    private static final String TOKEN = "reconnect-test-token-abcdefghijklmnopqrstuvwxyz";

    @Test void publishingTracksSelectionAndEditorVetoKeepsTheExistingConnection() throws Exception {
        var ui = new TestUi();
        var config = new CompanionLaunchConfiguration(root.resolve("app"));
        try (var app = new CompanionApplication(config, TOKEN, ui)) {
            app.session().bindAndPublish(config);
            assertNull(read(config).selectedProfileId());
            var first = profile("first");
            var second = profile("second");
            app.openProject(first).get(5, TimeUnit.SECONDS);
            assertEquals(first.id(), read(config).selectedProfileId());
            try (var game = socket(config)) {
                assertTrue(handshake(game, first, TOKEN));
                await(app::isConnected);
                ui.allowSwitch = false;
                ui.beforeSwitch = () -> assertNull(assertDoesNotThrow(() -> read(config)).selectedProfileId());
                assertThrows(ExecutionException.class, () -> app.openProject(second).get(5, TimeUnit.SECONDS));
                assertEquals(first, app.currentProject());
                assertEquals(first.id(), read(config).selectedProfileId());
                assertTrue(app.isConnected());
                ui.allowSwitch = true;
                app.openProject(second).get(5, TimeUnit.SECONDS);
                assertEquals(second.id(), read(config).selectedProfileId());
                assertEquals(-1, game.getInputStream().read());
            }
        }
    }

    @Test void reconnectCompletesOnlyAfterAuthenticationAndStaleActionsCannotDisconnectAnotherProject() throws Exception {
        var ui = new TestUi();
        var config = new CompanionLaunchConfiguration(root.resolve("app"));
        try (var app = new CompanionApplication(config, TOKEN, ui)) {
            app.session().bindAndPublish(config);
            var first = profile("first");
            var second = profile("second");
            app.openProject(first).get(5, TimeUnit.SECONDS);
            var oldScope = app.requireProject();
            var request = app.reconnectGame(oldScope);
            app.renameProject(first.id(), null).get(5, TimeUnit.SECONDS); // Drain publication on the same worker.
            assertFalse(request.isDone());
            flushUi();
            assertEquals(ServiceStatus.State.PENDING, ui.game.state());
            try (var game = socket(config)) {
                assertTrue(handshake(game, first, TOKEN));
                request.get(5, TimeUnit.SECONDS);
                assertTrue(app.isConnected());
                flushUi();
                assertEquals(ServiceStatus.State.AVAILABLE, ui.game.state());
                var pending = app.reconnectGame(oldScope);
                assertEquals(-1, game.getInputStream().read());
                app.openProject(second).get(5, TimeUnit.SECONDS);
                assertThrows(ExecutionException.class, () -> pending.get(5, TimeUnit.SECONDS));
            }
            try (var newGame = socket(config)) {
                assertTrue(handshake(newGame, second, TOKEN));
                assertThrows(ExecutionException.class, () -> app.reconnectGame(oldScope).get(5, TimeUnit.SECONDS));
                assertTrue(app.isConnected());
                assertEquals(second, app.currentProject());
            }
        }
    }

    @Test void rejectedAuthenticationReportsOneFailureAndWrongProjectDoesNotFailTheRequestedTarget() throws Exception {
        var ui = new TestUi();
        var config = new CompanionLaunchConfiguration(root.resolve("app"));
        try (var app = new CompanionApplication(config, TOKEN, ui)) {
            app.session().bindAndPublish(config);
            var selected = profile("selected");
            app.openProject(selected).get(5, TimeUnit.SECONDS);
            var request = app.reconnectGame(app.requireProject());
            app.renameProject(selected.id(), null).get(5, TimeUnit.SECONDS);
            try (var wrongGame = socket(config)) {
                assertFalse(handshake(wrongGame, profile("other"), TOKEN));
                assertEquals(-1, wrongGame.getInputStream().read());
                assertFalse(request.isDone());
            }
            await(() -> !app.session().server().isClientConnected());
            try (var invalid = socket(config)) {
                assertFalse(handshake(invalid, selected, "wrong-token"));
                assertThrows(ExecutionException.class, () -> request.get(5, TimeUnit.SECONDS));
            }
            flushUi();
            assertEquals(ServiceStatus.State.FAILED, ui.game.state());
            assertTrue(ui.game.detail().contains("Authentication token rejected"));
            assertEquals(1, app.notifications().snapshot().entries().stream()
                    .filter(entry -> entry.message().equals("Minecraft reconnect failed")).count());
        }
    }

    @Test void pendingReconnectDoesNotBlockProjectWorkAndShutdownCancelsIt() throws Exception {
        var ui = new TestUi();
        var config = new CompanionLaunchConfiguration(root.resolve("app"));
        try (var app = new CompanionApplication(config, TOKEN, ui)) {
            app.session().bindAndPublish(config);
            var selected = profile("selected");
            app.openProject(selected).get(5, TimeUnit.SECONDS);
            var waiting = app.reconnectGame(app.requireProject());
            app.renameProject(selected.id(), "Still responsive").get(5, TimeUnit.SECONDS);
            assertFalse(waiting.isDone());
            app.close();
            assertTrue(waiting.isCompletedExceptionally());
        }
    }

    @Test void failedPublicationPreservesTheOldProjectAndReportsTheFileFailure() throws Exception {
        var ui = new TestUi();
        var config = new CompanionLaunchConfiguration(root.resolve("app"));
        try (var app = new CompanionApplication(config, TOKEN, ui)) {
            app.session().bindAndPublish(config);
            var original = profile("original");
            app.openProject(original).get(5, TimeUnit.SECONDS);
            var scope = app.requireProject();
            Files.delete(config.descriptorFile());
            Files.createDirectory(config.descriptorFile());
            Path blocker = Files.writeString(config.descriptorFile().resolve("blocker"), "blocks replacement");
            assertThrows(ExecutionException.class, () -> app.openProject(profile("next")).get(5, TimeUnit.SECONDS));
            assertSame(scope, app.requireProject());
            assertTrue(scope.isActive());
            flushUi();
            assertEquals(ServiceStatus.State.FAILED, ui.game.state());
            Files.delete(blocker);
            Files.delete(config.descriptorFile());
            app.openProject(original).get(5, TimeUnit.SECONDS);
            assertEquals(original.id(), read(config).selectedProfileId());
        }
    }

    @Test void vetoingASwitchCancelsPendingReconnectWithoutLeavingTheStatusPending() throws Exception {
        var ui = new TestUi();
        var config = new CompanionLaunchConfiguration(root.resolve("app"));
        try (var app = new CompanionApplication(config, TOKEN, ui)) {
            app.session().bindAndPublish(config);
            var original = profile("original");
            app.openProject(original).get(5, TimeUnit.SECONDS);
            var pending = app.reconnectGame(app.requireProject());
            app.renameProject(original.id(), null).get(5, TimeUnit.SECONDS);
            ui.allowSwitch = false;
            assertThrows(ExecutionException.class, () -> app.openProject(profile("other")).get(5, TimeUnit.SECONDS));
            assertTrue(pending.isCompletedExceptionally());
            flushUi();
            assertEquals(ServiceStatus.State.INACTIVE, ui.game.state());
            assertEquals(original.id(), read(config).selectedProfileId());
        }
    }

    @Test void reconnectPublicationFailureDoesNotHideAStillAuthenticatedGame() throws Exception {
        var ui = new TestUi();
        var config = new CompanionLaunchConfiguration(root.resolve("app"));
        try (var app = new CompanionApplication(config, TOKEN, ui)) {
            app.session().bindAndPublish(config);
            var original = profile("original");
            app.openProject(original).get(5, TimeUnit.SECONDS);
            try (var game = socket(config)) {
                assertTrue(handshake(game, original, TOKEN));
                await(app::isConnected);
                Files.delete(config.descriptorFile());
                Files.createDirectory(config.descriptorFile());
                Files.writeString(config.descriptorFile().resolve("blocker"), "blocks publication");
                assertThrows(ExecutionException.class, () -> app.reconnectGame(app.requireProject()).get(5, TimeUnit.SECONDS));
                assertTrue(app.isConnected());
                flushUi();
                assertEquals(ServiceStatus.State.AVAILABLE, ui.game.state());
                assertEquals(1, app.notifications().snapshot().entries().stream()
                        .filter(entry -> entry.message().equals("Minecraft reconnect failed")).count());
            }
        }
    }

    @Test void reconnectPreservesTheIndexAndRetiresPreviousMcpJobsBeforeAttachingAgain() throws Exception {
        var ui = new TestUi();
        var config = new CompanionLaunchConfiguration(root.resolve("app"));
        try (var app = new CompanionApplication(config, TOKEN, ui);
             var snapshot = RuntimeInstallationTest.snapshot(root, "captured")) {
            app.session().bindAndPublish(config);
            var selected = profile("selected");
            app.openProject(selected).get(5, TimeUnit.SECONDS);
            await(() -> app.getRuntimeIndexStatus().phase() == RuntimeIndexService.Phase.EMPTY);
            app.installRuntimeSnapshot(snapshot, RuntimeSnapshotBytecodeSource.fromIndexedSources(snapshot.sources(), snapshot.index()));
            var binding = app.requireProject().requireRuntime();
            var cancelled = new AtomicInteger();
            var jobs = ProjectSwitchJobs.create(ignored -> cancelled.incrementAndGet());
            app.startMcpServer(jobs, 0);
            try (var game = socket(config)) {
                assertTrue(handshake(game, selected, TOKEN));
                var job = jobs.submit("return 42;", List.of(), CodeModeJobService.ExecutionSide.CLIENT,
                        CodeModeJobService.ExecutionEnvironment.THREAD);
                var lossReported = new CountDownLatch(1);
                var pendingWhileConnected = new AtomicBoolean();
                ui.gameChanged = status -> {
                    if (status.state() != ServiceStatus.State.PENDING) return;
                    if (app.isConnected()) pendingWhileConnected.set(true);
                    else lossReported.countDown();
                };
                var requested = new AtomicReference<CompletableFuture<Void>>();
                SwingUtilities.invokeAndWait(() -> requested.set(app.reconnectGame(app.requireProject())));
                var reconnect = requested.get();
                assertEquals(-1, game.getInputStream().read());
                assertTrue(pendingWhileConnected.get());
                assertTrue(lossReported.await(5, TimeUnit.SECONDS), "Actual disconnect must publish status even when it stays PENDING");
                app.renameProject(selected.id(), null).get(5, TimeUnit.SECONDS);
                assertEquals(1, cancelled.get());
                assertEquals(CodeModeJobService.JobState.DISCONNECTED, jobs.get(job.jobId()).orElseThrow().state());
                assertSame(binding, app.requireProject().runtime());
                assertFalse(snapshot.index().isDestroyed());
                try (var replacement = socket(config)) {
                    assertTrue(handshake(replacement, selected, TOKEN));
                    reconnect.get(5, TimeUnit.SECONDS);
                    assertEquals(CodeModeJobService.JobState.DISCONNECTED, jobs.get(job.jobId()).orElseThrow().state());
                    assertSame(binding, app.requireProject().runtime());
                }
            }
        }
    }

    @Test void authenticationDuringReconnectCleanupCannotCompleteTheRequest() throws Exception {
        var ui = new TestUi();
        var config = new CompanionLaunchConfiguration(root.resolve("app"));
        var cleaning = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var app = new CompanionApplication(config, TOKEN, ui)) {
            app.session().bindAndPublish(config);
            var selected = profile("selected");
            app.openProject(selected).get(5, TimeUnit.SECONDS);
            var jobs = ProjectSwitchJobs.create(ignored -> {
                cleaning.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            });
            app.startMcpServer(jobs, 0);
            jobs.submit("return 1;", List.of(), CodeModeJobService.ExecutionSide.CLIENT,
                    CodeModeJobService.ExecutionEnvironment.THREAD);
            var reconnect = app.reconnectGame(app.requireProject());
            try {
                assertTrue(cleaning.await(5, TimeUnit.SECONDS));
                try (var earlyGame = socket(config)) {
                    assertTrue(handshake(earlyGame, selected, TOKEN));
                    await(app::isConnected);
                    flushUi();
                    assertFalse(reconnect.isDone(), "A session that reconnect will still tear down cannot complete the request");
                    assertEquals(ServiceStatus.State.PENDING, ui.game.state());
                    release.countDown();
                    assertEquals(-1, earlyGame.getInputStream().read());
                }
                app.renameProject(selected.id(), null).get(5, TimeUnit.SECONDS);
                try (var replacement = socket(config)) {
                    assertTrue(handshake(replacement, selected, TOKEN));
                    reconnect.get(5, TimeUnit.SECONDS);
                    assertTrue(app.isConnected());
                }
            } finally { release.countDown(); }
        }
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void cancelledReconnectRestoresTheAdvertisementAfterWithdrawal() throws Exception {
        var config = new CompanionLaunchConfiguration(root.resolve("app"));
        try (var app = new CompanionApplication(config, TOKEN, new TestUi())) {
            app.session().bindAndPublish(config);
            var selected = profile("selected");
            app.openProject(selected).get(5, TimeUnit.SECONDS);
            try (var reader = Files.newBufferedReader(config.descriptorFile())) {
                var reconnect = app.reconnectGame(app.requireProject());
                await(() -> {
                    try (var files = Files.list(config.descriptorFile().getParent())) {
                        return files.anyMatch(path -> path.getFileName().toString().startsWith(".td-"));
                    } catch (IOException failure) { throw new AssertionError(failure); }
                });
                var cancel = CompanionApplication.class.getDeclaredMethod("cancelReconnect", String.class);
                cancel.setAccessible(true);
                cancel.invoke(app, "Cancelled during withdrawal");
                assertTrue(reconnect.isCompletedExceptionally());
            }
            app.renameProject(selected.id(), null).get(5, TimeUnit.SECONDS);
            assertEquals(selected.id(), read(config).selectedProfileId(), "Cancellation must not leave discovery disabled");
        }
    }

    @Test void startupPublicationWaitsForAnInProgressProjectSwitch() throws Exception {
        var ui = new TestUi();
        var config = new CompanionLaunchConfiguration(root.resolve("app"));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var app = new CompanionApplication(config, TOKEN, ui)) {
            var first = profile("first");
            var second = profile("second");
            app.openProject(first).get(5, TimeUnit.SECONDS);
            app.startMcpServer(ProjectSwitchJobs.create(), 0);
            ui.beforeSwitch = () -> {
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            };
            var switched = app.openProject(second);
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var startedCall = new CountDownLatch(1);
                var started = CompletableFuture.runAsync(() -> {
                    startedCall.countDown();
                    try { app.start(); }
                    catch (IOException failure) { throw new AssertionError(failure); }
                });
                assertTrue(startedCall.await(2, TimeUnit.SECONDS));
                Thread.sleep(100);
                assertFalse(started.isDone());
                assertFalse(Files.exists(config.descriptorFile()), "Startup publication must use the project worker");
                release.countDown();
                switched.get(5, TimeUnit.SECONDS);
                started.get(5, TimeUnit.SECONDS);
                assertEquals(second.id(), read(config).selectedProfileId());
            } finally { release.countDown(); }
        }
    }

    @Test void blockingBootstrapCannotRunOnTheEventDispatchThread() throws Exception {
        var config = new CompanionLaunchConfiguration(root.resolve("app"));
        try (var app = new CompanionApplication(config, TOKEN)) {
            SwingUtilities.invokeAndWait(() -> assertThrows(IllegalStateException.class, app::start));
            assertFalse(Files.exists(config.descriptorFile()));
        }
    }

    private CompanionProfile profile(String name) throws IOException {
        return CompanionProfile.forGame(Files.createDirectories(root.resolve(name)));
    }

    private static CompanionSessionDescriptor read(CompanionLaunchConfiguration config) throws IOException {
        return CompanionSessionDescriptor.read(config.descriptorFile(), CompanionProtocol.VERSION);
    }

    private static Socket socket(CompanionLaunchConfiguration config) throws IOException {
        var socket = new Socket("127.0.0.1", read(config).port());
        socket.setSoTimeout(5000);
        return socket;
    }

    private static boolean handshake(Socket socket, CompanionProfile profile, String token) throws IOException {
        byte[] bytes = ProjectSelectionRequest.encode(new ClientHelloMessage(CompanionProtocol.VERSION, token, profile.id(),
                profile.dataDirectory().toString(), profile.workspaceDirectory().toString()));
        var output = new DataOutputStream(socket.getOutputStream());
        output.writeShort(CompanionProtocol.CLIENT_HELLO);
        output.writeInt(bytes.length);
        output.write(bytes);
        output.flush();
        var input = new DataInputStream(socket.getInputStream());
        assertEquals(CompanionProtocol.SERVER_HELLO, input.readShort());
        var response = new ServerHelloMessage();
        response.read(new ByteBufferInputStream(ByteBuffer.wrap(input.readNBytes(input.readInt()))));
        if (response.accepted()) {
            assertEquals(CompanionProtocol.READY, input.readShort());
            input.readNBytes(input.readInt());
        }
        return response.accepted();
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(10);
        assertTrue(condition.getAsBoolean());
    }
    private static void flushUi() throws Exception { SwingUtilities.invokeAndWait(() -> { }); }

    private static final class TestUi implements CompanionUi {
        volatile ServiceStatus game;
        volatile boolean allowSwitch = true;
        Runnable beforeSwitch = () -> { };
        Consumer<ServiceStatus> gameChanged = ignored -> { };
        public boolean prepareProjectSwitch() { beforeSwitch.run(); return allowSwitch; }
        public boolean closeProjectViews() { return true; }
        public boolean canExit() { return true; }
        public void setSwitching(boolean value) { }
        public void refreshProfile() { }
        public void refreshProjects() { }
        public void runtimeChanged() { }
        public void setGameStatus(ServiceStatus status) { game = status; gameChanged.accept(status); }
        public void setMcpStatus(ServiceStatus status) { }
        public void setRuntimeIndexStatus(RuntimeIndexService.Status status) { }
        public void navigate(NavigationTarget target, NavigationService.Activation activation) { }
        public void focus() { fail("Automatic connection must not focus Companion"); }
        public void showError(String title, String message) { fail(message); }
        public void dispose() { }
    }
}
