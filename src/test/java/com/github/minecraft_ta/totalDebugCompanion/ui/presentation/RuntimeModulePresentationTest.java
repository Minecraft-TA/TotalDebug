package com.github.minecraft_ta.totalDebugCompanion.ui.presentation;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeInventory;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeModulePresentationTest {
    @Test
    void separatesTheDisplayNameFromItsDisambiguatingId() {
        var presentation = RuntimeModulePresentation.of(
                new RuntimeInventory.RuntimeModule("example", "Example Mod")
        );

        assertEquals(new PrimarySecondaryText("Example Mod", "example"), presentation.text());
        assertEquals("Example Mod (example)", presentation.label());
        assertTrue(presentation.tooltip().contains("module id: example"));
    }

    @Test
    void sourcePresentationRetainsProvenance() {
        var source = new RuntimeSnapshotBytecodeSource.Source(
                4,
                Path.of("example.jar"),
                "file:///runtime/example.jar",
                new RuntimeInventory.RuntimeModule("example", "Example Mod")
        );

        var presentation = RuntimeModulePresentation.of(source);

        assertTrue(presentation.tooltip().contains("file:///runtime/example.jar"));
    }

    @Test
    void summarizesSeveralModulesInStableOrder() {
        String summary = RuntimeModulePresentation.compactSummary(List.of(
                new RuntimeInventory.RuntimeModule("gamma", "Gamma"),
                new RuntimeInventory.RuntimeModule("alpha", "Alpha"),
                new RuntimeInventory.RuntimeModule("beta", "Beta")
        ));

        assertEquals("Alpha, Beta +1", summary);
    }

    @Test
    void disambiguatesOnlyDuplicateDisplayNamesInSummaries() {
        String summary = RuntimeModulePresentation.compactSummary(List.of(
                new RuntimeInventory.RuntimeModule("first", "Library"),
                new RuntimeInventory.RuntimeModule("second", "Library")
        ));

        assertEquals("Library (first), Library (second)", summary);
    }
}
