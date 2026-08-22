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
            RuntimeClassIndex.prepareJarSource(
                    virtualSourceJar,
                    this.temporaryDirectory,
                    7,
                    inputs
            );

            assertEquals(2, inputs.size());
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
