package com.github.minecraft_ta.totaldebug.client.companion;

import net.neoforged.jarjar.nio.layzip.LayeredZipFileSystemProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeClassIndexTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void classDirectoryFingerprintUsesContentRatherThanTimestamps() throws Exception {
        Path classFile = this.temporaryDirectory.resolve("example/Fixture.class");
        Files.createDirectories(classFile.getParent());
        Files.writeString(classFile, "first");
        String initialFingerprint = fingerprint(this.temporaryDirectory);

        Files.setLastModifiedTime(classFile, FileTime.from(Instant.now().plusSeconds(30)));
        assertEquals(initialFingerprint, fingerprint(this.temporaryDirectory));

        Files.writeString(classFile, "second");
        assertNotEquals(initialFingerprint, fingerprint(this.temporaryDirectory));
    }

    @Test
    void materializesNeoForgeJarInJarSources() throws Exception {
        byte[] nestedJarBytes = jarBytes("example/Fixture.class", new byte[]{1, 2, 3});
        byte[] sourceJarBytes = jarBytes("META-INF/jarjar/dependency.jar", nestedJarBytes);
        Path outerJar = this.temporaryDirectory.resolve("outer.jar");
        writeJar(outerJar, "META-INF/jarjar/source.jar", sourceJarBytes);

        var provider = new LayeredZipFileSystemProvider();
        try (FileSystem fileSystem = provider.newFileSystem(outerJar)) {
            Path virtualSourceJar = fileSystem.getPath("/META-INF/jarjar/source.jar");

            List<Path> inputs = new ArrayList<>();
            List<Path> referenceSources = new ArrayList<>();
            Path materialized = this.temporaryDirectory.resolve("materialized");
            Path published = this.temporaryDirectory.resolve("published");
            RuntimeClassIndex.prepareJarSource(
                    virtualSourceJar,
                    materialized,
                    published,
                    7,
                    inputs,
                    referenceSources
            );

            assertEquals(2, inputs.size());
            assertEquals(List.of(
                    published.resolve("source-7.jar"),
                    published.resolve("nested-7/nested-0.jar")
            ), referenceSources);
            assertSame(FileSystems.getDefault(), inputs.getFirst().getFileSystem());
            assertTrue(Files.isRegularFile(inputs.getFirst()));
            assertArrayEquals(sourceJarBytes, Files.readAllBytes(inputs.getFirst()));
            assertArrayEquals(nestedJarBytes, Files.readAllBytes(inputs.getLast()));
        }
    }

    @Test
    void readsClassDirectoriesAsDirectClassFiles() throws Exception {
        Path firstClass = this.temporaryDirectory.resolve("example/First.class");
        Path secondClass = this.temporaryDirectory.resolve("example/Second.class");
        Files.createDirectories(firstClass.getParent());
        Files.write(firstClass, new byte[]{1, 2, 3});
        Files.write(secondClass, new byte[]{4, 5, 6});
        Files.writeString(this.temporaryDirectory.resolve("example/ignored.txt"), "ignored");

        List<byte[]> classes = new ArrayList<>();
        assertEquals(2, RuntimeClassIndex.readClassDirectory(this.temporaryDirectory, classes));
        assertEquals(2, classes.size());
        assertArrayEquals(new byte[]{1, 2, 3}, classes.getFirst());
        assertArrayEquals(new byte[]{4, 5, 6}, classes.getLast());
    }

    @Test
    void publishesAnImmutableRuntimeSourceCache() throws Exception {
        Path published = this.temporaryDirectory.resolve("runtime-sources/signature");
        Path staged = stageRuntimeSources("first-stage", published, "source.jar", new byte[]{1, 2, 3});

        RuntimeClassIndex.publishRuntimeSources(staged, published);

        assertEquals(
                List.of(published.resolve("source.jar")),
                RuntimeSourceManifest.read(published.resolve("sources.txt"))
        );
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(published.resolve("source.jar")));

        Path identical = stageRuntimeSources("identical-stage", published, "source.jar", new byte[]{1, 2, 3});
        RuntimeClassIndex.publishRuntimeSources(identical, published);
        assertTrue(Files.notExists(identical));

        Path contentConflict = stageRuntimeSources("content-conflict", published, "source.jar", new byte[]{9});
        assertThrows(
                java.io.IOException.class,
                () -> RuntimeClassIndex.publishRuntimeSources(contentConflict, published)
        );

        Path conflicting = stageRuntimeSources("conflicting-stage", published, "other.jar", new byte[]{4});
        assertThrows(
                java.io.IOException.class,
                () -> RuntimeClassIndex.publishRuntimeSources(conflicting, published)
        );
    }

    @Test
    void publishesAnImmutableSignatureKeyedIndex() throws Exception {
        Path indexes = Files.createDirectories(this.temporaryDirectory.resolve("indexes"));
        Path staged = Files.createDirectory(indexes.resolve(".staged"));
        Path stagedIndex = Files.write(staged.resolve("index"), new byte[]{1, 2, 3});
        Files.writeString(staged.resolve("index.meta"), "signature");
        Path published = indexes.resolve("signature");

        RuntimeClassIndex.publishIndex(stagedIndex, published, "signature");

        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(published.resolve("index")));
        assertEquals("signature", Files.readString(published.resolve("index.meta")));

        Path identical = Files.createDirectory(indexes.resolve(".identical"));
        Path identicalIndex = Files.write(identical.resolve("index"), new byte[]{1, 2, 3});
        Files.writeString(identical.resolve("index.meta"), "signature");
        RuntimeClassIndex.publishIndex(identicalIndex, published, "signature");

        Path conflict = Files.createDirectory(indexes.resolve(".conflict"));
        Path conflictingIndex = Files.write(conflict.resolve("index"), new byte[]{9});
        Files.writeString(conflict.resolve("index.meta"), "other");
        assertThrows(
                java.io.IOException.class,
                () -> RuntimeClassIndex.publishIndex(conflictingIndex, published, "signature")
        );
    }

    private Path stageRuntimeSources(String name, Path published, String fileName, byte[] content) throws Exception {
        Path staged = Files.createDirectories(this.temporaryDirectory.resolve(name));
        Files.write(staged.resolve(fileName), content);
        RuntimeSourceManifest.write(staged.resolve("sources.txt"), List.of(published.resolve(fileName)));
        return staged;
    }

    private static String fingerprint(Path directory) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        RuntimeClassIndex.updateClassDirectoryDigest(digest, directory);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static byte[] jarBytes(String entryName, byte[] content) throws Exception {
        Path jar = Files.createTempFile("runtime-index-nested-", ".jar");
        try {
            writeJar(jar, entryName, content);
            return Files.readAllBytes(jar);
        } finally {
            Files.deleteIfExists(jar);
        }
    }

    private static void writeJar(Path jar, String entryName, byte[] content) throws Exception {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new ZipEntry(entryName));
            output.write(content);
            output.closeEntry();
        }
    }
}
