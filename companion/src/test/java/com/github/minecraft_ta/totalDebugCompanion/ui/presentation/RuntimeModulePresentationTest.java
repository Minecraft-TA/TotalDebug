package com.github.minecraft_ta.totalDebugCompanion.ui.presentation;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.minecraft_ta.totaldebug.storage.RuntimeInventory;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeModulePresentationTest {
    @Test
    void separatesTheDisplayNameFromItsDisambiguatingId() {
        var presentation = RuntimeModulePresentation.of(
                new RuntimeInventory.RuntimeModule(
                        "example",
                        "Example Mod",
                        RuntimeInventory.ModuleKind.MOD
                )
        );

        assertEquals(new PrimarySecondaryText("Example Mod", "example"), presentation.text());
        assertEquals("Example Mod (example)", presentation.label());
        assertTrue(presentation.tooltip().contains("Module example"), presentation.tooltip());
    }

    @Test
    void sourcePresentationRetainsProvenance() {
        var source = new RuntimeSnapshotBytecodeSource.Source(
                4,
                Path.of("example.jar"),
                "file:///runtime/example.jar",
                new RuntimeInventory.RuntimeModule(
                        "example",
                        "Example Mod",
                        RuntimeInventory.ModuleKind.MOD
                )
        );

        var presentation = RuntimeModulePresentation.of(source);

        assertTrue(presentation.tooltip().contains("example.jar"), presentation.tooltip());
        assertFalse(presentation.tooltip().contains("file:"), presentation.tooltip());
    }

    @Test
    void nestedJarsNameTheJarThatContainsThem() {
        assertEquals("fabric-api-base-0.4.42.jar in iris neoforge.jar", RuntimeModulePresentation.location(
                "jij:/C:/mods/iris%20neoforge.jar%23209!/META-INF/jarjar/fabric-api-base-0.4.42.jar"));
        assertEquals("example.jar", RuntimeModulePresentation.location("file:///runtime/example.jar"));
    }

    @Test
    void summarizesSeveralModulesInStableOrder() {
        String summary = RuntimeModulePresentation.compactSummary(List.of(
                new RuntimeInventory.RuntimeModule("gamma", "Gamma", RuntimeInventory.ModuleKind.MOD),
                new RuntimeInventory.RuntimeModule("alpha", "Alpha", RuntimeInventory.ModuleKind.MOD),
                new RuntimeInventory.RuntimeModule("beta", "Beta", RuntimeInventory.ModuleKind.MOD)
        ));

        assertEquals("Alpha, Beta +1", summary);
    }

    @Test
    void disambiguatesOnlyDuplicateDisplayNamesInSummaries() {
        String summary = RuntimeModulePresentation.compactSummary(List.of(
                new RuntimeInventory.RuntimeModule("first", "Library", RuntimeInventory.ModuleKind.LIBRARY),
                new RuntimeInventory.RuntimeModule("second", "Library", RuntimeInventory.ModuleKind.LIBRARY)
        ));

        assertEquals("Library (first), Library (second)", summary);
    }
}
