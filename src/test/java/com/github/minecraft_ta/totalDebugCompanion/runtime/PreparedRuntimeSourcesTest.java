package com.github.minecraft_ta.totalDebugCompanion.runtime;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PreparedRuntimeSourcesTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsTheOriginalLogicalSource() throws Exception {
        Path archive = Files.createFile(this.temporaryDirectory.resolve("prepared.jar"));
        String logical = "file:///original/mod.jar!/META-INF/jarjar/dependency.jar";
        Path file = this.temporaryDirectory.resolve(PreparedRuntimeSources.FILE_NAME);

        PreparedRuntimeSources.write(
                file,
                List.of(new RuntimeSnapshotBytecodeSource.Source(
                        7,
                        archive,
                        logical,
                        new RuntimeInventory.RuntimeModule("example", "Example Mod")
                ))
        );

        RuntimeSnapshotBytecodeSource.Source restored = PreparedRuntimeSources.read(file).getFirst();
        assertEquals(logical, restored.logicalUri());
        assertEquals("example", restored.module().id());
        assertEquals("Example Mod", restored.module().displayName());
    }
}
