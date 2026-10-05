package com.github.minecraft_ta.totalDebugCompanion.ui.categories.keybindings;

import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogFixtures;
import com.github.minecraft_ta.totalDebugCompanion.catalog.KeyBindingControl;
import com.github.minecraft_ta.totalDebugCompanion.catalog.PackCatalogService;
import com.github.minecraft_ta.totalDebugCompanion.change.ChangePipeline;
import com.github.minecraft_ta.totalDebugCompanion.game.GameLocations;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totalDebugCompanion.testui.UiTest;
import com.github.minecraft_ta.totalDebugCompanion.testui.UiTestScope;
import com.github.minecraft_ta.totalDebugCompanion.ui.categories.CategoryTestSupport;
import com.github.minecraft_ta.totalDebugCompanion.util.WindowFocus;
import com.github.minecraft_ta.totaldebug.storage.InstancePaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A binding asked for waits only while its page is shown, and a selection survives the keys read again. */
@UiTest
class KeyBindingsPanelTest {
    @TempDir Path directory;

    private static final String DROP = "key.drop";

    @Test
    @UiTest
    void keyBindingsKeepTheirSelectionWhenTheirKeysAreReadAgain() throws Exception {
        PackCatalogService catalog = CategoryTestSupport.readyCatalog(this.directory);
        Files.writeString(this.directory.resolve("options.txt"), "key_key.drop:key.keyboard.q\n");
        KeyBindingControl control = new KeyBindingControl(new ChangePipeline(GameLocations.of(this.directory, false),
                ChangeRecord.inMemory(), Runnable::run));
        KeyBindingsPanel[] panel = new KeyBindingsPanel[1];
        CategoryTestSupport.onEdt(() -> panel[0] = new KeyBindingsPanel(catalog, control, "", target -> { }));
        try {
            assertEquals(0, (int) UiTestScope.onEdt(panel[0]::reads), "a page does not read before it is shown");
            CategoryTestSupport.onEdt(() -> UiTestScope.showPages(panel[0]));
            UiTestScope.await(() -> panel[0].table().getRowCount() > 0);
            CategoryTestSupport.onEdt(() -> {
                JTable table = panel[0].table();
                int drop = -1;
                for (int row = 0; row < table.getRowCount(); row++) {
                    if (String.valueOf(table.getValueAt(row, 0)).contains("Drop Selected Item")) drop = row;
                }
                assertTrue(drop >= 0, "the binding is listed");
                table.setRowSelectionInterval(drop, drop);
            });

            // As when the game saved a key rebound in its controls screen and the user came back to Companion.
            Files.writeString(this.directory.resolve("options.txt"), "key_key.drop:key.keyboard.g\n");
            CategoryTestSupport.onEdt(() -> WindowFocus.returned().fire());
            UiTestScope.await(() -> panel[0].reads() == 2 && panel[0].loading().isDone());
            SwingUtilities.invokeAndWait(() -> { });
            CategoryTestSupport.onEdt(() -> {
                JTable table = panel[0].table();
                assertEquals(1, table.getSelectedRowCount());
                assertTrue(String.valueOf(table.getValueAt(table.getSelectedRow(), 0)).contains("Drop Selected Item"),
                        "the binding stays selected when its keys are read again");
                assertTrue(String.valueOf(table.getValueAt(table.getSelectedRow(), 1)).contains("G"), "and shows its new key");
            });
        } finally {
            CategoryTestSupport.onEdt(() -> panel[0].dispose());
        }
    }

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
