package com.github.minecraft_ta.totaldebug.client.companion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeSourceManifestTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsDefaultFilesystemSourcesWithSpaces() throws Exception {
        Path classes = Files.createDirectories(this.temporaryDirectory.resolve("class output"));
        Path archive = Files.createFile(this.temporaryDirectory.resolve("mod archive.jar"));
        Path manifest = this.temporaryDirectory.resolve("runtime-sources.txt");

        RuntimeSourceManifest.write(manifest, List.of(classes, archive, classes));

        assertEquals(List.of(classes, archive), RuntimeSourceManifest.read(manifest));
        assertEquals(
                List.of(
                        "totaldebug-runtime-sources-v1",
                        classes.toUri().toASCIIString(),
                        archive.toUri().toASCIIString()
                ),
                Files.readAllLines(manifest, StandardCharsets.UTF_8)
        );
    }

    @Test
    void rejectsMissingAndMalformedSources() throws Exception {
        Path manifest = this.temporaryDirectory.resolve("runtime-sources.txt");
        Files.writeString(manifest, RuntimeSourceManifest.FORMAT_HEADER + "\nfile:///missing.jar\n");
        assertThrows(java.io.IOException.class, () -> RuntimeSourceManifest.read(manifest));

        Files.writeString(manifest, "unknown-format\n");
        assertThrows(java.io.IOException.class, () -> RuntimeSourceManifest.read(manifest));
    }
}
