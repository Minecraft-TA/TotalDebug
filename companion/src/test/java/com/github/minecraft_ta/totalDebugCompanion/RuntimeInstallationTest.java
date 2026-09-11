package com.github.minecraft_ta.totalDebugCompanion;

import java.util.concurrent.CompletableFuture;
import com.github.minecraft_ta.totalDebugCompanion.model.ResourceView;
import com.github.minecraft_ta.totalDebugCompanion.resource.ArchiveEntrySource;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.MainWindow;
import javax.swing.SwingUtilities;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeIndexService.ReadySnapshot;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionLaunchConfiguration;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import com.github.minecraft_ta.totaldebug.storage.RuntimeInventory;
import com.github.tth05.jindex.ClassIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeInstallationTest {
    @TempDir Path directory;

    @Test void installedRuntimeSurvivesDebuggerFailureAndRefreshesUi() throws Exception {
        Path root = directory;
        Path home = Files.createDirectories(root.resolve("app"));
        GlobalConfig.getInstance().loadFrom(home);
        CompanionApp.configureLookAndFeel();
        try (var app = new CompanionApplication(new CompanionLaunchConfiguration(home), "test-token")) {
            var profile = new CompanionProfile("test", Files.createDirectories(root.resolve("data")),
                    Files.createDirectories(root.resolve("game")));
            app.openProject(profile).get(10, TimeUnit.SECONDS);
            var created = new CompletableFuture<MainWindow>();
            SwingUtilities.invokeAndWait(() -> {
                MainWindow window = app.createWindow();
                window.getEditorTabs().openEditorTab(new ResourceView(window.editorContext(),
                        new ArchiveEntrySource(root.resolve("old.jar"), "old.txt", -1), null));
                created.complete(window);
            });
            MainWindow window = created.join();
            app.getDebuggerController().close();
            try (var accepted = snapshot(root, "accepted"); var rejected = snapshot(root, "")) {
                var bytes = RuntimeSnapshotBytecodeSource.fromIndexedSources(accepted.sources(), accepted.index());
                app.installRuntimeSnapshot(accepted, bytes);
                app.openProject(profile).get(10, TimeUnit.SECONDS);
                SwingUtilities.invokeAndWait(() -> {});
                SwingUtilities.invokeAndWait(() -> assertEquals(0, window.getEditorTabs().getTabCount(),
                        "Old runtime tabs must close even when breakpoint restoration fails"));
                assertFalse(accepted.index().isDestroyed());
                var decompiler = app.getDecompilationService();
                var candidateBytes = RuntimeSnapshotBytecodeSource.fromIndexedSources(rejected.sources(), rejected.index());
                assertThrows(IllegalArgumentException.class, () -> app.installRuntimeSnapshot(rejected, candidateBytes));
                assertSame(decompiler, app.getDecompilationService());
                assertTrue(bytes.hasClass("java.lang.Object"));
                assertFalse(rejected.index().isDestroyed(), "Loader still owns the rejected candidate");
                app.close();
                assertTrue(accepted.index().isDestroyed());
            }
        }
    }

    static ReadySnapshot snapshot(Path root, String signature) throws Exception {
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
