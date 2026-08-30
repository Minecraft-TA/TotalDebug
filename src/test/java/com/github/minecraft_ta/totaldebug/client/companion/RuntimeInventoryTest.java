package com.github.minecraft_ta.totaldebug.client.companion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeInventoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsOrderedRuntimeSources() throws Exception {
        Path classes = Files.createDirectories(this.temporaryDirectory.resolve("classes"));
        Path archive = Files.write(this.temporaryDirectory.resolve("runtime.jar"), new byte[]{1, 2, 3});
        RuntimeInventory inventory = new RuntimeInventory(
                "inventory-id",
                "21.0.12",
                "jdk",
                false,
                List.of(
                        new RuntimeInventory.Source(
                                RuntimeInventory.SourceKind.DIRECTORY,
                                classes,
                                "logical:classes",
                                new RuntimeInventory.RuntimeModule(
                                        "total_debug",
                                        "TotalDebug",
                                        RuntimeInventory.ModuleKind.MOD
                                )
                        ),
                        new RuntimeInventory.Source(
                                RuntimeInventory.SourceKind.ARCHIVE,
                                archive,
                                "logical:archive",
                                new RuntimeInventory.RuntimeModule(
                                        "minecraft",
                                        "Minecraft",
                                        RuntimeInventory.ModuleKind.PLATFORM
                                )
                        )
                )
        );
        Path file = this.temporaryDirectory.resolve(RuntimeInventory.FILE_NAME);

        inventory.write(file);
        RuntimeInventory restored = RuntimeInventory.read(file);

        assertEquals("inventory-id", restored.id());
        assertEquals(inventory.sources(), restored.sources());
    }

    @Test
    void rejectsAnUnavailablePublishedSource() throws Exception {
        Path file = this.temporaryDirectory.resolve(RuntimeInventory.FILE_NAME);
        Files.writeString(file, """
                format=3
                inventory.id=id
                java.runtime.version=21
                java.home=jdk
                production=false
                source.count=1
                source.0.kind=ARCHIVE
                source.0.path=file:///missing.jar
                source.0.logical=logical:missing
                source.0.module.id=missing
                source.0.module.name=Missing
                source.0.module.kind=LIBRARY
                """);

        assertThrows(java.io.IOException.class, () -> RuntimeInventory.read(file));
    }
}
