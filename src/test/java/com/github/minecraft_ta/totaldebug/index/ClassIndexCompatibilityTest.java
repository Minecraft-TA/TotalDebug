package com.github.minecraft_ta.totaldebug.index;

import com.github.tth05.jindex.ClassIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class ClassIndexCompatibilityTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void indexesAndReloadsJava21ClassFiles() throws IOException {
        byte[] classBytes;
        String resourceName = "/" + ClassIndexCompatibilityTest.class.getName().replace('.', '/') + ".class";
        try (InputStream input = ClassIndexCompatibilityTest.class.getResourceAsStream(resourceName)) {
            assertNotNull(input, resourceName);
            classBytes = input.readAllBytes();
        }

        Path fixtureJar = this.temporaryDirectory.resolve("java21-fixture.jar");
        try (OutputStream output = Files.newOutputStream(fixtureJar);
             ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(resourceName.substring(1)));
            zip.write(classBytes);
            zip.closeEntry();
        }

        Path indexFile = this.temporaryDirectory.resolve("index");
        ClassIndex created = ClassIndex.fromJars(List.of(fixtureJar.toString()));
        try {
            assertNotNull(created.findClass(
                    ClassIndexCompatibilityTest.class.getPackageName(),
                    ClassIndexCompatibilityTest.class.getSimpleName()
            ));
            created.saveToFile(indexFile.toString());
        } finally {
            created.destroy();
        }

        ClassIndex loaded = ClassIndex.fromFile(indexFile.toString());
        try {
            assertNotNull(loaded.findClass(
                    ClassIndexCompatibilityTest.class.getPackageName(),
                    ClassIndexCompatibilityTest.class.getSimpleName()
            ));
        } finally {
            loaded.destroy();
        }
    }
}
