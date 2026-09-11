package com.github.minecraft_ta.totalDebugCompanion;

import com.github.minecraft_ta.totalDebugCompanion.session.CompanionLaunchConfiguration;
import com.github.minecraft_ta.totalDebugCompanion.project.ProjectScope;
import com.github.minecraft_ta.totalDebugCompanion.storage.InstanceState;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import com.github.minecraft_ta.totalDebugCompanion.session.ProjectRegistry;
import com.github.minecraft_ta.totaldebug.storage.AppPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.Map;
import com.github.minecraft_ta.totalDebugCompanion.mcp.ProjectSwitchJobs;
import com.github.minecraft_ta.totalDebugCompanion.mcp.CodeModeJobService;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationService;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;

class ProjectSwitchLifecycleTest {
    @TempDir Path directory;

    @Test void switchesTheActualApplicationStateWithoutAWindowOrGame() throws Exception {
        // Companion owns process-wide singletons. Exercise its real switch in a fresh JVM.
        String classpath = System.getProperty("totaldebug.testClasspath", System.getProperty("java.class.path"));
        Path log = directory.resolve("probe.log");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.awt.headless=false", "-cp", classpath,
                getClass().getName(), directory.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), () -> "Switch did not finish: " + read(log));
            assertEquals(0, process.exitValue(), () -> read(log));
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    public static void main(String[] args) {
        try {
            Path root = Path.of(args[0]);
            AppPaths paths = new AppPaths(root.resolve("app"));
            var registry = ProjectRegistry.open(paths);
            set("launchConfiguration", new CompanionLaunchConfiguration(paths.home()));
            set("projects", registry);
            var createDebugger = CompanionApp.class.getDeclaredMethod("createDebuggerController");
            createDebugger.setAccessible(true);
            set("debuggerController", createDebugger.invoke(null));
            var session = new com.github.minecraft_ta.totalDebugCompanion.session.CompanionSession("test-token");
            session.bindAndPublish(new CompanionLaunchConfiguration(paths.home()));
            set("session", session);
            CompanionApp.SERVER = session.server();
            var jobs = ProjectSwitchJobs.create();
            var constructor = com.github.minecraft_ta.totalDebugCompanion.mcp.CompanionMcpServer.class.getDeclaredConstructor(
                    Path.class, com.github.minecraft_ta.totalDebugCompanion.mcp.CodeModeJobService.class, int.class);
            constructor.setAccessible(true);
            var mcp = (com.github.minecraft_ta.totalDebugCompanion.mcp.CompanionMcpServer) constructor.newInstance(paths.home(), jobs, 0);
            mcp.start();
            set("mcpServer", mcp);
            String endpoint = mcp.endpointUrl();
            var transport = io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
                    .builder(endpoint.substring(0, endpoint.length() - 4)).endpoint("/mcp").build();
            var client = io.modelcontextprotocol.client.McpClient.sync(transport).build();
            client.initialize();
            var a = profile(root, "A");
            var b = profile(root, "B");
            Files.writeString(a.dataDirectory().resolve("scripts/shared.tdscript"), "A");
            Files.writeString(b.dataDirectory().resolve("scripts/shared.tdscript"), "B");
            CompanionApp.openProject(a).get(10, TimeUnit.SECONDS);
            CompanionApp.instanceState().setDebuggerWatches(List.of("watch A"));
            var admitted = CompanionApp.requireProject().admit(() -> jobs.submit("return 42;", List.of(),
                    CodeModeJobService.ExecutionSide.CLIENT, CodeModeJobService.ExecutionEnvironment.THREAD));
            assertEquals(CodeModeJobService.JobState.COMPILING, admitted.state());
            CompanionApp.openProject(b).get(10, TimeUnit.SECONDS);
            assertEquals(CodeModeJobService.JobState.DISCONNECTED, jobs.get(admitted.jobId()).orElseThrow().state());
            assertEquals(b, CompanionApp.currentProject());
            assertTrue(CompanionApp.instanceState().debuggerWatches().isEmpty());
            CompanionApp.instanceState().setDebuggerWatches(List.of("watch B"));
            CompanionApp.openProject(a).get(10, TimeUnit.SECONDS);
            assertEquals(List.of("watch A"), CompanionApp.instanceState().debuggerWatches());
            assertEquals("A", Files.readString(CompanionApp.instancePaths().scripts().resolve("shared.tdscript")));
            assertEquals("B", Files.readString(b.dataDirectory().resolve("scripts/shared.tdscript")));
            var missing = new CompanionProfile("missing", root.resolve("absent/total-debug"), root.resolve("absent"));
            assertThrows(ExecutionException.class,
                    () -> CompanionApp.openProject(missing).get(10, TimeUnit.SECONDS));
            assertEquals(a, CompanionApp.currentProject());
            assertFalse(Files.exists(missing.dataDirectory()));
            Files.writeString(b.dataDirectory().resolve("state.json"), "invalid state");
            assertThrows(ExecutionException.class,
                    () -> CompanionApp.openProject(b).get(10, TimeUnit.SECONDS));
            assertEquals(a, CompanionApp.currentProject());
            assertEquals(a, ProjectRegistry.open(paths).selected());
            Files.delete(b.dataDirectory().resolve("state.json"));
            Files.delete(b.dataDirectory().resolve("scripts/shared.tdscript"));
            Files.delete(b.dataDirectory().resolve("scripts"));
            Files.writeString(b.dataDirectory().resolve("scripts"), "not a directory");
            assertThrows(ExecutionException.class,
                    () -> CompanionApp.openProject(b).get(10, TimeUnit.SECONDS));
            assertEquals(a, CompanionApp.currentProject());
            assertEquals(a, ProjectRegistry.open(paths).selected());
            assertEquals(2, CompanionApp.projects().size());
            assertFalse(CompanionApp.isSwitching());
            assertEquals(endpoint, mcp.endpointUrl());
            var status = client.callTool(new io.modelcontextprotocol.spec.McpSchema.CallToolRequest("status", Map.of()));
            assertFalse(Boolean.TRUE.equals(status.isError()), "MCP must stay initialized through switches");
            // A failed state flush is reversible, just like the editor save veto.
            Files.delete(b.dataDirectory().resolve("scripts"));
            Files.createDirectory(b.dataDirectory().resolve("scripts"));
            var scopeBeforeFlush = CompanionApp.requireProject();
            CompanionApp.instanceState().saveNow();
            Path stateFile = a.dataDirectory().resolve("state.json");
            byte[] savedState = Files.readAllBytes(stateFile);
            Files.delete(stateFile);
            Files.createDirectory(stateFile);
            Files.writeString(stateFile.resolve("occupied"), "x");
            CompanionApp.instanceState().setDebuggerWatches(List.of("pending A"));
            assertThrows(ExecutionException.class,
                    () -> CompanionApp.openProject(b).get(10, TimeUnit.SECONDS));
            assertSame(scopeBeforeFlush, CompanionApp.requireProject());
            assertTrue(scopeBeforeFlush.admit(() -> true));
            assertFalse(CompanionApp.isSwitching());
            Files.delete(stateFile.resolve("occupied"));
            Files.delete(stateFile);
            Files.write(stateFile, savedState);
            CompanionApp.instanceState().saveNow();
            Files.delete(b.dataDirectory().resolve("scripts"));
            Files.writeString(b.dataDirectory().resolve("scripts"), "restore fixture");
            verifyEditorSwitch(a, b, paths);
            client.close();
            mcp.close();
            session.close();
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static CompanionProfile profile(Path root, String id) throws Exception {
        Path game = Files.createDirectories(root.resolve(id));
        Path data = game.resolve("total-debug");
        Files.createDirectories(data.resolve("scripts"));
        return new CompanionProfile(id, data, game);
    }

    private static void verifyEditorSwitch(CompanionProfile a, CompanionProfile b, AppPaths paths) throws Exception {
        Files.delete(b.dataDirectory().resolve("scripts"));
        Files.createDirectory(b.dataDirectory().resolve("scripts"));
        var allowed = new java.util.concurrent.atomic.AtomicBoolean();
        var disposed = new java.util.concurrent.atomic.AtomicBoolean();
        var panel = new javax.swing.JPanel();
        var editor = new com.github.minecraft_ta.totalDebugCompanion.model.IEditorPanel() {
            public String getTitle() { return "Unsaved A"; }
            public String getTooltip() { return "A"; }
            public javax.swing.Icon getIcon() { return null; }
            public java.awt.Component getComponent() { return panel; }
            public boolean canClose() {
                if (!allowed.get()) return false;
                assertEquals(a, CompanionApp.currentProject(), "Save must run before selecting B");
                try { Files.writeString(a.dataDirectory().resolve("scripts/shared.tdscript"), "saved A"); }
                catch (java.io.IOException failure) { return false; }
                return true;
            }
            public void dispose() { disposed.set(true); }
        };
        GlobalConfig.getInstance().loadFrom(((CompanionLaunchConfiguration) get("launchConfiguration")).appHome());
        CompanionApp.configureLookAndFeel();
        javax.swing.SwingUtilities.invokeAndWait(() ->
                com.github.minecraft_ta.totalDebugCompanion.ui.views.MainWindow.INSTANCE.getEditorTabs().openEditorTab(editor));
        set("uiStarted", true);
        var scopeBeforeVeto = CompanionApp.requireProject();
        assertThrows(ExecutionException.class,
                () -> CompanionApp.openProject(b).get(10, TimeUnit.SECONDS));
        assertEquals(a, CompanionApp.currentProject());
        assertSame(scopeBeforeVeto, CompanionApp.requireProject());
        assertEquals("still A", scopeBeforeVeto.admit(() -> "still A"));
        assertFalse(disposed.get());
        allowed.set(true);
        byte[] savedRegistry = Files.readAllBytes(paths.projects());
        Files.delete(paths.projects());
        Files.createDirectory(paths.projects());
        Files.writeString(paths.projects().resolve("occupied"), "x");
        var failure = assertThrows(ExecutionException.class,
                () -> CompanionApp.openProject(b).get(10, TimeUnit.SECONDS));
        assertTrue(failure.getCause().getMessage().contains("Project opened, but its selection could not be saved"));
        assertEquals(b, CompanionApp.currentProject());
        assertTrue(CompanionApp.instanceState().debuggerWatches().isEmpty());
        assertTrue(disposed.get());
        assertEquals("saved A", Files.readString(a.dataDirectory().resolve("scripts/shared.tdscript")));
        var window = com.github.minecraft_ta.totalDebugCompanion.ui.views.MainWindow.INSTANCE;
        var treeField = window.getClass().getDeclaredField("fileTreeView");
        treeField.setAccessible(true);
        var treeView = (javax.swing.JScrollPane) treeField.get(window);
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            var tree = (javax.swing.JTree) treeView.getViewport().getView();
            var root = (javax.swing.tree.DefaultMutableTreeNode) tree.getModel().getRoot();
            var scripts = (com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.LazyTreeNode) root.getChildAt(0);
            assertEquals(b.dataDirectory().resolve("scripts").toString(), scripts.getUserObject().getTooltip());
            assertEquals(0, window.getEditorTabs().getTabCount());
        });
        Files.delete(paths.projects().resolve("occupied"));
        Files.delete(paths.projects());
        Files.write(paths.projects(), savedRegistry);
        assertEquals(a, ProjectRegistry.open(paths).selected());
        CompanionApp.openProject(b).get(10, TimeUnit.SECONDS);
        assertEquals(b, ProjectRegistry.open(paths).selected());
        verifyNavigationReset(window);
        javax.swing.SwingUtilities.invokeAndWait(window::dispose);
    }

    private static void verifyNavigationReset(com.github.minecraft_ta.totalDebugCompanion.ui.views.MainWindow window) throws Exception {
        var pending = new java.util.concurrent.atomic.AtomicReference<CompletableFuture<Boolean>>();
        var created = new CompletableFuture<NavigationService>();
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            var tree = new com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.FileTreeView(ignored -> { }) {
                @Override public CompletableFuture<Boolean> revealLocalDirectory(Path path) {
                    var delayed = pending.getAndSet(null);
                    return delayed == null ? CompletableFuture.completedFuture(true) : delayed;
                }
            };
            created.complete(new NavigationService(window,
                    new com.github.minecraft_ta.totalDebugCompanion.ui.components.global.EditorTabs(), tree));
        });
        var navigation = created.join();
        var scopeA = new ProjectScope(new Object(), CompanionApp.currentProject(), InstanceState.inMemory());
        var scopeB = new ProjectScope(new Object(), CompanionApp.currentProject(), InstanceState.inMemory());
        navigation.projectChanged(scopeA);
        for (String directory : List.of("A/one", "A/two"))
            navigation.navigate(new NavigationTarget.LocalDirectory(Path.of(directory))).get(3, TimeUnit.SECONDS);
        var delayedA = new CompletableFuture<Boolean>();
        pending.set(delayedA);
        var oldTraversal = navigation.goBack();
        scopeA.retire();
        javax.swing.SwingUtilities.invokeAndWait(() -> navigation.projectChanged(scopeB));
        assertFalse(oldTraversal.isDone(), "Old lookup is still awaiting a callback");
        for (String directory : List.of("B/one", "B/two"))
            navigation.navigate(new NavigationTarget.LocalDirectory(Path.of(directory))).get(3, TimeUnit.SECONDS);
        javax.swing.SwingUtilities.invokeAndWait(() -> assertTrue(navigation.backAction().isEnabled()));
        var delayedB = new CompletableFuture<Boolean>();
        pending.set(delayedB);
        var newTraversal = navigation.goBack();
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
        assertFalse(newTraversal.isDone());
        delayedA.complete(true);
        assertInstanceOf(CancellationException.class, assertThrows(ExecutionException.class,
                () -> oldTraversal.get(3, TimeUnit.SECONDS)).getCause());
        javax.swing.SwingUtilities.invokeAndWait(() -> assertTrue(navigation.goBack().isDone(),
                "Completion from A must not admit another traversal while B is still navigating"));
        delayedB.complete(true);
        newTraversal.get(3, TimeUnit.SECONDS);
        javax.swing.SwingUtilities.invokeAndWait(() -> assertTrue(navigation.forwardAction().isEnabled()));
        var duringVeto = new CompletableFuture<Boolean>();
        pending.set(duringVeto);
        var vetoTraversal = navigation.goForward();
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
        scopeB.beginSwitch();
        duringVeto.complete(true);
        assertThrows(ExecutionException.class, () -> vetoTraversal.get(3, TimeUnit.SECONDS));
        scopeB.cancelSwitch();
        navigation.goForward().get(3, TimeUnit.SECONDS);
        javax.swing.SwingUtilities.invokeAndWait(() -> assertTrue(navigation.backAction().isEnabled()));
    }

    private static Object get(String name) throws Exception {
        var field = CompanionApp.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static void set(String name, Object value) throws Exception {
        var field = CompanionApp.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static String read(Path file) {
        try { return Files.readString(file); } catch (Exception failure) { return failure.toString(); }
    }
}
