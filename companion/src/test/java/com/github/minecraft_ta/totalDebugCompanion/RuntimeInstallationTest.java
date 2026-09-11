package com.github.minecraft_ta.totalDebugCompanion;

import com.github.minecraft_ta.totalDebugCompanion.model.ResourceView;
import com.github.minecraft_ta.totalDebugCompanion.resource.ArchiveEntrySource;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.MainWindow;
import javax.swing.SwingUtilities;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeIndexService.ReadySnapshot;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionLaunchConfiguration;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import com.github.minecraft_ta.totaldebug.storage.RuntimeInventory;
import com.github.tth05.jindex.ClassIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeInstallationTest {
    @TempDir Path directory;

    @Test
    void candidateFailureAndPostPublicationFailureRespectTheOwnershipBoundary() throws Exception {
        Path log = directory.resolve("install.log");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("totaldebug.testClasspath", System.getProperty("java.class.path")),
                getClass().getName(), directory.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(25, TimeUnit.SECONDS), () -> read(log));
            assertEquals(0, process.exitValue(), () -> read(log));
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    public static void main(String[] args) {
        try {
            Path root = Path.of(args[0]);
            var config = CompanionApp.class.getDeclaredField("launchConfiguration");
            config.setAccessible(true);
            config.set(null, new CompanionLaunchConfiguration(Files.createDirectories(root.resolve("app"))));
            CompanionApp.configureWithoutSession(new CompanionProfile("test", Files.createDirectories(root.resolve("data")),
                    Files.createDirectories(root.resolve("game"))));
            GlobalConfig.getInstance().loadFrom(root.resolve("app"));
            CompanionApp.configureLookAndFeel();
            SwingUtilities.invokeAndWait(() -> MainWindow.INSTANCE.getEditorTabs().openEditorTab(
                    new ResourceView(new ArchiveEntrySource(root.resolve("old.jar"), "old.txt", -1), null)));
            var uiStarted = CompanionApp.class.getDeclaredField("uiStarted");
            uiStarted.setAccessible(true);
            uiStarted.set(null, true);
            // Force debugger restoration to fail after the index is published.
            CompanionApp.getDebuggerController().close();
            var install = CompanionApp.class.getDeclaredMethod("installRuntimeSnapshot", ReadySnapshot.class,
                    RuntimeSnapshotBytecodeSource.class);
            install.setAccessible(true);
            var queue = CompanionApp.class.getDeclaredField("projectWorker");
            queue.setAccessible(true);
            var close = CompanionApp.class.getDeclaredMethod("closeRuntime");
            close.setAccessible(true);
            try (var accepted = snapshot(root, "accepted"); var rejected = snapshot(root, "")) {
                var bytes = RuntimeSnapshotBytecodeSource.fromIndexedSources(accepted.sources(), accepted.index());
                var releaseRefresh = new CountDownLatch(1);
                var refreshBlocked = new CountDownLatch(1);
                ((ExecutorService) queue.get(null)).submit(() -> {
                    refreshBlocked.countDown();
                    try { assertTrue(releaseRefresh.await(10, TimeUnit.SECONDS)); }
                    catch (InterruptedException failure) { throw new AssertionError(failure); }
                });
                assertTrue(refreshBlocked.await(3, TimeUnit.SECONDS));
                var newView = new AtomicReference<ResourceView>();
                try {
                    install.invoke(null, accepted, bytes);
                    SwingUtilities.invokeAndWait(() -> {
                        var view = new ResourceView(new ArchiveEntrySource(root.resolve("old.jar"), "old.txt", -1), CompanionApp.currentRuntime());
                        newView.set(view);
                        MainWindow.INSTANCE.getEditorTabs().openEditorTab(view);
                    });
                } finally { releaseRefresh.countDown(); }
                ((ExecutorService) queue.get(null)).submit(() -> {}).get(10, TimeUnit.SECONDS);
                SwingUtilities.invokeAndWait(() -> {});
                SwingUtilities.invokeAndWait(() -> {
                    assertEquals(1, MainWindow.INSTANCE.getEditorTabs().getTabCount(), "Late invalidation must close only the old runtime tab");
                    assertSame(newView.get(), MainWindow.INSTANCE.getEditorTabs().getSelectedEditor());
                });
                assertFalse(accepted.index().isDestroyed(), "Post-publication failure must not close the installed index");
                var decompiler = CompanionApp.getDecompilationService();
                var candidateBytes = RuntimeSnapshotBytecodeSource.fromIndexedSources(rejected.sources(), rejected.index());
                var failed = assertThrows(InvocationTargetException.class, () -> install.invoke(null, rejected, candidateBytes));
                assertInstanceOf(IllegalArgumentException.class, failed.getCause());
                assertSame(decompiler, CompanionApp.getDecompilationService());
                assertTrue(bytes.hasClass("java.lang.Object"), "Failed preparation must not close the previous bytecode source");
                assertFalse(rejected.index().isDestroyed(), "Loader still owns the rejected candidate");
                close.invoke(null);
                assertTrue(accepted.index().isDestroyed());
            }
            SwingUtilities.invokeAndWait(MainWindow.INSTANCE::dispose);
            System.exit(0);
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }

    private static ReadySnapshot snapshot(Path root, String signature) throws Exception {
        Path classes = Files.createDirectories(root.resolve("classes"));
        var module = new RuntimeInventory.RuntimeModule("test", "Test", RuntimeInventory.ModuleKind.LIBRARY);
        var source = new RuntimeSnapshotBytecodeSource.Source(0, classes, classes.toUri().toString(), module);
        try (var input = Object.class.getResourceAsStream("Object.class")) {
            return new ReadySnapshot("test", signature, root.resolve("runtime/index.jindex"), List.of(source),
                    ClassIndex.fromBytes(List.of(input.readAllBytes())));
        }
    }

    private static String read(Path path) {
        try { return Files.readString(path); }
        catch (Exception failure) { return failure.toString(); }
    }
}
