package com.github.minecraft_ta.totalDebugCompanion;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
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

    @Test void lateRuntimeRefreshKeepsViewsOpenedAgainstTheInstalledBinding() throws Exception {
        Path home = Files.createDirectories(directory.resolve("app"));
        try (var app = new CompanionApplication(new CompanionLaunchConfiguration(home), "test-token")) {
            app.openProject(new CompanionProfile("test", Files.createDirectories(directory.resolve("data")),
                    Files.createDirectories(directory.resolve("game")))).get(10, TimeUnit.SECONDS);
            try (var accepted = snapshot(directory, "accepted")) {
                app.installRuntimeSnapshot(accepted,
                        RuntimeSnapshotBytecodeSource.fromIndexedSources(accepted.sources(), accepted.index()));
                app.openProject(app.currentProject()).get(10, TimeUnit.SECONDS);
                var createdWindow = new AtomicReference<MainWindow>();
                var currentView = new AtomicReference<ResourceView>();
                SwingUtilities.invokeAndWait(() -> {
                    MainWindow window = app.createWindow();
                    var source = new ArchiveEntrySource(directory.resolve("same.jar"), "same.txt", -1);
                    var stale = new ResourceView(window.editorContext(), source, null);
                    var current = new ResourceView(window.editorContext(), source, app.requireProject().runtime());
                    window.getEditorTabs().openEditorTab(stale);
                    window.getEditorTabs().openEditorTab(current);
                    createdWindow.set(window);
                    currentView.set(current);
                    window.navigation().runtimeChanged();
                });
                SwingUtilities.invokeAndWait(() -> {
                    assertEquals(1, createdWindow.get().getEditorTabs().getTabCount());
                    assertSame(currentView.get(), createdWindow.get().getEditorTabs().getSelectedEditor(),
                            "A late refresh must preserve a tab opened against the installed runtime");
                });
                app.close();
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

}
