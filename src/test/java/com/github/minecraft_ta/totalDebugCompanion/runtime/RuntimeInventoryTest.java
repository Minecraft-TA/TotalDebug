package com.github.minecraft_ta.totalDebugCompanion.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeInventoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void readsTheGameRuntimeInventoryFormat() throws Exception {
        Path classes = Files.createDirectories(this.temporaryDirectory.resolve("classes"));
        Path file = this.temporaryDirectory.resolve("runtime-inventory.properties");
        Files.writeString(file, """
                format=3
                inventory.id=abc
                java.runtime.version=21.0.12
                java.home=C:/jdk
                production=false
                source.count=1
                source.0.kind=DIRECTORY
                source.0.path=%s
                source.0.logical=logical:classes
                source.0.module.id=total_debug
                source.0.module.name=TotalDebug
                source.0.module.kind=MOD
                """.formatted(classes.toUri().toASCIIString()));

        RuntimeInventory inventory = RuntimeInventory.read(file);

        assertEquals("abc", inventory.id());
        assertEquals(classes, inventory.sources().getFirst().path());
        assertEquals(RuntimeInventory.SourceKind.DIRECTORY, inventory.sources().getFirst().kind());
        assertEquals("total_debug", inventory.sources().getFirst().module().id());
        assertEquals("TotalDebug", inventory.sources().getFirst().module().displayName());
        assertEquals(RuntimeInventory.ModuleKind.MOD, inventory.sources().getFirst().module().kind());
    }

    @Test
    void rejectsAnInventoryIdentityWithoutSources() throws Exception {
        Path file = this.temporaryDirectory.resolve("runtime-inventory.properties");
        Files.writeString(file, """
                format=3
                inventory.id=abc
                java.runtime.version=21
                java.home=C:/jdk
                production=false
                source.count=0
                """);

        assertThrows(java.io.IOException.class, () -> RuntimeInventory.read(file));
    }

    @Test
    void explainsThatAMismatchedPublisherRequiresAMinecraftRestart() throws Exception {
        Path file = this.temporaryDirectory.resolve("runtime-inventory.properties");
        Files.writeString(file, """
                format=2
                inventory.id=old
                """);

        java.io.IOException exception = assertThrows(java.io.IOException.class, () -> RuntimeInventory.read(file));

        assertTrue(exception.getMessage().contains("Restart Minecraft"));
    }
}
