package com.github.minecraft_ta.totalDebugCompanion.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EditorLocationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void mapsGradleClassOutputBackToItsModuleSourceTree() throws Exception {
        Path module = this.temporaryDirectory.resolve("TotalDebug");
        Path classes = Files.createDirectories(module.resolve("build/classes/java/main"));

        EditorLocation location = EditorLocation.forRuntimeClass(
                "com.github.example.Sample",
                classes.toUri().toASCIIString()
        );

        assertEquals(
                "TotalDebug  ›  src  ›  main  ›  java  ›  com  ›  github  ›  example  ›  Sample.java",
                location.breadcrumb()
        );
        assertEquals(
                classes.resolve("com/github/example/Sample.java").toString(),
                location.tooltip()
        );
    }

    @Test
    void startsArchiveLocationsAtTheOwningJar() throws Exception {
        Path archive = Files.createFile(this.temporaryDirectory.resolve("example-mod.jar"));

        EditorLocation location = EditorLocation.forArchiveEntry(archive, "META-INF/mods.toml");

        assertEquals("example-mod.jar  ›  META-INF  ›  mods.toml", location.breadcrumb());
        assertEquals(archive.toAbsolutePath().normalize() + "!/META-INF/mods.toml", location.tooltip());
    }

    @Test
    void retainsNestedArchiveProvenanceForRuntimeClasses() throws Exception {
        Path archive = Files.createFile(this.temporaryDirectory.resolve("outer.jar"));

        EditorLocation location = EditorLocation.forRuntimeClass(
                "example.Nested",
                archive.toUri().toASCIIString() + "!/META-INF/jarjar/nested.jar"
        );

        assertEquals(
                "outer.jar  ›  META-INF  ›  jarjar  ›  nested.jar  ›  example  ›  Nested.java",
                location.breadcrumb()
        );
    }

    @Test
    void usesTheRuntimeModuleDisplayNameInsteadOfThePreparedSourceName() throws Exception {
        Path preparedArchive = Files.createFile(this.temporaryDirectory.resolve("nested-0.jar"));

        EditorLocation location = EditorLocation.forRuntimeClass(
                "example.Nested",
                preparedArchive.toUri().toASCIIString() + "!/META-INF/jarjar/nested.jar",
                "Example Mod"
        );

        assertEquals(
                "Example Mod  ›  META-INF  ›  jarjar  ›  nested.jar  ›  example  ›  Nested.java",
                location.breadcrumb()
        );
    }
}
