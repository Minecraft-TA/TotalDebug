package com.github.minecraft_ta.totalDebugCompanion.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeSourceManifestTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void readsTheModGeneratedFormatExactly() throws Exception {
        Path classes = Files.createDirectories(this.temporaryDirectory.resolve("class output"));
        Path archive = Files.createFile(this.temporaryDirectory.resolve("mod archive.jar"));
        Path manifest = this.temporaryDirectory.resolve("runtime-sources.txt");
        Files.writeString(manifest, String.join("\n", List.of(
                "totaldebug-runtime-sources-v1",
                classes.toUri().toASCIIString(),
                archive.toUri().toASCIIString(),
                classes.toUri().toASCIIString()
        )) + "\n");

        assertEquals(List.of(classes, archive), RuntimeSourceManifest.read(manifest));
    }

    @Test
    void rejectsUnknownFormatsAndMissingSources() throws Exception {
        Path manifest = this.temporaryDirectory.resolve("runtime-sources.txt");
        Files.writeString(manifest, "unknown-format\n");
        assertThrows(java.io.IOException.class, () -> RuntimeSourceManifest.read(manifest));

        Files.writeString(manifest, "totaldebug-runtime-sources-v1\nfile:///missing.jar\n");
        assertThrows(java.io.IOException.class, () -> RuntimeSourceManifest.read(manifest));
    }
}
