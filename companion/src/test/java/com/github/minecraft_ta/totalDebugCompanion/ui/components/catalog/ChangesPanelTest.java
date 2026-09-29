package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigSettingsFixture;
import com.github.minecraft_ta.totalDebugCompanion.game.GameLocations;
import com.github.minecraft_ta.totalDebugCompanion.storage.InstanceState;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogFixtures;
import com.github.minecraft_ta.totalDebugCompanion.catalog.KeyBindingControl;
import com.github.minecraft_ta.totalDebugCompanion.change.ChangePipeline;
import com.github.minecraft_ta.totalDebugCompanion.catalog.PackCatalogService;
import com.github.minecraft_ta.totalDebugCompanion.pack.PackSelections;
import com.github.minecraft_ta.totalDebugCompanion.pack.GamePacks;
import com.github.minecraft_ta.totalDebugCompanion.pack.ResourceEdits;
import com.github.minecraft_ta.totalDebugCompanion.pack.ResourceEditsFixture;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totalDebugCompanion.storage.ResourceOriginals;
import com.github.minecraft_ta.totaldebug.storage.InstancePaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangesPanelTest {
    @TempDir Path directory;

    @Test
    void listsRecordedChangesUntilTheFileHoldsTheOriginalAgain() throws Exception {
        Path jar = CatalogFixtures.modJar(this.directory);
        Path file = Files.createDirectories(jar.resolveSibling("config")).resolve("testmod-common.toml");
        Files.writeString(file, """
                [widgets]
                \tspeed = 12
                \tmode = "FAST"
                """);
        InstancePaths paths = new InstancePaths(this.directory.resolve("total-debug"));
        CatalogFixtures.catalog(jar).write(paths.catalog());
        PackCatalogService catalog = new PackCatalogService(paths);
        catalog.accept(CatalogFixtures.INVENTORY, paths.catalog(), Runnable::run);
        ChangeRecord record = ChangeRecord.inMemory();
        record.changed(new ChangeRecord.Setting("testmod", "testmod-common.toml", file, "widgets.speed"), "9", "12");
        ChangesPanel[] panel = new ChangesPanel[1];
        ResourceEdits edits = ResourceEditsFixture.edits(GameLocations.of(this.directory, false), record, new ResourceOriginals(this.directory.resolve("originals")),
                Runnable::run, InstanceState.inMemory());
        SwingUtilities.invokeAndWait(() -> panel[0] = new ChangesPanel(catalog, ConfigSettingsFixture.of(GameLocations.of(this.directory, false), record),
                new KeyBindingControl(new ChangePipeline(GameLocations.of(this.directory, false), record, Runnable::run)), edits,
                new PackSelections(edits), target -> { }));
        ConfigSettingsTable table = panel[0].settingsTable();
        try {
            awaitOnSwing(() -> table.getRowCount() == 3);
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(List.of("Test Mod", "testmod-common.toml", "widgets.speed"),
                        List.of(table.row(0).name(), table.row(1).name(), table.row(2).name()));
                assertEquals("12", table.row(2).value());
                String tooltip = ConfigSettingsTable.tooltip(table.row(2), null, "9");
                assertTrue(tooltip.contains("Before your edit"), tooltip);
            });

            // Written back outside Companion: the change is over.
            Files.writeString(file, Files.readString(file).replace("speed = 12", "speed = 9"));
            SwingUtilities.invokeAndWait(panel[0]::load);
            awaitOnSwing(() -> table.getRowCount() == 0);
            assertEquals(0, record.size());
        } finally {
            SwingUtilities.invokeAndWait(panel[0]::dispose);
        }
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
