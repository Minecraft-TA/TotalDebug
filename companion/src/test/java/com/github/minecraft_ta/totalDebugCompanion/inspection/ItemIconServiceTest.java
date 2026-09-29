package com.github.minecraft_ta.totalDebugCompanion.inspection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
    void anArchiveNamedByWhatItHoldsIsRestoredWhenItIsTheMostRecentlyUsed(@TempDir Path directory) throws Exception {
        Path random = archive(directory, UUID.randomUUID().toString());
        Path keyed = archive(directory, "ab".repeat(32));
        Files.setLastModifiedTime(random, FileTime.from(Instant.parse("2026-09-30T10:00:00Z")));
        Files.setLastModifiedTime(keyed, FileTime.from(Instant.parse("2026-09-30T11:00:00Z")));
        List<Runnable> reads = new ArrayList<>();
        try (ItemIconService icons = new ItemIconService()) {
            icons.restore(directory, reads::add);
            reads.getFirst().run();
            assertEquals(keyed, icons.snapshot().archive(), "the game used it last, for the packs it has now");
        }
    }

    private static Path archive(Path directory) throws Exception {
        return archive(directory, UUID.randomUUID().toString());
    }

    private static Path archive(Path directory, String name) throws Exception {
        Files.createDirectories(directory);
        Path archive = directory.resolve(name + ".zip").toAbsolutePath().normalize();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("layers/0/assets/test/models/item/thing.json"));
            zip.write("{}".getBytes());
            zip.closeEntry();
        }
        return archive;
    }
}
