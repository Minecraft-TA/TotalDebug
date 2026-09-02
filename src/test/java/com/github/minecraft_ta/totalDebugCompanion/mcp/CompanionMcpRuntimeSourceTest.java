package com.github.minecraft_ta.totalDebugCompanion.mcp;

import com.github.minecraft_ta.totalDebugCompanion.decompile.DecompiledSource;
import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import com.github.minecraft_ta.totalDebugCompanion.source.SourceLineMap;
import com.github.minecraft_ta.totalDebugCompanion.source.SourceVariableNames;
import com.github.tth05.jindex.ClassIndex;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionMcpRuntimeSourceTest {
    private static final String SOURCE = """
            package com.github.minecraft_ta.totalDebugCompanion.mcp;

            import java.util.List;
            import static java.util.Objects.requireNonNull;

            final class RuntimeSourceFixture {
                @Deprecated
                private final int answer = 42;

                String select(int value) {
                    return "int";
                }

                String select(long value) {
                    return "long";
                }

                record Entry(String name) {
                }

                enum Choice {
                    FIRST,
                    SECOND
                }
            }
            """;

    @TempDir
    Path temporaryDirectory;

    @BeforeAll
    static void initializeClassIndex() throws IOException {
        CompanionClassIndex.replace(ClassIndex.fromBytes(List.of(
                classBytes(Object.class),
                classBytes(String.class),
                classBytes(Record.class),
                classBytes(RuntimeSourceFixture.class),
                classBytes(RuntimeSourceFixture.Entry.class),
                classBytes(RuntimeSourceFixture.Choice.class)
        )));
    }

    @AfterAll
    static void closeClassIndex() {
        CompanionClassIndex.close();
    }

    @Test
    void returnsClassSourceWithoutEmbeddingPackageOrImports() {
        Map<String, Object> result = source().source(Map.of(
                "kind", "class",
                "binary_name", RuntimeSourceFixture.class.getName()
        ));

        assertEquals("com.github.minecraft_ta.totalDebugCompanion.mcp", result.get("package"));
        assertEquals(
                List.of("java.util.List", "static java.util.Objects.requireNonNull"),
                result.get("imports")
        );
        assertEquals(6, result.get("start_line"));
        String classSource = (String) result.get("source");
        assertTrue(classSource.startsWith("final class RuntimeSourceFixture"));
        assertFalse(classSource.contains("package "));
        assertFalse(classSource.contains("import "));
    }

    @Test
    void returnsOneExactOverloadedMethod() {
        Map<String, Object> result = source().source(Map.of(
                "kind", "method",
                "owner", RuntimeSourceFixture.class.getName(),
                "name", "select",
                "descriptor", "(I)Ljava/lang/String;"
        ));

        assertEquals(10, result.get("start_line"));
        assertEquals(
                """
                        String select(int value) {
                                return "int";
                            }""",
                result.get("source")
        );
        assertFalse(((String) result.get("source")).contains("long value"));
    }

    @Test
    void returnsFieldsAndNestedRecordComponents() {
        Map<String, Object> field = source().source(Map.of(
                "kind", "field",
                "owner", RuntimeSourceFixture.class.getName(),
                "name", "answer",
                "descriptor", "I"
        ));
        assertEquals("@Deprecated\n    private final int answer = 42;", field.get("source"));

        Map<String, Object> component = source().source(Map.of(
                "kind", "record_component",
                "owner", RuntimeSourceFixture.Entry.class.getName(),
                "name", "name",
                "descriptor", "Ljava/lang/String;"
        ));
        assertEquals("String name", component.get("source"));
    }

    @Test
    void returnsEnumConstantsAsFieldScopes() {
        Map<String, Object> result = source().source(Map.of(
                "kind", "field",
                "owner", RuntimeSourceFixture.Choice.class.getName(),
                "name", "FIRST",
                "descriptor", "L" + RuntimeSourceFixture.Choice.class.getName().replace('.', '/') + ";"
        ));

        assertEquals("FIRST", result.get("source"));
    }

    @Test
    void rejectsAValidButNonexistentOverload() {
        assertThrows(IllegalArgumentException.class, () -> source().source(Map.of(
                "kind", "method",
                "owner", RuntimeSourceFixture.class.getName(),
                "name", "select",
                "descriptor", "(D)Ljava/lang/String;"
        )));
    }

    private CompanionMcpRuntimeSource source() {
        DecompiledSource source = new DecompiledSource(
                this.temporaryDirectory.resolve("RuntimeSourceFixture.java"),
                RuntimeSourceFixture.class.getName(),
                SOURCE,
                SourceLineMap.empty(),
                SourceVariableNames.empty(),
                null
        );
        return new CompanionMcpRuntimeSource(binaryName -> source);
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        String resourceName = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream input = Objects.requireNonNull(type.getResourceAsStream(resourceName), resourceName)) {
            return input.readAllBytes();
        }
    }
}

final class RuntimeSourceFixture {
    @Deprecated
    private final int answer = 42;

    String select(int value) {
        return "int";
    }

    String select(long value) {
        return "long";
    }

    record Entry(String name) {
    }

    enum Choice {
        FIRST,
        SECOND
    }
}
