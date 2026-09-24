package com.github.minecraft_ta.totalDebugCompanion.decompile;

import com.github.minecraft_ta.totalDebugCompanion.source.SourceLineMap;
import com.github.minecraft_ta.totalDebugCompanion.source.SourceDocument;
import com.github.minecraft_ta.totalDebugCompanion.source.SourceVariableNames;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecompiledSourceStoreTest {
    @Test
    void storesSourceAndBothLineMappingDirections(@TempDir Path directory) throws Exception {
        SourceLineMap lineMap = SourceLineMap.fromOriginalToDisplayed(new int[]{20, 8, 10, 4, 21, 8});
        DecompiledSourceStore store = DecompiledSourceStore.open(directory, "runtime", "format");

        SourceVariableNames variableNames = SourceVariableNames.forMethod(
                "run",
                "()V",
                Map.of("p_1_", "level")
        );
        Path source = store.write(new SourceDocument("sample.Target", "class Target {}", lineMap, variableNames, List.of()));

        assertEquals(source, store.read("sample.Target").path());
        assertEquals("class Target {}", Files.readString(source));
        assertEquals(List.of(".lock", "manifest.json", "sample.Target.debug", "sample.Target.java"), fileNames(source.getParent()));
        assertEquals(List.of("sample.Target"), store.cachedClasses());
        SourceLineMap restored = DecompiledSourceStore.open(directory, "runtime", "format")
                .read("sample.Target").document().lineMap();
        assertArrayEquals(new int[]{10, 4, 20, 8, 21, 8}, restored.originalToDisplayed());
        assertArrayEquals(new int[]{4, 10, 8, 20, 8, 21}, restored.displayedToOriginal());
        assertEquals(variableNames, DecompiledSourceStore.open(directory, "runtime", "format")
                .read("sample.Target").document().variableNames());
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
        Path target = old.write(new SourceDocument("sample.Target", "first", SourceLineMap.empty(), SourceVariableNames.empty(), List.of()));
        old.write(new SourceDocument("sample.Removed", "removed", SourceLineMap.empty(), SourceVariableNames.empty(), List.of()));
        var current = DecompiledSourceStore.open(directory, "second", "format");
        assertNull(current.read("sample.Target"));
        assertThrows(IOException.class,
                () -> old.write(new SourceDocument("sample.Late", "stale", SourceLineMap.empty(), SourceVariableNames.empty(), List.of())));
        assertThrows(IOException.class, () -> old.read("sample.Target"));
        assertEquals(target, current.write(new SourceDocument("sample.Target", "second", SourceLineMap.empty(), SourceVariableNames.empty(), List.of())));
        assertEquals("second", current.read("sample.Target").document().contents());
        assertEquals(List.of(".lock", "manifest.json", "sample.Target.debug", "sample.Target.java"),
                fileNames(current.directory()));
    }

    @Test
    void disambiguatesCaseReservedAndLongNamesWithoutHashDirectories(@TempDir Path directory) throws Exception {
        var store = DecompiledSourceStore.open(directory, "runtime", "format");
        for (String name : List.of("sample.Target", "sample.target", "CON", "long.".repeat(60) + "Target")) {
            Path file = store.write(new SourceDocument(name, name, SourceLineMap.empty(), SourceVariableNames.empty(), List.of()));
            assertEquals(store.directory(), file.getParent());
            assertTrue(file.getFileName().toString().length() < 140);
            assertEquals(name, store.read(name).document().contents());
        }
        assertEquals("sample.target-2.java", store.read("sample.target").path().getFileName().toString());
        assertEquals("_CON.java", store.read("CON").path().getFileName().toString());
    }

    @Test
    void mismatchedSourceAndDebugFilesAreUnlistedForRegeneration(@TempDir Path directory) throws Exception {
        var store = DecompiledSourceStore.open(directory, "runtime", "format");
        Path file = store.write(document("sample.Target", "complete"));
        Files.writeString(file, "different");

        assertNull(store.read("sample.Target"));
        assertEquals(List.of(), store.cachedClasses());
        assertEquals(List.of(".lock", "manifest.json"), fileNames(store.directory()));
        assertEquals(file, store.write(document("sample.Target", "regenerated")));
        assertEquals("regenerated", store.read("sample.Target").document().contents());
    }

    @ParameterizedTest
    @ValueSource(strings = {".java", ".debug"})
    void missingPairFileIsRegeneratedAfterReopening(String damagedSuffix, @TempDir Path directory) throws Exception {
        var store = DecompiledSourceStore.open(directory, "runtime", "format");
        store.write(document("sample.Target", "complete"));
        store.write(document("sample.Other", "other"));
        Files.delete(store.directory().resolve("sample.Target" + damagedSuffix));

        var reopened = DecompiledSourceStore.open(directory, "runtime", "format");
        assertNull(reopened.read("sample.Target"));
        assertEquals(List.of("sample.Other"), reopened.cachedClasses());
        assertEquals("other", reopened.read("sample.Other").document().contents());
        reopened.write(document("sample.Target", "regenerated"));
        assertEquals("regenerated", reopened.read("sample.Target").document().contents());
    }

    @Test
    void writeReplacesADamagedListedPair(@TempDir Path directory) throws Exception {
        var store = DecompiledSourceStore.open(directory, "runtime", "format");
        Path file = store.write(document("sample.Target", "complete"));
        Files.write(store.directory().resolve("sample.Target.debug"), new byte[] {1, 2, 3});

        assertEquals(file, store.write(document("sample.Target", "regenerated")));
        assertEquals("regenerated", store.read("sample.Target").document().contents());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not json",
            "{\"format\": 2, \"id\": \"x\", \"classes\": {}}",
            "{\"format\": 1, \"classes\": {}}",
            "{\"format\": \"one\"}"
    })
    void damagedGeneratedManifestIsReplacedWhenOpening(String manifest, @TempDir Path directory) throws Exception {
        var store = DecompiledSourceStore.open(directory, "runtime", "format");
        store.write(document("sample.Target", "complete"));
        Files.writeString(store.directory().resolve("manifest.json"), manifest);

        var reopened = DecompiledSourceStore.open(directory, "runtime", "format");
        assertEquals(List.of(), reopened.cachedClasses());
        assertEquals(List.of(".lock", "manifest.json"), fileNames(reopened.directory()));
        reopened.write(document("sample.Target", "regenerated"));
        assertEquals("regenerated", reopened.read("sample.Target").document().contents());
    }

    private static SourceDocument document(String binaryName, String contents) {
        return new SourceDocument(binaryName, contents, SourceLineMap.empty(), SourceVariableNames.empty(), List.of());
    }


    @Test
    void failedPairPublicationIsInvisibleAndItsFilesAreReclaimed(@TempDir Path directory) throws Exception {
        var store = DecompiledSourceStore.open(directory, "runtime", "format");
        store.write(new SourceDocument("sample.Complete", "complete", SourceLineMap.empty(), SourceVariableNames.empty(), List.of()));
        String invalidHeader = "x".repeat(70_000);
        assertThrows(IOException.class,
                () -> store.write(new SourceDocument(invalidHeader, "incomplete", SourceLineMap.empty(), SourceVariableNames.empty(), List.of())));
        assertNull(store.read(invalidHeader));
        var reopened = DecompiledSourceStore.open(directory, "runtime", "format");
        assertEquals("complete", reopened.read("sample.Complete").document().contents());
        assertEquals(List.of(".lock", "manifest.json", "sample.Complete.debug", "sample.Complete.java"),
                fileNames(reopened.directory()));
    }

    @Test
    void unknownDirectoriesAreNotMigratedOrCleaned(@TempDir Path directory) throws Exception {
        Path unknown = Files.createDirectories(directory.resolve("cache/decompiled/unknown"));
        Files.writeString(unknown.resolve("keep"), "untouched");
        assertThrows(IOException.class,
                () -> DecompiledSourceStore.open(directory, "runtime", "format"));
        assertEquals("untouched", Files.readString(unknown.resolve("keep")));
    }

    private static List<String> fileNames(Path directory) throws Exception {
        try (var files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }
}
