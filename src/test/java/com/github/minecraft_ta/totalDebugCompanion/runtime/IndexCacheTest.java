package com.github.minecraft_ta.totalDebugCompanion.runtime;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.minecraft_ta.totaldebug.storage.RuntimeInventory;
import com.github.tth05.jindex.ClassIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class IndexCacheTest {
    @TempDir Path home;

    @Test
    void roundTripsNativeIndexAndSourceIdentityAsOneFile() throws Exception {
        Path archive = Files.createFile(this.home.resolve("prepared.jar"));
        String logical = "file:///original/mod.jar!/META-INF/jarjar/dependency.jar";
        var source = new RuntimeSnapshotBytecodeSource.Source(7, archive, logical,
                new RuntimeInventory.RuntimeModule("example", "Example Mod", RuntimeInventory.ModuleKind.MOD));
        Path file = this.home.resolve("index.jindex");
        var manifest = new IndexCache.Manifest("test", List.of(source));
        try (var input = IndexCacheTest.class.getResourceAsStream("IndexCacheTest.class");
             ClassIndex index = ClassIndex.fromBytes(List.of(input.readAllBytes()))) {
            IndexCache.write(file, index, manifest);
            assertEquals(manifest, IndexCache.read(file));
            try (ClassIndex restored = ClassIndex.fromFile(file.toString())) {
                assertNotNull(restored.findClass(IndexCacheTest.class.getName()));
            }
            byte[] previous = Files.readAllBytes(file);
            var missing = new RuntimeSnapshotBytecodeSource.Source(7, this.home.resolve("missing.jar"), logical, source.module());
            assertThrows(java.io.IOException.class, () -> IndexCache.write(file, index,
                    new IndexCache.Manifest("invalid", List.of(missing))));
            assertArrayEquals(previous, Files.readAllBytes(file));
            try (var children = Files.list(this.home)) {
                assertEquals(2, children.count());
            }
        }
    }
}
