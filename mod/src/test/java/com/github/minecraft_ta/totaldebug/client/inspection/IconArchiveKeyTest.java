package com.github.minecraft_ta.totaldebug.client.inspection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IconArchiveKeyTest {
    private static final byte[] FLUIDS = "{}".getBytes();
    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");

    @TempDir Path directory;

    @Test
    void theSamePacksWithTheSameFilesHaveTheSameKey() throws Exception {
        Path pack = pack("Faithful");
        List<IconArchiveKey.Part> parts = List.of(IconArchiveKey.Part.builtIn("vanilla"), new IconArchiveKey.Part("file/Faithful", List.of(pack)));
        Optional<String> key = IconArchiveKey.of("1.21.1", parts, FLUIDS, NOW);
        assertTrue(key.isPresent());
        assertEquals(key, IconArchiveKey.of("1.21.1", parts, FLUIDS, NOW), "a reload that changed nothing uses the archive again");

        assertNotEquals(key, IconArchiveKey.of("1.21.2", parts, FLUIDS, NOW), "another version of the game");
        assertNotEquals(key, IconArchiveKey.of("1.21.1", parts.reversed(), FLUIDS, NOW), "the packs in another order");
        assertNotEquals(key, IconArchiveKey.of("1.21.1", parts, "{\"water\":{}}".getBytes(), NOW), "fluids that look different");
    }

    @Test
    void aChangedFileInAPackChangesTheKey() throws Exception {
        Path pack = pack("Faithful");
        List<IconArchiveKey.Part> parts = List.of(new IconArchiveKey.Part("file/Faithful", List.of(pack)));
        Optional<String> before = IconArchiveKey.of("1.21.1", parts, FLUIDS, NOW);

        Path texture = pack.resolve("assets/minecraft/textures/block/sand.png");
        Files.write(texture, new byte[]{1, 2, 3, 4});
        Files.setLastModifiedTime(texture, FileTime.from(NOW.minusSeconds(30)));
        Optional<String> saved = IconArchiveKey.of("1.21.1", parts, FLUIDS, NOW);
        assertNotEquals(before, saved, "a texture saved into the pack");

        Path overlay = Files.createDirectories(pack.resolve("overlay_48/assets/minecraft/models/item")).resolve("sand.json");
        Files.writeString(overlay, "{}");
        Files.setLastModifiedTime(overlay, FileTime.from(NOW.minusSeconds(30)));
        Optional<String> withOverlay = IconArchiveKey.of("1.21.1", parts, FLUIDS, NOW);
        assertTrue(withOverlay.isPresent());
        assertNotEquals(saved, withOverlay, "an overlay folder counts like every file of the pack");
        assertEquals(withOverlay, IconArchiveKey.of("1.21.1", parts, FLUIDS, NOW.plusSeconds(60)), "the key does not depend on when it is taken");
    }

    @Test
    void aPackWhoseFilesCannotBeToldHasNoKey() throws Exception {
        Path pack = pack("Faithful");
        assertEquals(Optional.empty(), IconArchiveKey.of("1.21.1", List.of(IconArchiveKey.Part.unknown("generated")), FLUIDS, NOW),
                "a pack a mod builds in memory");
        assertEquals(Optional.empty(), IconArchiveKey.of("1.21.1",
                List.of(new IconArchiveKey.Part("file/Gone", List.of(this.directory.resolve("Gone")))), FLUIDS, NOW), "a pack removed since");

        Path texture = pack.resolve("assets/minecraft/textures/block/sand.png");
        Files.setLastModifiedTime(texture, FileTime.from(NOW.minusMillis(500)));
        assertEquals(Optional.empty(), IconArchiveKey.of("1.21.1", List.of(new IconArchiveKey.Part("file/Faithful", List.of(pack))), FLUIDS, NOW),
                "a file written a moment ago may change again within the same time");
    }

    /** A folder pack with a texture and its pack.mcmeta, last written a minute before {@link #NOW}. */
    private Path pack(String name) throws Exception {
        Path pack = Files.createDirectories(this.directory.resolve(name));
        Path texture = Files.createDirectories(pack.resolve("assets/minecraft/textures/block")).resolve("sand.png");
        Files.write(texture, new byte[]{1, 2, 3});
        Path meta = pack.resolve("pack.mcmeta");
        Files.writeString(meta, "{\"pack\":{\"pack_format\":34,\"description\":\"\"}}");
        for (Path file : List.of(texture, meta)) Files.setLastModifiedTime(file, FileTime.from(NOW.minusSeconds(60)));
        return pack;
    }
}
