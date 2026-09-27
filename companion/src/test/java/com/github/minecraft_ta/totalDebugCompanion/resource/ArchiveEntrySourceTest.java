package com.github.minecraft_ta.totalDebugCompanion.resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchiveEntrySourceTest {

    @TempDir
    Path directory;

    @Test
    void readsAnEntryWithoutKeepingTheArchiveOpen() throws Exception {
        Path archive = this.directory.resolve("sample.jar");
        byte[] expected = "enabled = true\n".getBytes(StandardCharsets.UTF_8);
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new ZipEntry("config/sample.toml"));
            output.write(expected);
            output.closeEntry();
        }

        ArchiveEntrySource source = new ArchiveEntrySource(archive, "config/sample.toml", expected.length);
        assertArrayEquals(expected, source.read(1024));
        assertEquals(archive.toAbsolutePath().normalize() + "!/config/sample.toml", source.identity());

        Files.move(archive, this.directory.resolve("renamed.jar"));
    }

    @Test
    void findsOnlyFileEntriesAndTheirAdjacentFiles() throws Exception {
        Path archive = this.directory.resolve("textures.jar");
        write(archive, "assets/testmod/textures/block/", null, "assets/testmod/textures/block/gear.png", "png",
                "assets/testmod/textures/block/gear.png.mcmeta", "{\"animation\":{}}");

        ArchiveEntrySource texture = new ArchiveEntrySource(archive, "assets/testmod/textures/block/gear.png", -1);
        assertArrayEquals("png".getBytes(StandardCharsets.UTF_8), texture.read(1024));
        assertEquals("{\"animation\":{}}", new String(texture.readAdjacent(".mcmeta", 1024).orElseThrow(), StandardCharsets.UTF_8));
        assertTrue(texture.readAdjacent(".missing", 1024).isEmpty());
        assertThrows(FileNotFoundException.class, () -> new ArchiveEntrySource(archive, "assets/testmod/missing.png", -1).read(1024));
        assertThrows(FileNotFoundException.class, () -> new ArchiveEntrySource(archive, "assets/testmod/textures/block", -1).read(1024),
                "a folder is not read as a file, even named without its slash");
        assertThrows(ResourceTooLargeException.class, () -> texture.read(2), "an entry of unknown size is still read only up to the limit");
    }

    @Test
    void readsAnArchiveReplacedSinceTheLastRead() throws Exception {
        Path archive = this.directory.resolve("pack.jar");
        write(archive, "lang/en_us.json", "old");
        ArchiveEntrySource source = new ArchiveEntrySource(archive, "lang/en_us.json", -1);
        assertArrayEquals("old".getBytes(StandardCharsets.UTF_8), source.read(1024));

        write(archive, "extra.txt", "moves the entry", "lang/en_us.json", "new text");
        Files.setLastModifiedTime(archive, FileTime.fromMillis(Files.getLastModifiedTime(archive).toMillis() + 2_000));
        assertArrayEquals("new text".getBytes(StandardCharsets.UTF_8), source.read(1024),
                "the reader keeps an archive's directory only while the archive is unchanged");
    }

    /** Writes an archive of names and texts; a null text makes a folder entry. */
    private static void write(Path archive, String... namesAndTexts) throws Exception {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (int index = 0; index < namesAndTexts.length; index += 2) {
                output.putNextEntry(new ZipEntry(namesAndTexts[index]));
                if (namesAndTexts[index + 1] != null) output.write(namesAndTexts[index + 1].getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
    }

    @Test
    void enforcesTheDeclaredSizeBeforeOpening() {
        ArchiveEntrySource source = new ArchiveEntrySource(
                this.directory.resolve("missing.jar"),
                "large.txt",
                100
        );
        assertThrows(ResourceTooLargeException.class, () -> source.read(10));
    }
}
