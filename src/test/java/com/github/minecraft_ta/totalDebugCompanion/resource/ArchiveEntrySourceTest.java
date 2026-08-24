package com.github.minecraft_ta.totalDebugCompanion.resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void enforcesTheDeclaredSizeBeforeOpening() {
        ArchiveEntrySource source = new ArchiveEntrySource(
                this.directory.resolve("missing.jar"),
                "large.txt",
                100
        );
        assertThrows(ResourceTooLargeException.class, () -> source.read(10));
    }
}
