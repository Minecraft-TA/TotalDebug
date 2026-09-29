package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.catalog.ModResources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceBrowserTest {
    @TempDir Path directory;

    @Test
    void emptyingAListOfEveryResourceDoesNotMeasureEachRow() throws Exception {
        List<ModResources.Resource> resources = new ArrayList<>();
        for (int index = 0; index < 40_000; index++) {
            resources.add(new ModResources.Resource(this.directory, false, "assets/testmod/models/item/widget_" + index + ".json",
                    new ModResources.Category("assets", "models")));
        }
        long[] took = new long[1];
        ResourceBrowser[] browser = new ResourceBrowser[1];
        SwingUtilities.invokeAndWait(() -> {
            browser[0] = new ResourceBrowser(target -> { }, category -> { });
            JScrollPane shown = new JScrollPane(browser[0]);
            shown.setSize(800, 600);
            shown.doLayout();
            browser[0].setResources(resources);
            // As while the catalog is captured again: the list empties before the resources come back.
            long started = System.nanoTime();
            browser[0].setResources(List.of());
            browser[0].setResources(resources);
            took[0] = System.nanoTime() - started;
        });
        assertEquals(40_000, browser[0].rowCount());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(took[0]) < 1_000,
                "rendering every row to measure it took seconds on the Swing thread: " + TimeUnit.NANOSECONDS.toMillis(took[0]) + " ms");
    }
}
