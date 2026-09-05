package com.github.minecraft_ta.totaldebug.runtime;

import net.neoforged.jarjar.nio.pathfs.PathFileSystemProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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
    void reusesAProvenUnionArchiveAndKeepsTheFirstModuleOwnerAcrossProviderInstances() throws Exception {
        Path archive = createArchive("union.jar", Map.of("Example.class", new byte[]{1},
                "META-INF/versions/21/Example.class", new byte[]{2},
                "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nMulti-Release: true\r\n\r\n".getBytes(),
                "resource.txt", new byte[]{3}));
        var provider = new cpw.mods.niofs.union.UnionFileSystemProvider();
        String previousId = null;
        String previousUri = null;
        for (int run = 0; run < 2; run++) {
            try (var union = provider.newFileSystem(null, archive)) {
                var original = new RuntimeSourceInventory.Source(union.getRoot(), "loader-module");
                var prepared = RuntimeSourceMaterializer.prepare(List.of(original,
                        new RuntimeSourceInventory.Source(archive, "classpath-module")), directory.resolve("union/sources"));
                assertEquals(List.of(archive), prepared.paths());
                assertEquals(original, prepared.sources().getFirst().original());
                if (previousId != null) {
                    assertEquals(previousId, prepared.id());
                    assertTrue(!previousUri.equals(original.path().toUri().toString()));
                }
                previousId = prepared.id();
                previousUri = original.path().toUri().toString();
            }
        }
    }

    @Test
    void materializesFilteredAndOverlaidUnionViewsWithoutExposingHiddenClasses() throws Exception {
        Path archive = createArchive("base.jar", Map.of("Example.class", new byte[]{1}, "Hidden.class", new byte[]{2}));
        Path overlay = createArchive("overlay.jar", Map.of("Example.class", new byte[]{3}));
        var provider = new cpw.mods.niofs.union.UnionFileSystemProvider();
        try (var filtered = provider.newFileSystem((name, base) -> !name.equals("Hidden.class"), archive)) {
            var first = RuntimeSourceMaterializer.prepare(List.of(new RuntimeSourceInventory.Source(filtered.getRoot(), "filtered")),
                    directory.resolve("filtered/sources"));
            assertTrue(!archive.equals(first.paths().getFirst()));
            try (var zip = new ZipFile(first.paths().getFirst().toFile())) {
                assertNull(zip.getEntry("Hidden.class"));
                assertArrayEquals(new byte[]{1}, zip.getInputStream(zip.getEntry("Example.class")).readAllBytes());
            }
        }
        try (var merged = provider.newFileSystem(null, archive, overlay)) {
            var prepared = RuntimeSourceMaterializer.prepare(List.of(new RuntimeSourceInventory.Source(merged.getRoot(), "merged")),
                    directory.resolve("merged/sources"));
            try (var zip = new ZipFile(prepared.paths().getFirst().toFile())) {
                assertArrayEquals(Files.readAllBytes(merged.getRoot().resolve("Example.class")),
                        zip.getInputStream(zip.getEntry("Example.class")).readAllBytes());
                assertNotNull(zip.getEntry("Hidden.class"));
            }
        }
    }

    @Test
    void contentChangesWithIdenticalSizeAndTimestampStillInvalidateTheSource() throws Exception {
        Path cache = directory.resolve("content/sources");
        try (var fs = FileSystems.newFileSystem(directory.resolve("content.zip"), Map.of("create", "true"))) {
            Path file = Files.write(fs.getPath("/Example.class"), new byte[]{1});
            var sources = List.of(new RuntimeSourceInventory.Source(fs.getPath("/"), "content"));
            var before = RuntimeSourceMaterializer.prepare(sources, cache);
            var stamp = Files.getLastModifiedTime(file);
            Files.write(file, new byte[]{2});
            Files.setLastModifiedTime(file, stamp);
            var after = RuntimeSourceMaterializer.prepare(sources, cache);
            assertTrue(!before.id().equals(after.id()));
            try (var zip = new ZipFile(after.paths().getFirst().toFile())) {
                assertArrayEquals(new byte[]{2}, zip.getInputStream(zip.getEntry("Example.class")).readAllBytes());
            }
        }
    }

    private Path createArchive(String name, Map<String, byte[]> entries) throws IOException {
        Path file = directory.resolve(name);
        try (var output = new ZipOutputStream(Files.newOutputStream(file))) {
            for (var entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return file;
    }

    @Test
    void copiesProvenNestedUnionArchivesByteForByteAcrossProviderInstances() throws Exception {
        Path inner = createArchive("inner.jar", Map.of("Example.class", new byte[]{1}, "resource.txt", new byte[]{2}));
        byte[] original = Files.readAllBytes(inner);
        Path outer = createArchive("outer.jar", Map.of("META-INF/jarjar/inner.jar", original));
        var provider = new cpw.mods.niofs.union.UnionFileSystemProvider();
        String previous = null;
        for (int run = 0; run < 2; run++) {
            try (var container = provider.newFileSystem(null, outer);
                 var nested = provider.newFileSystem(null, container.getPath("/META-INF/jarjar/inner.jar"))) {
                var prepared = RuntimeSourceMaterializer.prepare(
                        List.of(new RuntimeSourceInventory.Source(nested.getRoot(), "inner")), directory.resolve("nested/sources"));
                assertArrayEquals(original, Files.readAllBytes(prepared.paths().getFirst()));
                if (previous != null) {
                    assertEquals(previous, prepared.id());
                }
                previous = prepared.id();
            }
        }
    }

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
            assertEquals(first, RuntimeSourceMaterializer.prepare(originals, cache));
            assertTrue(Files.isRegularFile(materialized.path()));
        }
    }

    enum CacheDamage { DELETED_CACHE, INVALID_MANIFEST, UNSUPPORTED_FORMAT, TRUNCATED_JAR }

    @Test
    void changingOneVirtualSourceLeavesTheOtherPreparedFileUntouched() throws Exception {
        Path cache = this.directory.resolve("selective/sources");
        try (var firstFs = FileSystems.newFileSystem(this.directory.resolve("one.zip"), Map.of("create", "true"));
             var secondFs = FileSystems.newFileSystem(this.directory.resolve("two.zip"), Map.of("create", "true"))) {
            Path first = firstFs.getPath("/");
            Path second = secondFs.getPath("/");
            Files.write(first.resolve("One.class"), new byte[]{1});
            Files.write(second.resolve("Two.class"), new byte[]{2});
            var inputs = List.of(new RuntimeSourceInventory.Source(first, "one"),
                    new RuntimeSourceInventory.Source(second, "two"));
            var before = RuntimeSourceMaterializer.prepare(inputs, cache);
            Path untouched = before.paths().get(1);
            var stamp = java.nio.file.attribute.FileTime.fromMillis(1_234_000);
            Files.setLastModifiedTime(untouched, stamp);
            Files.write(first.resolve("One.class"), new byte[]{3, 4});
            var after = RuntimeSourceMaterializer.prepare(inputs, cache);
            assertEquals(stamp, Files.getLastModifiedTime(untouched));
            assertTrue(!before.id().equals(after.id()));
            Files.delete(after.paths().getFirst());
            RuntimeSourceMaterializer.prepare(inputs, cache);
            assertEquals(stamp, Files.getLastModifiedTime(untouched));
        }
    }

    @Test
    void missingOriginalSourceStillFailsWithoutReplacingTheCache() throws Exception {
        Path original = Files.write(this.directory.resolve("original.jar"), new byte[]{1});
        Path cache = this.directory.resolve("runtime/sources");
        var inputs = List.of(new RuntimeSourceInventory.Source(original, "example"));
        RuntimeSourceMaterializer.prepare(inputs, cache);
        byte[] manifest = Files.readAllBytes(cache.resolve("manifest.json"));
        Files.delete(original);

        IOException failure = assertThrows(IOException.class, () -> RuntimeSourceMaterializer.prepare(inputs, cache));
        assertTrue(failure.getMessage().contains("Runtime class source does not exist:"));
        assertArrayEquals(manifest, Files.readAllBytes(cache.resolve("manifest.json")));
    }

    @ParameterizedTest
    @EnumSource(CacheDamage.class)
    void recreatesUnavailableGeneratedSourcesFromTheLoadedRuntime(CacheDamage damage) throws Exception {
        Path archive = this.directory.resolve("original.jar");
        Path cache = this.directory.resolve("runtime/sources");
        try (var filesystem = FileSystems.newFileSystem(archive, Map.of("create", "true"))) {
            Path root = filesystem.getPath("/");
            Files.write(root.resolve("Example.class"), new byte[]{1, 2, 3});
            var originals = List.of(new RuntimeSourceInventory.Source(root, "example"));
            var first = RuntimeSourceMaterializer.prepare(originals, cache);
            Path copy = first.paths().getFirst();
            Path manifest = cache.resolve("manifest.json");
            switch (damage) {
                case DELETED_CACHE -> {
                    Files.delete(copy);
                    Files.delete(manifest);
                    Files.delete(cache);
                }
                case INVALID_MANIFEST -> Files.writeString(manifest, "broken");
                case UNSUPPORTED_FORMAT -> {
                    var json = com.github.minecraft_ta.totaldebug.storage.JsonFiles.read(manifest);
                    json.addProperty("format", 99);
                    com.github.minecraft_ta.totaldebug.storage.JsonFiles.write(manifest, json);
                }
                case TRUNCATED_JAR -> Files.writeString(copy, "truncated");
            }

            var current = RuntimeSourceMaterializer.prepare(originals, cache);
            assertEquals(first, current);
            assertEquals("ready", current.withCurrentSources(() -> "ready"));
            try (ZipFile zip = new ZipFile(copy.toFile())) {
                assertArrayEquals(new byte[]{1, 2, 3}, zip.getInputStream(zip.getEntry("Example.class")).readAllBytes());
            }
        }
    }
}
