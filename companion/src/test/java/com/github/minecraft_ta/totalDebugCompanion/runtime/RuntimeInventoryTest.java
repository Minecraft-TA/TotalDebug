package com.github.minecraft_ta.totalDebugCompanion.runtime;

import com.github.minecraft_ta.totaldebug.storage.RuntimeInventory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeInventoryTest {
    @TempDir Path home;

    @Test
    void readsTheSharedGameInventory() throws Exception {
        Path classes = Files.createDirectory(this.home.resolve("classes"));
        var source = new RuntimeInventory.Source(RuntimeInventory.SourceKind.DIRECTORY, classes, "logical:classes",
                new RuntimeInventory.RuntimeModule("total_debug", "TotalDebug", RuntimeInventory.ModuleKind.MOD));
        var inventory = new RuntimeInventory("abc", "21.0.12", System.getProperty("java.home"), false, List.of(source));
        Path file = this.home.resolve("inventory.json");
        inventory.write(file);
        assertEquals(inventory, RuntimeInventory.read(file));
    }

    @Test
    void rejectsWrongFormatAndEmptySources() throws Exception {
        Path file = this.home.resolve("inventory.json");
        for (String invalid : List.of("{\"format\":999}", "{\"format\":1,\"id\":\"abc\",\"javaVersion\":\"21\","
                + "\"javaHome\":\"C:/jdk\",\"production\":false,\"sources\":[]}")) {
            Files.writeString(file, invalid);
            assertThrows(java.io.IOException.class, () -> RuntimeInventory.read(file));
        }
    }
}
