package com.github.minecraft_ta.totaldebug.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class GameCatalogTest {
    @TempDir Path directory;

    @Test void freezesCapturedTintsAndRejectsNegativeIndices() {
        var colors = new HashMap<>(Map.of(0, 0xFF80A755));
        var entry = entry(colors);
        colors.put(0, -1);
        assertEquals(Map.of(0, 0xFF80A755), entry.tintColors());
        assertThrows(UnsupportedOperationException.class, () -> entry.tintColors().put(7, -1));
        assertThrows(IllegalArgumentException.class, () -> entry(Map.of(-1, -1)));
    }

    @Test void rejectsOldCaptureWithRefreshInstructionsBeforeReadingEntriesWithoutTints() throws Exception {
        Path archive = directory.resolve("old.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry(GameCatalog.MANIFEST));
            zip.write("{\"format\":1,\"entries\":[{\"id\":\"minecraft:birch_leaves\"}]}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        IOException failure = assertThrows(IOException.class, () -> GameCatalog.read(archive));
        assertTrue(failure.getMessage().contains("expected 2 with captured item tints"));
        assertTrue(failure.getMessage().contains("Browse > Refresh game data"));
    }

    private static GameCatalog.Entry entry(Map<Integer, Integer> colors) {
        return new GameCatalog.Entry(GameCatalog.Kind.ITEM, "minecraft:birch_leaves", "Birch Leaves", "Minecraft", "1.21.1",
                "BlockItem", "minecraft:birch_leaves", "minecraft:item/birch_leaves", Map.of(), colors);
    }
}
