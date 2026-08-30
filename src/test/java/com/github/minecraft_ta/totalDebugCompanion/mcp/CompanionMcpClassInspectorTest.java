package com.github.minecraft_ta.totalDebugCompanion.mcp;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.minecraft_ta.totalDebugCompanion.decompile.DecompiledSource;
import com.github.minecraft_ta.totalDebugCompanion.source.SourceLineMap;
import com.github.minecraft_ta.totalDebugCompanion.source.SourceVariableNames;
import com.github.tth05.jindex.ClassIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionMcpClassInspectorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void returnsOnlyTheRequestedClassEvidence() throws Exception {
        byte[] bytes = classBytes(Fixture.class);
        DecompiledSource source = new DecompiledSource(
                this.temporaryDirectory.resolve("Fixture.java"),
                Fixture.class.getName(),
                "final class Fixture { int answer() { return 42; } }",
                SourceLineMap.empty(),
                SourceVariableNames.empty(),
                null
        );
        CompanionMcpClassInspector.RuntimeClassAccess runtimeClasses = new CompanionMcpClassInspector.RuntimeClassAccess() {
            @Override
            public DecompiledSource source(String binaryName) {
                return source;
            }

            @Override
            public byte[] bytecode(String binaryName) {
                return bytes;
            }

            @Override
            public RuntimeSnapshotBytecodeSource.ClassOrigin origin(String binaryName) {
                return null;
            }
        };

        try (ClassIndex index = ClassIndex.fromBytes(List.of(bytes))) {
            CompanionMcpClassInspector inspector = new CompanionMcpClassInspector(runtimeClasses, () -> index);

            assertEquals(Map.of("source", source.contents()), inspector.source(Fixture.class.getName()));

            Map<String, Object> bytecode = inspector.bytecode(Fixture.class.getName());
            assertEquals(java.util.Set.of("bytecode"), bytecode.keySet());
            assertTrue(((String) bytecode.get("bytecode")).contains("answer"));
            assertTrue(((String) bytecode.get("bytecode")).contains("BIPUSH 42"));

            Map<String, Object> members = inspector.members(Fixture.class.getName());
            assertEquals(java.util.Set.of("fields", "methods"), members.keySet());
            List<?> fields = (List<?>) members.get("fields");
            assertTrue(fields.stream().map(Map.class::cast).anyMatch(field ->
                    field.get("name").equals("CONSTANT")
                            && field.get("descriptor").equals("I")
                            && ((List<?>) field.get("modifiers")).containsAll(List.of("static", "final"))
            ));
            List<?> methods = (List<?>) members.get("methods");
            assertTrue(methods.stream().map(Map.class::cast).anyMatch(method ->
                    method.get("name").equals("answer")
                            && method.get("descriptor").equals("()I")
            ));
            assertFalse(members.toString().contains("access_flags"));
        }
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        String resourceName = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IOException("Missing class resource " + resourceName);
            }
            return input.readAllBytes();
        }
    }

    private static final class Fixture {
        private static final int CONSTANT = 42;

        int answer() {
            return CONSTANT;
        }
    }
}
