package com.github.minecraft_ta.totalDebugCompanion;

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
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ApplicationNavigationTest {
    @TempDir Path directory;
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
            try { app.installRuntimeSnapshot(snapshot, RuntimeSnapshotBytecodeSource.fromIndexedSources(snapshot.sources(), snapshot.index())); }
            catch (RuntimeException failure) { snapshot.close(); throw failure; }
            assertEquals(new NavigationTarget.RuntimeLine("java.lang.Object", 7), ui.navigation.get(10, TimeUnit.SECONDS));
        }
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

    private static final class RecordingUi implements CompanionUi {
        Runnable onRefresh = () -> { };
        final CompletableFuture<NavigationTarget> navigation = new CompletableFuture<>();
        public boolean prepareProjectSwitch() { return true; }
        public boolean closeProjectViews() { return true; }
        public boolean canExit() { return true; }
        public void setSwitching(boolean switching) { }
        public void refreshProfile() { onRefresh.run(); }
        public void runtimeChanged() { }
        public void setGameStatus(ServiceStatus status) { }
        public void setMcpStatus(ServiceStatus status) { }
        public void setRuntimeIndexStatus(RuntimeIndexService.Status status) { }
        public void navigate(NavigationTarget target, NavigationService.Activation activation) { navigation.complete(target); }
        public void focus() { }
        public void showError(String title, String message) { fail(title + ": " + message); }
        public void dispose() { }
    }
}
