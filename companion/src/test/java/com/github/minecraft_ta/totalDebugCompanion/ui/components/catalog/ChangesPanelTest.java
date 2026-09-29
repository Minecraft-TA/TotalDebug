package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigLabels;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigSettings;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigSettingsFixture;
import com.github.minecraft_ta.totalDebugCompanion.catalog.KeyBindingLabels;
import com.github.minecraft_ta.totalDebugCompanion.change.ChangeLabels;
import com.github.minecraft_ta.totalDebugCompanion.game.GameLocations;
import com.github.minecraft_ta.totalDebugCompanion.navigation.ModTab;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.pack.PackLabels;
import com.github.minecraft_ta.totalDebugCompanion.pack.ResourceLabels;
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
        ConfigSettings settings = ConfigSettingsFixture.of(GameLocations.of(this.directory, false), record);
        List<ChangeLabels> labels = List.of(new ConfigLabels(settings),
                new KeyBindingLabels(new KeyBindingControl(new ChangePipeline(GameLocations.of(this.directory, false), record, Runnable::run))),
                new ResourceLabels(edits), new PackLabels(new PackSelections(edits)));
        SwingUtilities.invokeAndWait(() -> panel[0] = new ChangesPanel(catalog, record, labels, target -> { }));
        try {
            awaitOnSwing(() -> panel[0].rows("Configuration").size() == 1);
            SwingUtilities.invokeAndWait(() -> {
                ChangeLabels.Row row = panel[0].rows("Configuration").getFirst();
                assertEquals(List.of("widgets.speed", "Test Mod, testmod-common.toml", "12", "9"), List.of(row.name(), row.where(), row.now(), row.before()),
                        "the category names the row; the page only lists it");
                assertEquals(new NavigationTarget.ModPage("testmod", ModTab.CONFIGURATION, "testmod-common.toml"), row.opens(), "the file holding the setting");
                assertEquals(List.of("Show in Configuration", "Revert to 9"), List.of(row.actions().open(), row.actions().revert()));
            });

            // Written back outside Companion: the change is over.
            Files.writeString(file, Files.readString(file).replace("speed = 12", "speed = 9"));
            SwingUtilities.invokeAndWait(panel[0]::load);
            awaitOnSwing(() -> panel[0].rows("Configuration").isEmpty());
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
