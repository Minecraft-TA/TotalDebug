package com.github.minecraft_ta.totalDebugCompanion.ui.categories.resources;

import com.github.minecraft_ta.totalDebugCompanion.catalog.ModResources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.ListCellRenderer;
import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceBrowserTest {
    @TempDir Path directory;

    @Test
    void emptyingAListOfEveryResourceDoesNotRenderEachRowToMeasureIt() throws Exception {
        List<ModResources.Resource> resources = new ArrayList<>();
        for (int index = 0; index < 40_000; index++) {
            resources.add(new ModResources.Resource(this.directory, false, "assets/testmod/models/item/widget_" + index + ".json",
                    new ModResources.Category("assets", "models"), new ModResources.Version(0, 0)));
        }
        ResourceBrowser.Prepared prepared = ResourceBrowser.prepare(resources, Map.of(), Map.of());
        AtomicInteger rendered = new AtomicInteger();
        ResourceBrowser[] browser = new ResourceBrowser[1];
        SwingUtilities.invokeAndWait(() -> {
            browser[0] = new ResourceBrowser(target -> { });
            JScrollPane shown = new JScrollPane(browser[0]);
            shown.setSize(800, 600);
            shown.doLayout();
            browser[0].setResources(prepared);
            JList<ModResources.Resource> list = browser[0].resourceList();
            ListCellRenderer<? super ModResources.Resource> renderer = list.getCellRenderer();
            list.setCellRenderer((owner, value, index, selected, focused) -> {
                rendered.incrementAndGet();
                return renderer.getListCellRendererComponent(owner, value, index, selected, focused);
            });
            // As while the catalog is captured again: the list empties before the resources come back.
            browser[0].setResources(ResourceBrowser.Prepared.NONE);
            browser[0].setResources(prepared);
        });
        assertEquals(40_000, browser[0].rowCount());
        assertTrue(rendered.get() < 100, "the list rendered " + rendered.get() + " rows to measure them, on the Swing thread");
    }
}
