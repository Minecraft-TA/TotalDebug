package com.github.minecraft_ta.totalDebugCompanion.inspection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.swing.SwingUtilities;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ItemIconServiceTest {
    @Test
    void onlyTheNewestRestoreAdoptsItsSnapshotWhateverOrderTheReadsFinishIn(@TempDir Path directory) throws Exception {
        Path first = archive(directory.resolve("first"));
        Path second = archive(directory.resolve("second"));
        List<Runnable> reads = new ArrayList<>();
        try (ItemIconService icons = new ItemIconService()) {
            icons.restore(first.getParent(), reads::add);
            icons.restore(second.getParent(), reads::add);

            reads.get(1).run();
            reads.get(0).run();

            assertEquals(second, icons.snapshot().archive(), "the project switched to last keeps its icons");
        }
    }

    @Test
    void anAnnouncedSnapshotSupersedesARestoreStillReading(@TempDir Path directory) throws Exception {
        Path saved = archive(directory.resolve("saved"));
        Path announced = archive(directory.resolve("announced"));
        List<Runnable> reads = new ArrayList<>();
        try (ItemIconService icons = new ItemIconService()) {
            icons.restore(saved.getParent(), reads::add);
            icons.accept(announced);

            reads.getFirst().run();

            assertEquals(announced, icons.snapshot().archive());
        }
    }

    @Test
    void viewsAreToldOnceOnTheSwingThreadOnlyWhenTheSnapshotChanged(@TempDir Path directory) throws Exception {
        Path archive = archive(directory.resolve("snapshot"));
        List<Boolean> told = new CopyOnWriteArrayList<>();
        try (ItemIconService icons = new ItemIconService()) {
            icons.changed().subscribe(() -> told.add(SwingUtilities.isEventDispatchThread()));
            icons.accept(archive);
            icons.accept(archive);
            SwingUtilities.invokeAndWait(() -> { });

            assertEquals(List.of(true), told, "the same snapshot again tells nothing");
        }
    }

    private static Path archive(Path directory) throws Exception {
        Files.createDirectories(directory);
        Path archive = directory.resolve(UUID.randomUUID() + ".zip").toAbsolutePath().normalize();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("layers/0/assets/test/models/item/thing.json"));
            zip.write("{}".getBytes());
            zip.closeEntry();
        }
        return archive;
    }
}
