package com.github.minecraft_ta.totalDebugCompanion.bytecode;

import com.github.minecraft_ta.totaldebug.storage.RuntimeInventory;
import com.github.tth05.jindex.ClassIndex;
import com.github.tth05.jindex.IndexSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeTestSources.bytecodeSource;
import static com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeTestSources.librarySource;

class RuntimeSnapshotBytecodeSourceTest {
    @TempDir
    Path temporaryDirectory;


    @Test
    void rejectsReplacedRuntimeFilesAndNeverUsesAClosedNativeIndex() throws Exception {
        byte[] expected = classBytes(ArchiveFixture.class);
        Path selected = jar("selected.jar", resourceName(ArchiveFixture.class), expected);
        Path inventory = this.temporaryDirectory.resolve("inventory.json");
        Files.writeString(inventory, "{\"id\":\"first\"}");
        ClassIndex index = ClassIndex.fromSources(List.of(IndexSource.archive(0, selected.toString())));
        try {
            var source = RuntimeSnapshotBytecodeSource.fromRuntime(List.of(librarySource(0, selected)),
                    index, inventory, "first");
            assertArrayEquals(expected, source.findClassBytes(ArchiveFixture.class.getName()));
            Files.writeString(inventory, "{\"id\":\"second\"}");
            assertThrows(IOException.class, () -> source.findClassBytes(ArchiveFixture.class.getName()));
            source.close();
            index.close();
            assertThrows(IllegalStateException.class, () -> source.findClassBytes(ArchiveFixture.class.getName()));
            assertThrows(IllegalStateException.class, () -> source.hasClass(ArchiveFixture.class.getName()));
            assertThrows(IllegalStateException.class, () -> source.findClassOrigin(ArchiveFixture.class.getName()));
        } finally {
            index.close();
        }
    }


    @Test
    void closingDoesNotWaitForAnUnrelatedCacheWriter() throws Exception {
        byte[] expected = classBytes(ArchiveFixture.class);
        Path selected = jar("selected.jar", resourceName(ArchiveFixture.class), expected);
        Path inventory = this.temporaryDirectory.resolve("inventory.json");
        Files.writeString(inventory, "{\"id\":\"first\"}");
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (ClassIndex index = ClassIndex.fromSources(List.of(IndexSource.archive(0, selected.toString())))) {
            var source = RuntimeSnapshotBytecodeSource.fromRuntime(List.of(librarySource(0, selected)), index, inventory, "first");
            var writer = java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    com.github.minecraft_ta.totaldebug.storage.CacheFiles.locked(this.temporaryDirectory, () -> {
                        locked.countDown();
                        assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
                        return null;
                    });
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            });
            assertTrue(locked.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var reader = java.util.concurrent.CompletableFuture.runAsync(() ->
                    assertThrows(IllegalStateException.class, () -> source.findClassBytes(ArchiveFixture.class.getName())));
            try {
                java.util.concurrent.CompletableFuture.runAsync(source::close).get(1, java.util.concurrent.TimeUnit.SECONDS);
                index.close();
            } finally {
                release.countDown();
            }
            reader.get(5, java.util.concurrent.TimeUnit.SECONDS);
            writer.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    void readsTheIndexedArchiveWithoutScanningOtherSources() throws Exception {
        byte[] expected = classBytes(ArchiveFixture.class);
        String resourceName = resourceName(ArchiveFixture.class);
        Path decoy = jar("decoy.jar", resourceName, classBytes(DirectoryFixture.class));
        Path selected = jar("selected.jar", resourceName, expected);

        try (ClassIndex index = ClassIndex.fromSources(List.of(
                IndexSource.archive(0, decoy.toString()),
                IndexSource.archive(1, selected.toString())
        ))) {
            RuntimeSnapshotBytecodeSource source = bytecodeSource(List.of(decoy, selected), index);

            assertArrayEquals(expected, source.findClassBytes(ArchiveFixture.class.getName()));
        }
    }

    @Test
    void reportsTheLogicalOriginRecordedForTheIndexedClass() throws Exception {
        byte[] expected = classBytes(ArchiveFixture.class);
        String resourceName = resourceName(ArchiveFixture.class);
        Path selected = jar("selected-origin.jar", resourceName, expected);
        String logical = "file:///mods/original.jar";

        try (ClassIndex index = ClassIndex.fromSources(List.of(IndexSource.archive(4, selected.toString())))) {
            RuntimeSnapshotBytecodeSource source = RuntimeSnapshotBytecodeSource.fromIndexedSources(
                    List.of(new RuntimeSnapshotBytecodeSource.Source(
                            4,
                            selected,
                            logical,
                            new RuntimeInventory.RuntimeModule(
                                    "example",
                                    "Example Mod",
                                    RuntimeInventory.ModuleKind.MOD
                            )
                    )),
                    index
            );

            RuntimeSnapshotBytecodeSource.ClassOrigin origin = source.findClassOrigin(
                    ArchiveFixture.class.getName()
            );

            assertEquals(logical, origin.logicalSource());
            assertEquals(resourceName, origin.resourceName());
            assertEquals("Example Mod", origin.module().displayName());
        }
    }

    @Test
    void readsDirectClassDirectories() throws Exception {
        byte[] expected = classBytes(DirectoryFixture.class);
        String resourceName = resourceName(DirectoryFixture.class);
        Path classes = this.temporaryDirectory.resolve("classes");
        Path classFile = classes.resolve(resourceName.replace('/', java.io.File.separatorChar));
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, expected);

        try (ClassIndex index = ClassIndex.fromSources(List.of(IndexSource.classFile(0, expected)))) {
            RuntimeSnapshotBytecodeSource source = bytecodeSource(List.of(classes), index);

            assertArrayEquals(expected, source.findClassBytes(resourceName));
        }
    }

    @Test
    void readsAnExplicitJdkSourceFromTheRuntimeImage() throws Exception {
        byte[] expected = classBytes(String.class);
        Path javaHome = Path.of(System.getProperty("java.home"));
        try (ClassIndex index = ClassIndex.fromSources(List.of(IndexSource.classFile(5, expected)))) {
            RuntimeSnapshotBytecodeSource source = RuntimeSnapshotBytecodeSource.fromIndexedSources(
                    List.of(new RuntimeSnapshotBytecodeSource.Source(
                            5,
                            javaHome,
                            "jrt:/",
                            new RuntimeInventory.RuntimeModule(
                                    "java-runtime",
                                    "Java Runtime",
                                    RuntimeInventory.ModuleKind.JAVA_RUNTIME
                            )
                    )),
                    index
            );

            assertArrayEquals(expected, source.findClassBytes(String.class.getName()));
            assertEquals("Java Runtime", source.findClassOrigin(String.class.getName()).module().displayName());
        }
    }

    @Test
    void usesTheCurrentRuntimeViewOfMultiReleaseArchives() throws Exception {
        Path archive = multiReleaseJar();
        byte[] indexFixture = classBytes(ArchiveFixture.class);

        try (ClassIndex index = ClassIndex.fromSources(List.of(IndexSource.classFile(0, indexFixture)))) {
            RuntimeSnapshotBytecodeSource source = RuntimeSnapshotBytecodeSource.fromIndexedSources(
                    List.of(librarySource(0, archive)),
                    index
            );

            assertArrayEquals(new byte[]{21}, source.findClassBytes(ArchiveFixture.class.getName()));
        }
    }

    @Test
    void indexMissDoesNotTouchUnrelatedArchives() throws Exception {
        byte[] expected = classBytes(ArchiveFixture.class);
        Path corruptArchive = this.temporaryDirectory.resolve("unrelated.jar");
        Files.writeString(corruptArchive, "not a zip");

        try (ClassIndex index = ClassIndex.fromSources(List.of(IndexSource.classFile(7, expected)))) {
            RuntimeSnapshotBytecodeSource source = RuntimeSnapshotBytecodeSource.fromIndexedSources(
                    List.of(librarySource(7, corruptArchive)),
                    index
            );

            assertNull(source.findClassBytes("missing.Type"));
        }
    }

    @Test
    void indexedSourceMismatchDoesNotFallBackToAnotherArchive() throws Exception {
        byte[] expected = classBytes(ArchiveFixture.class);
        String resourceName = resourceName(ArchiveFixture.class);
        Path selected = jar("selected-missing-entry.jar", "unrelated/Type.class", expected);
        Path fallback = jar("fallback.jar", resourceName, expected);

        try (ClassIndex index = ClassIndex.fromSources(List.of(IndexSource.classFile(4, expected)))) {
            RuntimeSnapshotBytecodeSource source = RuntimeSnapshotBytecodeSource.fromIndexedSources(
                    List.of(
                            librarySource(4, selected),
                            librarySource(5, fallback)
                    ),
                    index
            );

            IOException failure = assertThrows(
                    IOException.class,
                    () -> source.findClassBytes(ArchiveFixture.class.getName())
            );
            assertTrue(failure.getMessage().contains("is missing"), failure.getMessage());
        }
    }

    @Test
    void unknownIndexedSourceFailsInsteadOfSearchingAnotherArchive() throws Exception {
        byte[] expected = classBytes(ArchiveFixture.class);
        Path unrelated = jar("unrelated-owner.jar", resourceName(ArchiveFixture.class), expected);

        try (ClassIndex index = ClassIndex.fromSources(List.of(IndexSource.classFile(4, expected)))) {
            RuntimeSnapshotBytecodeSource source = RuntimeSnapshotBytecodeSource.fromIndexedSources(
                    List.of(librarySource(5, unrelated)),
                    index
            );

            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> source.findClassBytes(ArchiveFixture.class.getName())
            );
            assertEquals(
                    "Runtime index maps " + ArchiveFixture.class.getName() + " to unknown source id 4",
                    failure.getMessage()
            );
        }
    }

    @Test
    void checksClassExistenceFromTheIndexWithoutReadingArchives() throws Exception {
        byte[] expected = classBytes(ArchiveFixture.class);
        Path corruptArchive = this.temporaryDirectory.resolve("indexed-but-unreadable.jar");
        Files.writeString(corruptArchive, "not a zip");

        try (ClassIndex index = ClassIndex.fromSources(List.of(IndexSource.classFile(9, expected)))) {
            RuntimeSnapshotBytecodeSource source = RuntimeSnapshotBytecodeSource.fromIndexedSources(
                    List.of(librarySource(9, corruptArchive)),
                    index
            );

            assertTrue(source.hasClass(ArchiveFixture.class.getName()));
            assertFalse(source.hasClass("missing.Type"));
        }
    }

    @Test
    void rejectsPathTraversalNames() throws Exception {
        byte[] fixture = classBytes(ArchiveFixture.class);
        Path archive = jar("fixture.jar", resourceName(ArchiveFixture.class), fixture);
        try (ClassIndex index = ClassIndex.fromSources(List.of(IndexSource.archive(0, archive.toString())))) {
            RuntimeSnapshotBytecodeSource source = bytecodeSource(List.of(archive), index);

            assertThrows(IllegalArgumentException.class, () -> source.findClassBytes("../secret.Type"));
            assertThrows(IllegalArgumentException.class, () -> source.findClassBytes("example//Type"));
        }
    }

    private Path jar(String fileName, String entryName, byte[] bytes) throws IOException {
        Path jar = this.temporaryDirectory.resolve(fileName);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry(entryName));
            output.write(bytes);
            output.closeEntry();
        }
        return jar;
    }

    private Path multiReleaseJar() throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Multi-Release", "true");
        Path jar = this.temporaryDirectory.resolve("multi-release.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            String resourceName = resourceName(ArchiveFixture.class);
            output.putNextEntry(new JarEntry(resourceName));
            output.write(new byte[]{8});
            output.closeEntry();
            output.putNextEntry(new JarEntry("META-INF/versions/21/" + resourceName));
            output.write(new byte[]{21});
            output.closeEntry();
        }
        return jar;
    }

    private static String resourceName(Class<?> type) {
        return type.getName().replace('.', '/') + ".class";
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        ClassLoader loader = type.getClassLoader() == null
                ? ClassLoader.getPlatformClassLoader()
                : type.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(resourceName(type))) {
            if (input == null) {
                throw new IOException("Missing test class bytes for " + type.getName());
            }
            return input.readAllBytes();
        }
    }

    private static final class ArchiveFixture {
    }

    private static final class DirectoryFixture {
    }
}
