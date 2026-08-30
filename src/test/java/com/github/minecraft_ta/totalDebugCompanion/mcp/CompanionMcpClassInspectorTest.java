package com.github.minecraft_ta.totalDebugCompanion.mcp;

import com.github.minecraft_ta.totalDebugCompanion.decompile.DecompiledSource;
import com.github.minecraft_ta.totalDebugCompanion.source.SourceLineMap;
import com.github.minecraft_ta.totalDebugCompanion.source.SourceVariableNames;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompanionMcpClassInspectorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void returnsOnlyCompleteDecompiledSource() {
        DecompiledSource source = new DecompiledSource(
                this.temporaryDirectory.resolve("Fixture.java"),
                Fixture.class.getName(),
                "final class Fixture { int answer() { return 42; } }",
                SourceLineMap.empty(),
                SourceVariableNames.empty(),
                null
        );
        CompanionMcpClassInspector inspector = new CompanionMcpClassInspector(binaryName -> source);

        assertEquals(Map.of("source", source.contents()), inspector.source(Fixture.class.getName()));
    }

    private static final class Fixture {
    }
}
