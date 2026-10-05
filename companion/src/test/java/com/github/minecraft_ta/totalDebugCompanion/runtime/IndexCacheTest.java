package com.github.minecraft_ta.totalDebugCompanion.runtime;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.minecraft_ta.totaldebug.storage.RuntimeInventory;
import com.github.tth05.jindex.ClassIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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
            try (ClassIndex validated = IndexCache.write(file, index, manifest)) {
                assertNotNull(validated.findClass(IndexCacheTest.class.getName()));
            }
            assertEquals(manifest, IndexCache.read(file));
            try (ClassIndex restored = ClassIndex.fromFile(file.toString())) {
                assertNotNull(restored.findClass(IndexCacheTest.class.getName()));
            }
            byte[] previous = Files.readAllBytes(file);
            var missing = new RuntimeSnapshotBytecodeSource.Source(7, this.home.resolve("missing.jar"), logical, source.module());
            assertThrows(IOException.class, () -> IndexCache.write(file, index,
                    new IndexCache.Manifest("invalid", List.of(missing))));
            assertArrayEquals(previous, Files.readAllBytes(file));
            try (var children = Files.list(this.home)) {
                assertEquals(2, children.count());
            }
            Files.delete(archive);
            assertEquals(manifest, IndexCache.read(file));
            assertThrows(IOException.class, () -> IndexCache.requireSources(manifest));
        }
    }

    @Test
    void anIndexOfAnotherJavaRuntimeIsNotUsed() throws Exception {
        var java = new RuntimeInventory.RuntimeModule("java-runtime", "Java Runtime", RuntimeInventory.ModuleKind.JAVA_RUNTIME);
        IndexCache.requireSourcePaths(new IndexCache.Manifest("test",
                List.of(new RuntimeSnapshotBytecodeSource.Source(0, IndexCache.javaHome(), "jrt:/", java))));
        var other = new IndexCache.Manifest("test",
                List.of(new RuntimeSnapshotBytecodeSource.Source(0, this.home, "jrt:/", java)));
        assertThrows(IOException.class, () -> IndexCache.requireSourcePaths(other));
    }

    @Test
    void anIndexWrittenOnAnotherBuildOfTheJavaRuntimeIsNotUsed() throws Exception {
        Path file = this.home.resolve("index.jindex");
        var java = new RuntimeInventory.RuntimeModule("java-runtime", "Java Runtime", RuntimeInventory.ModuleKind.JAVA_RUNTIME);
        var manifest = new IndexCache.Manifest("test",
                List.of(new RuntimeSnapshotBytecodeSource.Source(0, IndexCache.javaHome(), "jrt:/", java)));
        try (var input = IndexCacheTest.class.getResourceAsStream("IndexCacheTest.class");
             ClassIndex index = ClassIndex.fromBytes(List.of(input.readAllBytes()));
             ClassIndex ignored = IndexCache.write(file, index, manifest)) {
            assertEquals(manifest, IndexCache.read(file));
        }
        String version = System.getProperty("java.runtime.version");
        // The same path after an update in place.
        System.setProperty("java.runtime.version", version + "-updated");
        try {
            assertThrows(IOException.class, () -> IndexCache.read(file));
        } finally {
            System.setProperty("java.runtime.version", version);
        }
    }

    @Test
    void aJavaHomeWrittenWithRedundantPartsIsTheSameRuntime() throws Exception {
        var java = new RuntimeInventory.RuntimeModule("java-runtime", "Java Runtime", RuntimeInventory.ModuleKind.JAVA_RUNTIME);
        String property = System.getProperty("java.home");
        System.setProperty("java.home", Path.of(property, ".").toString());
        try {
            IndexCache.requireSourcePaths(new IndexCache.Manifest("test",
                    List.of(new RuntimeSnapshotBytecodeSource.Source(0, IndexCache.javaHome(), "jrt:/", java))));
        } finally {
            System.setProperty("java.home", property);
        }
    }
}
