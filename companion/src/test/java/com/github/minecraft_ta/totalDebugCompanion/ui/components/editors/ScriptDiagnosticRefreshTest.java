package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.CompanionApplication;
import com.github.minecraft_ta.totalDebugCompanion.GlobalConfig;
import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import com.github.minecraft_ta.totalDebugCompanion.model.ScriptView;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionLaunchConfiguration;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.JavaAnalysisFixtures;
import javax.swing.SwingUtilities;
import static org.junit.jupiter.api.Assertions.*;

class ScriptDiagnosticRefreshTest {
    @TempDir Path directory;

    @Test void publishedAstRefreshesNoticesWithoutAnotherKeystrokeAndDisposalUnsubscribes() throws Exception {
        Path home = Files.createDirectories(directory.resolve("app"));
        GlobalConfig.getInstance().loadFrom(home);
        var configure = CompanionApp.class.getDeclaredMethod("configureLookAndFeel");
        configure.setAccessible(true);
        configure.invoke(null);
        try (var index = JavaAnalysisFixtures.index();
             var app = new CompanionApplication(new CompanionLaunchConfiguration(home), "test-token")) {
            app.openProject(CompanionProfile.forGame(Files.createDirectories(directory.resolve("game")))).get(10, TimeUnit.SECONDS);
            ScriptPanel[] selected = new ScriptPanel[1];
            var initial = new CountDownLatch(1);
            var fixedResult = new CountDownLatch(1);
            Files.createDirectories(CompanionProfile.forGame(directory.resolve("game")).dataDirectory().resolve("scripts"));
            Files.writeString(CompanionProfile.forGame(directory.resolve("game")).dataDirectory().resolve("scripts/DiagnosticProof.tdscript"), "");
            SwingUtilities.invokeAndWait(() -> {
                var window = app.createWindow();
                CompanionClassIndex.set(index);
                var panel = (ScriptPanel) new ScriptView(window.editorContext(), window.editorContext().project().paths().scripts().resolve("DiagnosticProof.tdscript")).getComponent();
                panel.editorPane.setParserDelay(Integer.MAX_VALUE);
                panel.astCache().addChangeListener(panel.astKey(), snapshot -> {
                    if (snapshot == null) return;
                    if (snapshot.contents().equals("return missing;")) initial.countDown();
                    if (snapshot.contents().equals("return 1;")) fixedResult.countDown();
                });
                panel.editorPane.setText("return missing;");
                selected[0] = panel;
            });
            var panel = selected[0];
            assertTrue(initial.await(10, TimeUnit.SECONDS), "Normal document edits must trigger analysis");
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(panel.editorPane.getParserNotices().stream().anyMatch(notice -> notice.getMessage().contains("missing")));
                panel.editorPane.append(" ");
                assertTrue(panel.editorPane.getParserNotices().stream().anyMatch(notice -> notice.getMessage().contains("missing")),
                        "Unrelated edits must retain the error while analysis is pending");
                panel.editorPane.setText("return 1;");
            });
            assertTrue(fixedResult.await(10, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(panel.editorPane.getParserNotices().isEmpty());
                panel.dispose();
                assertNull(panel.astCache().getSnapshot(panel.astKey()));
            });
        } finally { CompanionClassIndex.clear(); }
    }
}
