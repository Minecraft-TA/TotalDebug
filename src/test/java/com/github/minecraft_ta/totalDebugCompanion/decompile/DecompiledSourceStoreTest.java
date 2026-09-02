package com.github.minecraft_ta.totalDebugCompanion.decompile;

import com.github.minecraft_ta.totalDebugCompanion.source.SourceLineMap;
import com.github.minecraft_ta.totalDebugCompanion.source.SourceVariableNames;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DecompiledSourceStoreTest {
    @Test
    void storesSourceAndBothLineMappingDirections(@TempDir Path directory) throws Exception {
        SourceLineMap lineMap = SourceLineMap.fromOriginalToDisplayed(new int[]{20, 8, 10, 4, 21, 8});
        DecompiledSourceStore store = DecompiledSourceStore.open(directory, "runtime", "format");

        SourceVariableNames variableNames = SourceVariableNames.forMethod(
                "run",
                "()V",
                java.util.Map.of("p_1_", "level")
        );
        Path source = store.write("sample.Target", "class Target {}", lineMap, variableNames);

        assertEquals(source, store.read("sample.Target").path());
        assertEquals("class Target {}", Files.readString(source));
        assertEquals(java.util.List.of(".lock", "manifest.json", "sample.Target.debug", "sample.Target.java"), fileNames(source.getParent()));
        assertEquals(java.util.List.of("sample.Target"), store.cachedClasses());
        SourceLineMap restored = DecompiledSourceStore.open(directory, "runtime", "format")
                .read("sample.Target").debug().lines();
        assertArrayEquals(new int[]{10, 4, 20, 8, 21, 8}, restored.originalToDisplayed());
        assertArrayEquals(new int[]{4, 10, 8, 20, 8, 21}, restored.displayedToOriginal());
        assertEquals(variableNames, DecompiledSourceStore.open(directory, "runtime", "format")
                .read("sample.Target").debug().names());
    }

    @Test
    void javaFileWithoutTheCurrentLineMapIsNotACacheEntry(@TempDir Path directory) throws Exception {
        DecompiledSourceStore store = DecompiledSourceStore.open(directory, "runtime", "format");
        Path javaOnly = store.directory().resolve("sample.Target.java");
        Files.writeString(javaOnly, "old source without debugger mapping");

        assertNull(store.read("sample.Target"));
    }


    @Test
    void replacesTheCurrentRuntimeAndRejectsLateWrites(@TempDir Path directory) throws Exception {
        var old = DecompiledSourceStore.open(directory, "first", "format");
        Path target = old.write("sample.Target", "first", SourceLineMap.empty(), SourceVariableNames.empty());
        old.write("sample.Removed", "removed", SourceLineMap.empty(), SourceVariableNames.empty());
        var current = DecompiledSourceStore.open(directory, "second", "format");
        assertNull(current.read("sample.Target"));
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> old.write("sample.Late", "stale", SourceLineMap.empty(), SourceVariableNames.empty()));
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () -> old.read("sample.Target"));
        assertEquals(target, current.write("sample.Target", "second", SourceLineMap.empty(), SourceVariableNames.empty()));
        assertEquals("second", current.read("sample.Target").source());
        assertEquals(java.util.List.of(".lock", "manifest.json", "sample.Target.debug", "sample.Target.java"),
                fileNames(current.directory()));
    }

    @Test
    void disambiguatesCaseReservedAndLongNamesWithoutHashDirectories(@TempDir Path directory) throws Exception {
        var store = DecompiledSourceStore.open(directory, "runtime", "format");
        for (String name : java.util.List.of("sample.Target", "sample.target", "CON", "long.".repeat(60) + "Target")) {
            Path file = store.write(name, name, SourceLineMap.empty(), SourceVariableNames.empty());
            assertEquals(store.directory(), file.getParent());
            org.junit.jupiter.api.Assertions.assertTrue(file.getFileName().toString().length() < 140);
            assertEquals(name, store.read(name).source());
        }
        assertEquals("sample.target-2.java", store.read("sample.target").path().getFileName().toString());
        assertEquals("_CON.java", store.read("CON").path().getFileName().toString());
    }

    @Test
    void detectsMismatchedSourceAndDebugFiles(@TempDir Path directory) throws Exception {
        var store = DecompiledSourceStore.open(directory, "runtime", "format");
        Path file = store.write("sample.Target", "complete", SourceLineMap.empty(), SourceVariableNames.empty());
        Files.writeString(file, "different");
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () -> store.read("sample.Target"));
    }


    @Test
    void failedPairPublicationIsInvisibleAndItsFilesAreReclaimed(@TempDir Path directory) throws Exception {
        var store = DecompiledSourceStore.open(directory, "runtime", "format");
        store.write("sample.Complete", "complete", SourceLineMap.empty(), SourceVariableNames.empty());
        String invalidHeader = "x".repeat(70_000);
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> store.write(invalidHeader, "incomplete", SourceLineMap.empty(), SourceVariableNames.empty()));
        assertNull(store.read(invalidHeader));
        var reopened = DecompiledSourceStore.open(directory, "runtime", "format");
        assertEquals("complete", reopened.read("sample.Complete").source());
        assertEquals(java.util.List.of(".lock", "manifest.json", "sample.Complete.debug", "sample.Complete.java"),
                fileNames(reopened.directory()));
    }

    @Test
    void unknownDirectoriesAreNotMigratedOrCleaned(@TempDir Path directory) throws Exception {
        Path unknown = Files.createDirectories(directory.resolve("cache/decompiled/unknown"));
        Files.writeString(unknown.resolve("keep"), "untouched");
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> DecompiledSourceStore.open(directory, "runtime", "format"));
        assertEquals("untouched", Files.readString(unknown.resolve("keep")));
    }

    private static java.util.List<String> fileNames(Path directory) throws Exception {
        try (var files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }
}
