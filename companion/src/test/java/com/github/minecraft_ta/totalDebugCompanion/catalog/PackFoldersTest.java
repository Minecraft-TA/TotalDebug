package com.github.minecraft_ta.totalDebugCompanion.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackFoldersTest {
    @TempDir Path directory;

    @Test
    void packsAreFoldersWithPackMcmetaOrZipFiles() throws Exception {
        Path folder = Files.createDirectories(this.directory.resolve("resourcepacks"));
        Files.createDirectories(folder.resolve("Faithful"));
        Files.writeString(folder.resolve("Faithful/pack.mcmeta"), "{\"pack\":{\"pack_format\":34,\"description\":\"Faithful 32x\"}}");
        Files.createDirectories(folder.resolve("backup"));
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(folder.resolve("Old.zip")))) {
            output.putNextEntry(new ZipEntry("pack.mcmeta"));
            output.write("{\"pack\":{\"pack_format\":15,\"description\":\"Old\"}}".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        Files.createDirectories(folder.resolve("Broken"));
        Files.writeString(folder.resolve("Broken/pack.mcmeta"), "{\"pack\":{\"description\":\"No format\"}}");
        Files.createDirectories(folder.resolve("Nameless"));
        Files.writeString(folder.resolve("Nameless/pack.mcmeta"), "{\"pack\":{\"pack_format\":34,\"description\":null}}");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(folder.resolve("Nested.zip")))) {
            output.putNextEntry(new ZipEntry("Nested/pack.mcmeta"));
            output.closeEntry();
        }
        Files.writeString(folder.resolve("readme.txt"), "");

        assertEquals(List.of("file/Faithful", "file/Old.zip"), List.copyOf(PackFolders.list(folder).keySet()),
                "a folder or zip without readable pack metadata at its root and other files are no packs, as for the game");
        assertEquals("Old.zip", PackFolders.title(folder.resolve("Old.zip")), "the game titles a zip pack with its file name");
        assertTrue(PackFolders.list(this.directory.resolve("missing")).isEmpty());
    }

    @Test
    void packMcmetaIsReadFromFoldersAndZipsWithItsDescriptionAsPlainText() throws Exception {
        Path folder = Files.createDirectories(this.directory.resolve("Tweaks"));
        Files.writeString(folder.resolve("pack.mcmeta"),
                "{\"pack\":{\"pack_format\":48,\"description\":[{\"text\":\"Better \"},{\"text\":\"loot\",\"color\":\"gold\"}]}}");
        assertEquals(new PackFolders.Meta("Better loot", 48), PackFolders.meta(folder).orElseThrow(),
                "a description may be a text component, shown as its text");

        Path zip = this.directory.resolve("Structures.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
            output.putNextEntry(new ZipEntry("pack.mcmeta"));
            output.write("{\"pack\":{\"pack_format\":48,\"description\":{\"translate\":\"pack.structures\"}}}".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        assertEquals(new PackFolders.Meta("pack.structures", 48), PackFolders.meta(zip).orElseThrow());
        assertTrue(PackFolders.meta(Files.createDirectories(this.directory.resolve("empty"))).isEmpty());
    }

    @Test
    void aPackIsNamedWithItsWorldWhenItIsADatapack() {
        assertEquals("MyPack datapack of World", PackFolders.label(this.directory.resolve("saves/World/datapacks/MyPack")));
        assertEquals("TotalDebug resource pack", PackFolders.label(this.directory.resolve("resourcepacks/TotalDebug")));
    }
}
