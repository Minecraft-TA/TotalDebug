package com.github.minecraft_ta.totalDebugCompanion.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ModFilesTest {
    @TempDir Path directory;

    @Test
    void readsEntriesOfJarsNestedInOtherJars() throws Exception {
        byte[] inner = jar("assets/fabric-api-base/icon.png", "logo");
        Path outer = this.directory.resolve("iris neoforge+mc.jar");
        Files.write(outer, jar("META-INF/jarjar/fabric-api-base.jar", inner));
        String path = outer.toUri().getRawPath();
        URI nested = URI.create("jij:" + path + "%23209!/META-INF/jarjar/fabric-api-base.jar");

        assertArrayEquals("logo".getBytes(StandardCharsets.UTF_8),
                ModFiles.read(nested, "assets/fabric-api-base/icon.png", 1024).orElseThrow());
        assertEquals(Optional.empty(), ModFiles.read(nested, "missing.png", 1024));
    }

    @Test
    void readsPlainJarsAndFolders() throws Exception {
        Path jar = this.directory.resolve("mod.jar");
        Files.write(jar, jar("logo.png", "jar logo"));
        Path folder = Files.createDirectories(this.directory.resolve("devmod"));
        Files.writeString(folder.resolve("logo.png"), "folder logo");

        assertEquals("jar logo", new String(ModFiles.read(jar.toUri(), "logo.png", 1024).orElseThrow(), StandardCharsets.UTF_8));
        assertEquals("folder logo", new String(ModFiles.read(folder.toUri(), "logo.png", 1024).orElseThrow(), StandardCharsets.UTF_8));
        assertEquals(Optional.empty(), ModFiles.read(folder.toUri(), "../mod.jar", 1024));
    }

    @Test
    void splitsNestingMarkers() {
        assertEquals(List.of("/C:/mods/a.jar", "META-INF/jarjar/b.jar", "META-INF/jarjar/c.jar"),
                ModFiles.nesting("jij:/C:/mods/a.jar%2312!/META-INF/jarjar/b.jar%233!/META-INF/jarjar/c.jar"));
    }

    private static byte[] jar(String entry, String content) throws IOException {
        return jar(entry, content.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] jar(String entry, byte[] content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(entry));
            zip.write(content);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
