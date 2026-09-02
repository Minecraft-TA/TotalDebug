package com.github.minecraft_ta.totaldebug.client.companion;

import com.github.minecraft_ta.totaldebug.storage.RuntimeInventory;

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
        Path file = this.temporaryDirectory.resolve("inventory.json");

        inventory.write(file);
        RuntimeInventory restored = RuntimeInventory.read(file);

        assertEquals("inventory-id", restored.id());
        assertEquals(inventory.sources(), restored.sources());
    }

    @Test
    void rejectsAnUnavailablePublishedSource() throws Exception {
        Path file = this.temporaryDirectory.resolve("inventory.json");
        RuntimeInventory inventory = new RuntimeInventory("missing-source", "21", System.getProperty("java.home"),
                false, List.of(new RuntimeInventory.Source(RuntimeInventory.SourceKind.ARCHIVE,
                this.temporaryDirectory.resolve("missing.jar"), "logical:missing",
                new RuntimeInventory.RuntimeModule("missing", "Missing", RuntimeInventory.ModuleKind.LIBRARY))));
        com.github.minecraft_ta.totaldebug.storage.JsonFiles.write(file, inventory.toJson());

        assertThrows(java.io.IOException.class, () -> RuntimeInventory.read(file));
    }
}
