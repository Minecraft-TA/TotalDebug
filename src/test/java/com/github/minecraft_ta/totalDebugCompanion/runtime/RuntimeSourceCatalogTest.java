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
    void exposesSortedModulesAndTheirDistinctSourceIds() {
        RuntimeInventory.RuntimeModule zeta = new RuntimeInventory.RuntimeModule("zeta", "Zeta Mod");
        RuntimeInventory.RuntimeModule alpha = new RuntimeInventory.RuntimeModule("alpha", "Alpha Mod");
        RuntimeSourceCatalog catalog = new RuntimeSourceCatalog(List.of(
                source(4, zeta, "zeta-a.jar"),
                source(2, alpha, "alpha.jar"),
                source(3, zeta, "zeta-b.jar")
        ));

        assertEquals(List.of(alpha, zeta), catalog.modules());
        assertArrayEquals(new int[]{2, 3, 4}, catalog.sourceIdsForModules(Set.of("zeta", "alpha")));
        assertArrayEquals(new int[0], catalog.sourceIdsForModules(Set.of()));
        assertThrows(IllegalArgumentException.class, () -> catalog.sourceIdsForModules(Set.of("missing")));
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
