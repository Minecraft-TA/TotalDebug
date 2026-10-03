package com.github.minecraft_ta.totalDebugCompanion.ui.categories.logs;

import com.github.minecraft_ta.totalDebugCompanion.catalog.PackCatalogService;
import com.github.minecraft_ta.totalDebugCompanion.testui.UiTest;
import com.github.minecraft_ta.totalDebugCompanion.testui.UiTestScope;
import com.github.minecraft_ta.totaldebug.storage.InstancePaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

@UiTest
class LogsPanelTest {
    @TempDir Path directory;

    @Test
    void aLogFileTheUserChoseIsNotReplacedByOneAskedForBefore() throws Exception {
        Path logs = Files.createDirectories(this.directory.resolve("logs"));
        Files.writeString(logs.resolve("latest.log"), "[12:00:00] [main/INFO]: Started\n");
        Files.writeString(logs.resolve("debug.log"), "[12:00:00] [main/DEBUG]: Started\n");
        Path report = this.directory.resolve("crash-reports").resolve("crash-2026-10-02_12.00.00-client.txt");
        PackCatalogService catalog = new PackCatalogService(new InstancePaths(this.directory.resolve("total-debug")));
        LogsPanel panel = UiTestScope.onEdt(() -> new LogsPanel(catalog, this.directory, target -> { }));
        try {
            UiTestScope.onEdt(() -> UiTestScope.showPages(panel));
            UiTestScope.await(() -> panel.fileList().getModel().getSize() == 2 && panel.selectedFile() != null);
            int listed = UiTestScope.onEdt(panel::listings);
            Path chosen = UiTestScope.onEdt(() -> {
                // A crash report the list does not hold yet is looked for by listing again; the user chooses a file
                // before that listing is back.
                panel.select(report);
                panel.fileList().setSelectedIndex(1);
                return panel.selectedFile();
            });
            UiTestScope.await(() -> panel.listings() > listed && panel.listing().isDone());
            SwingUtilities.invokeAndWait(() -> { });
            assertEquals(chosen, UiTestScope.onEdt(panel::selectedFile), "the file the user chose stays selected");
        } finally {
            UiTestScope.onEdt(panel::dispose);
        }
    }
}
