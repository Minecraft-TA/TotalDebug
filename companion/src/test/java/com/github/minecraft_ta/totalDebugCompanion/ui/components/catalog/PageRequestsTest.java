package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogFixtures;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogIndex;
import com.github.minecraft_ta.totalDebugCompanion.catalog.KeyBindingControl;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ModResources;
import com.github.minecraft_ta.totalDebugCompanion.catalog.PackCatalogService;
import com.github.minecraft_ta.totalDebugCompanion.catalog.RegistryIds;
import com.github.minecraft_ta.totalDebugCompanion.change.ChangePipeline;
import com.github.minecraft_ta.totalDebugCompanion.game.GameLocations;
import com.github.minecraft_ta.totalDebugCompanion.inspection.ItemIconService;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totalDebugCompanion.testui.UiTest;
import com.github.minecraft_ta.totalDebugCompanion.testui.UiTestScope;
import com.github.minecraft_ta.totaldebug.storage.InstancePaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a navigation asks a page to select waits only while the page is shown, and a category asked for waits only
 * while nothing is listed (docs/LAST_DIFFERENCES.md, part 3).
 */
@UiTest
class PageRequestsTest {
    private static final String DROP = "key.drop";

    @TempDir Path directory;

    @Test
    void aKeyBindingAskedForIsNotSelectedAfterThePageWasLeft() throws Exception {
        try (Pages pages = keyBindingsBeforeTheCatalog()) {
            UiTestScope.onEdt(() -> pages.panel.select(DROP));
            // A click on another tab before the keys could be read.
            UiTestScope.onEdt(() -> pages.tabs.setSelectedIndex(1));
            pages.catalogCaptured();
            UiTestScope.onEdt(() -> pages.tabs.setSelectedIndex(0));
            UiTestScope.await(() -> pages.panel.table().getRowCount() > 0 && pages.panel.loading().isDone());
            UiTestScope.onEdt(() -> assertEquals(0, pages.panel.table().getSelectedRowCount(),
                    "the binding asked for before the page was left is not selected"));
        }
    }

    @Test
    void aGenericKeyBindingsRequestDropsOneAskedForBefore() throws Exception {
        try (Pages pages = keyBindingsBeforeTheCatalog()) {
            UiTestScope.onEdt(() -> {
                pages.panel.select(DROP);
                pages.panel.select("");
            });
            pages.catalogCaptured();
            UiTestScope.await(() -> pages.panel.table().getRowCount() > 0 && pages.panel.loading().isDone());
            UiTestScope.onEdt(() -> assertEquals(0, pages.panel.table().getSelectedRowCount()));
        }
    }

    @Test
    void aKeyBindingAskedForWhileThePageStaysShownIsSelected() throws Exception {
        try (Pages pages = keyBindingsBeforeTheCatalog()) {
            UiTestScope.onEdt(() -> pages.panel.select(DROP));
            pages.catalogCaptured();
            UiTestScope.await(() -> pages.panel.table().getSelectedRowCount() == 1);
            UiTestScope.onEdt(() -> {
                JTable table = pages.panel.table();
                assertTrue(String.valueOf(table.getValueAt(table.getSelectedRow(), 0)).contains("Drop Selected Item"));
            });
        }
    }

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

    @Test
    void aResourceCategoryAskedForWaitsThroughAnEmptyListing() throws Exception {
        ResourceBrowser.Prepared textures = prepared(true);
        String selected = UiTestScope.onEdt(() -> {
            ResourceBrowser browser = new ResourceBrowser(target -> { }, category -> { });
            browser.selectCategory("assets/textures");
            browser.setResources(ResourceBrowser.Prepared.NONE);
            browser.setResources(textures);
            return browser.selectedCategory();
        });
        assertEquals("assets/textures", selected);
    }

    @Test
    void aResourceCategoryAskedForBeforeAnythingIsListedIsDroppedWhenTheBrowserIsLeft() throws Exception {
        ResourceBrowser.Prepared textures = prepared(true);
        String selected = UiTestScope.onEdt(() -> {
            ResourceBrowser browser = new ResourceBrowser(target -> { }, category -> { });
            JTabbedPane tabs = new JTabbedPane();
            tabs.addTab("Resources", browser);
            tabs.addTab("Other", new JPanel());
            UiTestScope.showPages(tabs);
            browser.selectCategory("assets/textures");
            browser.setResources(ResourceBrowser.Prepared.NONE);
            // The user leaves by a click on another tab, and comes back the same way after the resources were read.
            tabs.setSelectedIndex(1);
            browser.setResources(textures);
            tabs.setSelectedIndex(0);
            return browser.selectedCategory();
        });
        assertEquals("", selected, "the category asked for before the browser was left is not selected");
    }

    @Test
    void aResourceCategoryNotListedLeavesAllForTheNextListing() throws Exception {
        ResourceBrowser.Prepared models = prepared(false);
        ResourceBrowser.Prepared textures = prepared(true);
        String selected = UiTestScope.onEdt(() -> {
            ResourceBrowser browser = new ResourceBrowser(target -> { }, category -> { });
            browser.setResources(models);
            // Asked for while a listing without it shows, as during a refresh that has not finished.
            browser.selectCategory("assets/textures");
            browser.setResources(textures);
            return browser.selectedCategory();
        });
        assertEquals("", selected, "All stays, as the user saw it chosen");
    }

    @Test
    void aContentKindNotListedLeavesAllForTheNextListing() throws Exception {
        PackCatalogService catalog = readyCatalog();
        Map<String, List<CatalogIndex.Entry>> all = catalog.index().orElseThrow().content("testmod");
        assertTrue(all.containsKey(RegistryIds.FLUID) && all.containsKey(RegistryIds.ITEM), all.keySet()::toString);
        Map<String, List<CatalogIndex.Entry>> items = new LinkedHashMap<>();
        items.put(RegistryIds.ITEM, all.get(RegistryIds.ITEM));
        try (ItemIconService icons = new ItemIconService()) {
            String selected = UiTestScope.onEdt(() -> {
                ContentBrowser browser = new ContentBrowser(new CatalogIcons(icons, 16), entry -> null, target -> { }, null);
                browser.setContent(items);
                browser.select(RegistryIds.FLUID);
                browser.setContent(all);
                return browser.selectedKind();
            });
            assertEquals("", selected, "All stays, as the user saw it chosen");
        }
    }

    /** A mod's resources, prepared off the Swing thread: its models, and its textures too where asked. */
    private ResourceBrowser.Prepared prepared(boolean withTextures) throws Exception {
        Path mod = this.directory.resolve(withTextures ? "with-textures" : "models-only");
        Files.createDirectories(mod.resolve("assets/testmod/models/item"));
        Files.writeString(mod.resolve("assets/testmod/models/item/widget.json"), "{}");
        if (withTextures) {
            Files.createDirectories(mod.resolve("assets/testmod/textures/item"));
            Files.write(mod.resolve("assets/testmod/textures/item/widget.png"), new byte[0]);
        }
        return ResourceBrowser.prepare(ModResources.list(mod), Map.of(), Map.of());
    }

    private PackCatalogService readyCatalog() throws Exception {
        InstancePaths paths = new InstancePaths(this.directory.resolve("total-debug"));
        CatalogFixtures.catalog(CatalogFixtures.modJar(this.directory)).write(paths.catalog());
        PackCatalogService catalog = new PackCatalogService(paths);
        catalog.accept(CatalogFixtures.INVENTORY, paths.catalog(), Runnable::run);
        return catalog;
    }

    /** A Key bindings page shown in a tab beside another, before the catalog is captured, so its keys wait. */
    private Pages keyBindingsBeforeTheCatalog() throws Exception {
        Files.writeString(this.directory.resolve("options.txt"), "key_key.drop:key.keyboard.q\n");
        InstancePaths paths = new InstancePaths(this.directory.resolve("total-debug"));
        PackCatalogService catalog = new PackCatalogService(paths);
        KeyBindingControl control = new KeyBindingControl(new ChangePipeline(GameLocations.of(this.directory, false),
                ChangeRecord.inMemory(), Runnable::run));
        Pages pages = UiTestScope.onEdt(() -> {
            KeyBindingsPanel panel = new KeyBindingsPanel(catalog, control, "", target -> { });
            JTabbedPane tabs = new JTabbedPane();
            tabs.addTab("Key bindings", panel);
            tabs.addTab("Other", new JPanel());
            UiTestScope.showPages(tabs);
            return new Pages(this.directory, paths, catalog, panel, tabs);
        });
        // Shown without a catalog, the page reads nothing yet and says why.
        UiTestScope.await(() -> pages.panel.isShowing());
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(0, (int) UiTestScope.onEdt(pages.panel::reads));
        return pages;
    }

    private record Pages(Path directory, InstancePaths paths, PackCatalogService catalog, KeyBindingsPanel panel,
                         JTabbedPane tabs) implements AutoCloseable {
        /** The catalog is captured, so the keys can be read. */
        void catalogCaptured() throws Exception {
            CatalogFixtures.catalog(CatalogFixtures.modJar(this.directory)).write(this.paths.catalog());
            this.catalog.accept(CatalogFixtures.INVENTORY, this.paths.catalog(), Runnable::run);
        }

        @Override
        public void close() throws Exception {
            UiTestScope.onEdt(this.panel::dispose);
        }
    }
}
