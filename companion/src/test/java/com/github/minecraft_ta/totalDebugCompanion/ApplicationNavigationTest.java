package com.github.minecraft_ta.totalDebugCompanion;

import com.github.minecraft_ta.totalDebugCompanion.testui.UiTest;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.model.ServiceStatus;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationService;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeIndexService;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionLaunchConfiguration;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import com.github.minecraft_ta.totalDebugCompanion.ui.CompanionUi;
import org.junit.jupiter.api.Test;
import com.github.minecraft_ta.totalDebugCompanion.testui.RequiresDesktop;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.github.minecraft_ta.totalDebugCompanion.model.ResourceView;
import com.github.minecraft_ta.totalDebugCompanion.resource.LocalFileSource;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.MainWindow;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.net.Socket;
import java.awt.Container;
import java.awt.Component;
import javax.swing.AbstractButton;
import com.github.tth05.scnet.IConnectionListener;
import com.github.tth05.scnet.Client;
import com.github.minecraft_ta.totalDebugCompanion.script.ExecutionRuns;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResult;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ClientHelloMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ProtocolBindings;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ReadyMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ServerHelloMessage;
import java.net.InetSocketAddress;
import com.github.minecraft_ta.totalDebugCompanion.mcp.ProjectSwitchJobs;
import com.github.minecraft_ta.totaldebug.protocol.CompanionProtocol;
import com.github.minecraft_ta.totaldebug.storage.CompanionSessionDescriptor;
import javax.swing.SwingUtilities;
import javax.swing.JLabel;
import javax.swing.JButton;
import javax.swing.JMenu;
import javax.swing.JTree;
import javax.swing.text.JTextComponent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import com.github.minecraft_ta.totalDebugCompanion.model.ScriptView;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import static org.junit.jupiter.api.Assertions.*;

@UiTest
class ApplicationNavigationTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @RequiresDesktop
    void headerUsesSettingsGearAndInitialFocusBelongsToTheWorkArea(boolean resource) throws Exception {
        CompanionApp.configureTokenMakers();
        try (var app = new CompanionApplication(new CompanionLaunchConfiguration(directory), "test-token")) {
            app.openProject(CompanionProfile.forGame(Files.createDirectories(directory.resolve("game")))).get(3, TimeUnit.SECONDS);
            Path script = app.requireProject().scriptFiles().create(app.requireProject().paths().scripts(), "Test", false, "return 1;");
            Path textFile = Files.writeString(directory.resolve("example.txt"), "Example text");
            var windowRef = new AtomicReference<MainWindow>();
            var focused = new CompletableFuture<Component>();
            var expected = new AtomicReference<Component>();
            SwingUtilities.invokeAndWait(() -> {
                var window = app.createWindow();
                assertInstanceOf(JTree.class, window.getFocusTraversalPolicy().getDefaultComponent(window));
                JButton settings = null;
                JMenu scripts = null;
                for (Component component : window.getJMenuBar().getComponents()) {
                    if (component instanceof JMenu menu) {
                        assertNotEquals("File", menu.getText());
                        if ("Script".equals(menu.getText())) scripts = menu;
                    }
                    if (component instanceof JButton button && "Settings".equals(button.getToolTipText())) settings = button;
                }
                assertNotNull(settings);
                assertEquals("", settings.getText());
                assertNotNull(settings.getIcon());
                assertNotNull(scripts);
                boolean breakpoints = false;
                for (int i = 0; i < scripts.getItemCount(); i++)
                    if (scripts.getItem(i) != null && "View Breakpoints".equals(scripts.getItem(i).getText())) breakpoints = scripts.getItem(i).isEnabled();
                assertTrue(breakpoints);
                window.getEditorTabs().openEditorTab(resource
                        ? new ResourceView(window.editorContext(), new LocalFileSource(textFile), null)
                        : new ScriptView(window.editorContext(), script));
                windowRef.set(window);
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (expected.get() == null && System.nanoTime() < deadline) {
                SwingUtilities.invokeAndWait(() -> expected.set(findText(windowRef.get().getEditorTabs().getSelectedComponent())));
                if (expected.get() == null) Thread.sleep(5);
            }
            assertNotNull(expected.get());
            SwingUtilities.invokeAndWait(() -> {
                var window = windowRef.get();
                expected.get().addFocusListener(new FocusAdapter() {
                    @Override public void focusGained(FocusEvent event) { focused.complete(event.getComponent()); }
                });
                window.setBounds(50, 60, 984, 620);
                window.setAutoRequestFocus(true);
                window.setVisible(true);
                window.requestFocus();
                window.getFocusTraversalPolicy().getDefaultComponent(window).requestFocusInWindow(FocusEvent.Cause.ACTIVATION);
            });
            assertSame(expected.get(), focused.get(5, TimeUnit.SECONDS));
        }
    }
    @Test void frameNavigationWaitsForTheRuntimeInItsProject() throws Exception {
        var ui = new RecordingUi();
        var home = Files.createDirectories(directory.resolve("app"));
        GlobalConfig.getInstance().loadFrom(home);
        try (var app = new CompanionApplication(new CompanionLaunchConfiguration(home), "test-token", ui)) {
            app.openProject(CompanionProfile.forGame(Files.createDirectories(directory.resolve("game")))).get(3, TimeUnit.SECONDS);
            var frame = new DebugEngine.StackFrame(1, "run", "java.lang.Object", null, 7, 0);
            app.openDebugFrame(frame, true);
            assertFalse(ui.navigation.isDone(), "Frame navigation must wait for an installed runtime");
            var snapshot = RuntimeInstallationTest.snapshot(directory, "navigation");
            advanceRuntimeFollowUpBeforePublication(app);
            try { app.installRuntimeSnapshot(snapshot, RuntimeSnapshotBytecodeSource.fromIndexedSources(snapshot.sources(), snapshot.index())); }
            catch (RuntimeException failure) { snapshot.close(); throw failure; }
            assertEquals(new NavigationTarget.RuntimeLine("java.lang.Object", 7), ui.navigation.get(10, TimeUnit.SECONDS));
        }
    }

    /** Force the project worker and EDT to run while installation still holds the lifecycle lock. */
    private static void advanceRuntimeFollowUpBeforePublication(CompanionApplication app) throws Exception {
        var field = CompanionApplication.class.getDeclaredField("projectWorker");
        field.setAccessible(true);
        var delegate = (ExecutorService) field.get(app);
        var nextExecution = new AtomicBoolean(true);
        var controlled = Proxy.newProxyInstance(ExecutorService.class.getClassLoader(), new Class<?>[]{ExecutorService.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("execute") && nextExecution.getAndSet(false)) {
                        var started = new CountDownLatch(1);
                        var worker = new AtomicReference<Thread>();
                        delegate.execute(() -> {
                            worker.set(Thread.currentThread());
                            started.countDown();
                            ((Runnable) arguments[0]).run();
                        });
                        assertTrue(started.await(3, TimeUnit.SECONDS));
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                        while (worker.get().getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(1);
                        assertEquals(Thread.State.BLOCKED, worker.get().getState(), "Follow-up must reach the held lifecycle lock");
                        SwingUtilities.invokeAndWait(() -> { });
                        return null;
                    }
                    try { return method.invoke(delegate, arguments); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
        field.set(app, controlled);
    }

    @Test void replacementRejectsRequestsUntilUiRestorationFinishes() throws Exception {
        var ui = new RecordingUi();
        try (var app = new CompanionApplication(new CompanionLaunchConfiguration(directory), "test-token", ui)) {
            var restored = new CompletableFuture<Void>();
            ui.onRefresh = () -> {
                assertTrue(app.isSwitching());
                assertFalse(app.currentScope().isActive());
                assertThrows(IllegalStateException.class, app::requireProject);
                assertThrows(IllegalStateException.class, () -> app.currentScope().admit(() -> true));
                restored.complete(null);
            };
            app.openProject(CompanionProfile.forGame(Files.createDirectories(directory.resolve("game")))).get(3, TimeUnit.SECONDS);
            assertTrue(restored.isDone());
            assertTrue(app.requireProject().admit(() -> true));
            assertFalse(app.isSwitching());
        }
    }

    @Test void rejectedWindowCreationDoesNotPublishAWindow() throws Exception {
        try (var app = new CompanionApplication(new CompanionLaunchConfiguration(directory), "test-token")) {
            app.openProject(CompanionProfile.forGame(Files.createDirectories(directory.resolve("game")))).get(3, TimeUnit.SECONDS);
            var scope = app.requireProject();
            scope.beginSwitch();
            SwingUtilities.invokeAndWait(() -> assertThrows(IllegalStateException.class, app::createWindow));
            scope.cancelSwitch();
            SwingUtilities.invokeAndWait(() -> assertNotNull(app.createWindow()));
        }
    }

    @Test void debuggerMenuCannotOpenWithoutAProject() throws Exception {
        try (var app = new CompanionApplication(new CompanionLaunchConfiguration(directory), "test-token")) {
            SwingUtilities.invokeAndWait(() -> {
                var window = app.createWindow();
                try {
                    var state = window.getClass().getDeclaredField("debuggerState");
                    state.setAccessible(true);
                    var button = (AbstractButton) state.get(window);
                    assertFalse(button.isVisible());
                    button.doClick(0);
                    var popup = window.getClass().getDeclaredField("debuggerPopup");
                    popup.setAccessible(true);
                    assertNull(popup.get(window));
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            });
        }
    }

    @Test void gamePopupReflectsRenamedConnectedProject() throws Exception {
        try (var app = new CompanionApplication(new CompanionLaunchConfiguration(directory), "test-token")) {
            app.openProject(CompanionProfile.forGame(Files.createDirectories(directory.resolve("game")))).get(3, TimeUnit.SECONDS);
            var name = new AtomicReference<JLabel>();
            SwingUtilities.invokeAndWait(() -> {
                var window = app.createWindow();
                window.setGameStatus(new ServiceStatus(ServiceStatus.State.AVAILABLE, "Connected", "Connected"));
                try {
                    var barField = window.getClass().getDeclaredField("statusBar");
                    barField.setAccessible(true);
                    var bar = barField.get(window);
                    var nameField = bar.getClass().getDeclaredField("gameName");
                    nameField.setAccessible(true);
                    name.set((JLabel) nameField.get(bar));
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            });
            app.renameProject(app.currentProject().id(), "Renamed pack").get(3, TimeUnit.SECONDS);
            SwingUtilities.invokeAndWait(() -> assertEquals("Renamed pack", name.get().getText()));
        }
    }

    @Test void reconnectPendingKeepsRunsUntilTheTransportActuallyDisconnects() throws Exception {
        var configuration = new CompanionLaunchConfiguration(directory);
        var game = new Client();
        try (var app = new CompanionApplication(configuration, "test-token")) {
            var profile = CompanionProfile.forGame(Files.createDirectories(directory.resolve("game")));
            app.openProject(profile).get(3, TimeUnit.SECONDS);
            app.session().bindAndPublish(configuration);
            ProtocolBindings.registerMod(game.getMessageProcessor());
            var ready = new CompletableFuture<Void>();
            game.getMessageBus().listenAlways(ServerHelloMessage.class, hello -> {
                if (!hello.accepted()) ready.completeExceptionally(new AssertionError(hello.rejectionReason()));
            });
            game.getMessageBus().listenAlways(ReadyMessage.class, message -> ready.complete(null));
            int port = CompanionSessionDescriptor.read(configuration.descriptorFile(), CompanionProtocol.VERSION).port();
            assertTrue(game.connect(new InetSocketAddress("127.0.0.1", port)));
            game.getMessageProcessor().enqueueMessage(new ClientHelloMessage(CompanionProtocol.VERSION, "test-token", profile.id(),
                    profile.dataDirectory().toString(), profile.workspaceDirectory().toString()));
            ready.get(3, TimeUnit.SECONDS);
            assertTrue(app.isConnected());
            var completion = new CompletableFuture<ExecutionResult>();
            SwingUtilities.invokeAndWait(() -> {
                var window = app.createWindow();
                try {
                    var executionsField = MainWindow.class.getDeclaredField("executions");
                    executionsField.setAccessible(true);
                    // Observe a run on this connection without executing game code; the real transport owns its lifetime.
                    ((ExecutionRuns) executionsField.get(window)).open(new ExecutionRuns.Observer() {
                        @Override public void result(int id, ExecutionResult result) { completion.complete(result); }
                        @Override public void disconnected(int id, boolean expected) {
                            completion.completeExceptionally(new IllegalStateException("Minecraft disconnected"));
                        }
                    });
                    window.setGameStatus(new ServiceStatus(ServiceStatus.State.PENDING, "Reconnecting", "Waiting for Minecraft."));
                    assertFalse(completion.isDone(), "A pending request must preserve the still-connected run");
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            });
            var reconnect = app.reconnectGame(app.requireProject());
            assertThrows(ExecutionException.class, () -> completion.get(5, TimeUnit.SECONDS));
            assertFalse(app.isConnected());
            assertFalse(reconnect.isDone(), "Reconnect still waits for the replacement game");
        } finally {
            game.close();
        }
    }

    @Test void lateWindowReplaysGameAndMcpStatuses() throws Exception {
        var configuration = new CompanionLaunchConfiguration(directory);
        try (var app = new CompanionApplication(configuration, "test-token")) {
            app.openProject(CompanionProfile.forGame(Files.createDirectories(directory.resolve("game")))).get(3, TimeUnit.SECONDS);
            app.startMcpServer(ProjectSwitchJobs.create(), 0);
            var connecting = new CompletableFuture<Void>();
            app.session().server().addConnectionListener(new IConnectionListener() {
                @Override public void onConnected() { connecting.complete(null); }
                @Override public void onDisconnected() { }
                @Override public void onConnectionError(Throwable failure) { connecting.completeExceptionally(failure); }
            });
            app.session().bindAndPublish(configuration);
            int port = CompanionSessionDescriptor.read(configuration.descriptorFile(), CompanionProtocol.VERSION).port();
            try (var socket = new Socket("127.0.0.1", port)) {
                connecting.get(3, TimeUnit.SECONDS);
                SwingUtilities.invokeAndWait(() -> {
                    var window = app.createWindow();
                    assertTrue(hasButton(window, "MCP: Listening"));
                    assertTrue(hasButton(window, "Game: Connecting"));
                });
            }
        }
    }

    private static JTextComponent findText(Component component) {
        if (component instanceof JTextComponent text) return text;
        if (component instanceof Container container) for (Component child : container.getComponents()) {
            JTextComponent text = findText(child);
            if (text != null) return text;
        }
        return null;
    }

    private static boolean hasButton(Container parent, String text) {
        for (Component child : parent.getComponents()) {
            if (child instanceof AbstractButton button && text.equals(button.getText())) return true;
            if (child instanceof Container container && hasButton(container, text)) return true;
        }
        return false;
    }

    private static final class RecordingUi implements CompanionUi {
        Runnable onRefresh = () -> { };
        final CompletableFuture<NavigationTarget> navigation = new CompletableFuture<>();
        public boolean prepareProjectSwitch() { return true; }
        public boolean closeProjectViews() { return true; }
        public boolean canExit() { return true; }
        public void setSwitching(boolean switching) { }
        public void refreshProfile() { onRefresh.run(); }
        public void refreshProjects() { }
        public void runtimeChanged() { }
        @Override
        public void catalogChanged() { }
        public void setGameStatus(ServiceStatus status) { }
        public void setMcpStatus(ServiceStatus status) { }
        public void setRuntimeIndexStatus(RuntimeIndexService.Status status) { }
        public void navigate(NavigationTarget target, NavigationService.Activation activation) { navigation.complete(target); }
        public void focus() { }
        public void showError(String title, String message) { fail(title + ": " + message); }
        public void dispose() { }
    }
}
