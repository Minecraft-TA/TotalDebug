package com.github.minecraft_ta.totalDebugCompanion.ui.theme;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApplication;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionLaunchConfiguration;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.EvaluateExpressionWindow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ThemeListenerDisposalTest {
    @TempDir Path directory;

    @Test void closingApplicationsReleasesWindowAndEvaluationThemeListeners() throws Exception {
        var baseline = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> baseline.set(ThemeManager.listenerCount()));
        for (int i = 0; i < 2; i++) {
            try (var app = new CompanionApplication(new CompanionLaunchConfiguration(directory.resolve("app" + i)), "test-token")) {
                app.openProject(CompanionProfile.forGame(Files.createDirectories(directory.resolve("game" + i)))).get(3, TimeUnit.SECONDS);
                SwingUtilities.invokeAndWait(() -> {
                    var window = app.createWindow();
                    new EvaluateExpressionWindow(window, null, window.editorContext(), () -> { });
                });
            }
            SwingUtilities.invokeAndWait(() -> assertEquals(baseline.get(), ThemeManager.listenerCount(),
                    "Closing an application must release every theme listener owned by its windows"));
        }
    }
}
