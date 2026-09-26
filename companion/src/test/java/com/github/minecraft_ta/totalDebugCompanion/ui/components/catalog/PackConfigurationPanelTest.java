package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogFixtures;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigChanges;
import com.github.minecraft_ta.totalDebugCompanion.catalog.PackCatalogService;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totaldebug.storage.InstancePaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JCheckBox;
import javax.swing.SwingUtilities;

import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackConfigurationPanelTest {
    @TempDir Path directory;

    @Test
    void listsModifiedSettingsUnderTheirModFileAndSectionThenAll() throws Exception {
        Path jar = CatalogFixtures.modJar(this.directory);
        Path file = jar.resolveSibling("testmod-common.toml");
        Files.writeString(file, """
                [widgets]
                \tspeed = 9
                \tmode = "FAST"
                """);
        InstancePaths paths = new InstancePaths(this.directory.resolve("total-debug"));
        CatalogFixtures.catalog(jar).write(paths.catalog());
        PackCatalogService catalog = new PackCatalogService(paths);
        catalog.accept(CatalogFixtures.INVENTORY, paths.catalog(), Runnable::run);
        PackConfigurationPanel[] panel = new PackConfigurationPanel[1];
        SwingUtilities.invokeAndWait(() -> panel[0] = new PackConfigurationPanel(catalog, this.directory,
                new ConfigChanges(this.directory, ChangeRecord.inMemory()), target -> { }));
        ConfigSettingsTable table = table(panel[0]);
        try {
            awaitOnSwing(() -> table.getRowCount() == 4);
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(List.of("Test Mod", "testmod-common.toml", "widgets", "speed"),
                        List.of(table.row(0).name(), table.row(1).name(), table.row(2).name(), table.row(3).name()));
                assertEquals("9", table.row(3).value());
                assertTrue(table.getToolTipText(new MouseEvent(table, 0, 0, 0, 5,
                        table.getCellRect(1, 0, true).y + 2, 0, false)).contains("testmod-common.toml"));
                table.reset(table.row(3));
            });
            // The reset setting no longer differs, so nothing is left to list.
            awaitOnSwing(() -> table.getRowCount() == 0);
            assertTrue(Files.readString(file).contains("\tspeed = 4\n"));

            SwingUtilities.invokeAndWait(() -> table.getActionMap().get("undoConfigEdit").actionPerformed(null));
            awaitOnSwing(() -> table.getRowCount() == 4);
            assertTrue(Files.readString(file).contains("\tspeed = 9\n"));

            // Without the Modified filter every setting is listed.
            SwingUtilities.invokeAndWait(() -> component(panel[0], JCheckBox.class).doClick());
            awaitOnSwing(() -> table.getRowCount() == 5 && "mode".equals(table.row(4).name()));
        } finally {
            SwingUtilities.invokeAndWait(panel[0]::dispose);
        }
    }

    private static <T extends Component> T component(Container root, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container container) {
                T found = component(container, type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static ConfigSettingsTable table(Container root) {
        for (Component child : root.getComponents()) {
            if (child instanceof ConfigSettingsTable table) return table;
            if (child instanceof Container container) {
                ConfigSettingsTable found = table(container);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void awaitOnSwing(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        boolean[] met = new boolean[1];
        while (true) {
            SwingUtilities.invokeAndWait(() -> met[0] = condition.getAsBoolean());
            if (met[0]) return;
            if (System.nanoTime() > deadline) throw new AssertionError("Timed out waiting on the Swing thread");
            Thread.sleep(20);
        }
    }
}
