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

        SourceVariableNames variableNames = SourceVariableNames.of(java.util.Map.of("p_1_", "level"));
        Path source = store.write("sample.Target", "class Target {}", lineMap, variableNames);

        assertEquals(source, store.find("sample.Target"));
        assertEquals("class Target {}", Files.readString(source));
        SourceLineMap restored = DecompiledSourceStore.open(directory, "runtime", "format")
                .readLineMap("sample.Target");
        assertArrayEquals(new int[]{10, 4, 20, 8, 21, 8}, restored.originalToDisplayed());
        assertArrayEquals(new int[]{4, 10, 8, 20, 8, 21}, restored.displayedToOriginal());
        assertEquals(variableNames, DecompiledSourceStore.open(directory, "runtime", "format")
                .readVariableNames("sample.Target"));
    }

    @Test
    void javaFileWithoutTheCurrentLineMapIsNotACacheEntry(@TempDir Path directory) throws Exception {
        DecompiledSourceStore store = DecompiledSourceStore.open(directory, "runtime", "format");
        Path javaOnly = directory.resolve("decompiled-files/sample.Target.java");
        Files.writeString(javaOnly, "old source without debugger mapping");

        assertNull(store.find("sample.Target"));
    }
}
