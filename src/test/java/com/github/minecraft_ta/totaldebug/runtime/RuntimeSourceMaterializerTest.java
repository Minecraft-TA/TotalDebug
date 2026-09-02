package com.github.minecraft_ta.totaldebug.runtime;

import net.neoforged.jarjar.nio.pathfs.PathFileSystemProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeSourceMaterializerTest {
    @TempDir
    Path directory;

    @Test
    void copiesJarInJarArchivesWithoutRequestingUnsupportedFileAttributes() throws Exception {
        Path archive = this.directory.resolve("nested.jar");
        try (var output = new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new ZipEntry("example/Dependency.class"));
            output.write(new byte[]{1, 2, 3});
            output.closeEntry();
        }
        try (var filesystem = new PathFileSystemProvider().newFileSystem(archive)) {
            Path virtualArchive = filesystem.getRoot();
            var prepared = RuntimeSourceMaterializer.prepare(
                    List.of(new RuntimeSourceInventory.Source(virtualArchive, "nested")),
                    this.directory.resolve("cache")
            );
            assertArrayEquals(Files.readAllBytes(archive), Files.readAllBytes(prepared.paths().getFirst()));
            assertEquals(virtualArchive.toUri().toASCIIString(), prepared.sources().getFirst().logicalUri());
        }
    }

    @Test
    void replacesOneSourceSetAndRejectsReadersFromThePreviousRuntime() throws Exception {
        Path physical = Files.createDirectory(this.directory.resolve("classes"));
        Path cache = this.directory.resolve("runtime/sources");
        Path firstJar = this.directory.resolve("first.jar");
        Path secondJar = this.directory.resolve("second.jar");
        try (var firstFs = FileSystems.newFileSystem(firstJar, Map.of("create", "true"));
             var secondFs = FileSystems.newFileSystem(secondJar, Map.of("create", "true"))) {
            Path first = firstFs.getPath("/");
            Path second = secondFs.getPath("/");
            Files.write(first.resolve("First.class"), new byte[]{1});
            Files.write(second.resolve("Second.class"), new byte[]{2});
            var old = RuntimeSourceMaterializer.prepare(List.of(
                    new RuntimeSourceInventory.Source(first, "first"),
                    new RuntimeSourceInventory.Source(second, "second")), cache);
            Files.writeString(cache.getParent().resolve("inventory.json"), "old inventory");

            Files.write(first.resolve("First.class"), new byte[]{3, 4});
            var current = RuntimeSourceMaterializer.prepare(List.of(
                    new RuntimeSourceInventory.Source(first, "first")), cache);
            assertEquals(old.paths().getFirst(), current.paths().getFirst());
            assertTrue(!Files.exists(cache.resolve("second.jar")));
            assertTrue(!Files.exists(cache.getParent().resolve("inventory.json")));
            assertThrows(IOException.class, () -> old.withCurrentSources(() -> "stale"));
            assertEquals("current", current.withCurrentSources(() -> "current"));
            try (ZipFile zip = new ZipFile(current.paths().getFirst().toFile())) {
                assertArrayEquals(new byte[]{3, 4}, zip.getInputStream(zip.getEntry("First.class")).readAllBytes());
            }

            RuntimeSourceMaterializer.prepare(List.of(new RuntimeSourceInventory.Source(physical, "physical")), cache);
            try (var entries = Files.list(cache)) {
                assertEquals(List.of("manifest.json"), entries.map(path -> path.getFileName().toString()).toList());
            }
        }
    }

    @Test
    void rejectsUnknownDirectoriesWithoutMigratingOrDeletingThem() throws Exception {
        Path physical = Files.createDirectory(this.directory.resolve("classes"));
        Path cache = Files.createDirectories(this.directory.resolve("runtime/sources"));
        Path unknown = Files.createDirectory(cache.resolve("old-layout"));
        Files.writeString(unknown.resolve("keep"), "untouched");
        assertThrows(IOException.class, () -> RuntimeSourceMaterializer.prepare(
                List.of(new RuntimeSourceInventory.Source(physical, "physical")), cache));
        assertEquals("untouched", Files.readString(unknown.resolve("keep")));
    }

    @Test
    void reusesPhysicalSourcesAndMaterializesVirtualRootsOnce() throws Exception {
        Path physical = Files.createDirectory(this.directory.resolve("classes"));
        Path archive = this.directory.resolve("dependency.jar");
        Path cache = this.directory.resolve("cache");
        try (var filesystem = FileSystems.newFileSystem(archive, Map.of("create", "true"))) {
            Path root = filesystem.getPath("/");
            Files.createDirectories(root.resolve("example"));
            Files.write(root.resolve("example/Dependency.class"), new byte[]{1, 2, 3});
            Files.createDirectories(root.resolve("META-INF"));
            Files.writeString(root.resolve("META-INF/MANIFEST.MF"), "Manifest-Version: 1.0\r\nMulti-Release: true\r\n\r\n");
            Files.writeString(root.resolve("unrelated.txt"), "not compiler input");
            var originals = List.of(
                    new RuntimeSourceInventory.Source(physical, "main"),
                    new RuntimeSourceInventory.Source(root, "dependency")
            );

            PreparedRuntimeSources first = RuntimeSourceMaterializer.prepare(originals, cache);
            assertEquals(physical, first.paths().getFirst());
            var materialized = first.sources().get(1);
            assertEquals(root.toUri().toASCIIString(), materialized.logicalUri());
            assertEquals("dependency", materialized.original().moduleName());
            assertSame(FileSystems.getDefault(), materialized.path().getFileSystem());
            try (ZipFile zip = new ZipFile(materialized.path().toFile())) {
                try (var input = zip.getInputStream(zip.getEntry("example/Dependency.class"))) {
                    assertArrayEquals(new byte[]{1, 2, 3}, input.readAllBytes());
                }
                assertNotNull(zip.getEntry("META-INF/MANIFEST.MF"));
                assertNull(zip.getEntry("unrelated.txt"));
            }
            var modified = Files.getLastModifiedTime(materialized.path());
            PreparedRuntimeSources second = RuntimeSourceMaterializer.prepare(originals, cache);
            assertEquals(first, second);
            assertEquals(modified, Files.getLastModifiedTime(materialized.path()));
            try (var entries = Files.list(cache)) {
                assertEquals(List.of("dependency.jar", "manifest.json"), entries.map(path -> path.getFileName().toString()).sorted().toList());
            }

            Files.delete(materialized.path());
            IOException failure = assertThrows(IOException.class,
                    () -> RuntimeSourceMaterializer.prepare(originals, cache));
            assertTrue(failure.getMessage().contains("Prepared runtime source does not exist"));
        }
    }
}
