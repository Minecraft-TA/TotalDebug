package com.github.minecraft_ta.totalDebugCompanion.runtime;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeSourceCatalogTest {
    @Test
    void exposesPriorityOrderedModulesAndTheirDistinctSourceIds() {
        RuntimeInventory.RuntimeModule zeta = new RuntimeInventory.RuntimeModule(
                "zeta",
                "Zeta Mod",
                RuntimeInventory.ModuleKind.MOD
        );
        RuntimeInventory.RuntimeModule alpha = new RuntimeInventory.RuntimeModule(
                "alpha",
                "Alpha Mod",
                RuntimeInventory.ModuleKind.MOD
        );
        RuntimeInventory.RuntimeModule minecraft = new RuntimeInventory.RuntimeModule(
                "minecraft",
                "Minecraft",
                RuntimeInventory.ModuleKind.PLATFORM
        );
        RuntimeInventory.RuntimeModule library = new RuntimeInventory.RuntimeModule(
                "asm",
                "ASM",
                RuntimeInventory.ModuleKind.LIBRARY
        );
        RuntimeInventory.RuntimeModule javaRuntime = new RuntimeInventory.RuntimeModule(
                "java-runtime",
                "Java Runtime",
                RuntimeInventory.ModuleKind.JAVA_RUNTIME
        );
        RuntimeSourceCatalog catalog = new RuntimeSourceCatalog(List.of(
                source(4, zeta, "zeta-a.jar"),
                source(2, alpha, "alpha.jar"),
                source(3, zeta, "zeta-b.jar"),
                source(0, library, "asm.jar"),
                source(1, minecraft, "minecraft.jar"),
                source(5, javaRuntime, "java-runtime")
        ));

        assertEquals(List.of(minecraft, alpha, zeta, library, javaRuntime), catalog.modules());
        assertEquals(List.of(library), catalog.modules(RuntimeInventory.ModuleKind.LIBRARY));
        assertEquals(Path.of("alpha.jar").toAbsolutePath().normalize(), catalog.sourceFor(2).path());
        assertEquals(List.of(catalog.sourceFor(3), catalog.sourceFor(4)), catalog.sourcesForModule("zeta"));
        assertArrayEquals(new int[]{2, 3, 4}, catalog.sourceIdsForModules(Set.of("zeta", "alpha")));
        assertArrayEquals(new int[0], catalog.sourceIdsForModules(Set.of()));
        assertThrows(IllegalArgumentException.class, () -> catalog.sourceIdsForModules(Set.of("missing")));
        assertThrows(IllegalArgumentException.class, () -> catalog.sourceFor(99));
    }

    private static RuntimeSnapshotBytecodeSource.Source source(
            int sourceId,
            RuntimeInventory.RuntimeModule module,
            String name
    ) {
        return new RuntimeSnapshotBytecodeSource.Source(
                sourceId,
                Path.of(name),
                "file:///" + name,
                module
        );
    }
}
