package com.github.minecraft_ta.totalDebugCompanion.bytecode;

import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeInventory;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeSnapshotBytecodeSourceTest {
    @TempDir
    Path temporaryDirectory;

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
            RuntimeSnapshotBytecodeSource source = new RuntimeSnapshotBytecodeSource(
                    List.of(decoy, selected),
                    index
            );

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
                            new RuntimeInventory.RuntimeModule("example", "Example Mod")
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
    void readsDirectClassDirectoriesAndJdkClasses() throws Exception {
        byte[] expected = classBytes(DirectoryFixture.class);
        String resourceName = resourceName(DirectoryFixture.class);
        Path classes = this.temporaryDirectory.resolve("classes");
        Path classFile = classes.resolve(resourceName.replace('/', java.io.File.separatorChar));
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, expected);

        try (ClassIndex index = ClassIndex.fromSources(List.of(IndexSource.classFile(0, expected)))) {
            RuntimeSnapshotBytecodeSource source = new RuntimeSnapshotBytecodeSource(List.of(classes), index);

            assertArrayEquals(expected, source.findClassBytes(resourceName));
            byte[] stringBytes = source.findClassBytes("java.lang.String");
            assertTrue(stringBytes.length > 4);
            assertEquals((byte) 0xCA, stringBytes[0]);
            assertEquals((byte) 0xFE, stringBytes[1]);
            assertEquals((byte) 0xBA, stringBytes[2]);
            assertEquals((byte) 0xBE, stringBytes[3]);
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
                            new RuntimeInventory.RuntimeModule("java-runtime", "Java Runtime")
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
            RuntimeSnapshotBytecodeSource source = new RuntimeSnapshotBytecodeSource(List.of(archive), index);

            assertArrayEquals(new byte[]{21}, source.findClassBytes("example.Versioned"));
        }
    }

    @Test
    void rejectsPathTraversalNames() throws Exception {
        byte[] fixture = classBytes(ArchiveFixture.class);
        Path archive = jar("fixture.jar", resourceName(ArchiveFixture.class), fixture);
        try (ClassIndex index = ClassIndex.fromSources(List.of(IndexSource.archive(0, archive.toString())))) {
            RuntimeSnapshotBytecodeSource source = new RuntimeSnapshotBytecodeSource(List.of(archive), index);

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
            output.putNextEntry(new JarEntry("example/Versioned.class"));
            output.write(new byte[]{8});
            output.closeEntry();
            output.putNextEntry(new JarEntry("META-INF/versions/21/example/Versioned.class"));
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
