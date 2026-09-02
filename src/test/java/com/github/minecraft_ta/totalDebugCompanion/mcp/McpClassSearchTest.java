package com.github.minecraft_ta.totalDebugCompanion.mcp;

import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeInventory;
import com.github.tth05.jindex.ClassIndex;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpClassSearchTest {
    private static final RuntimeInventory.RuntimeModule FIXTURE_MODULE = new RuntimeInventory.RuntimeModule(
            "fixture-mod",
            "Fixture Mod",
            RuntimeInventory.ModuleKind.MOD
    );

    @Test
    void resolvesExactNamesAndSearchesCompleteBinaryNamesWithModuleOwnership() throws Exception {
        try (ClassIndex index = ClassIndex.fromBytes(List.of(
                classBytes(ExactFixture.class),
                classBytes(QualifiedSearchFixture.class)
        ))) {
            CompanionMcpSearchService search = search(index);
            Map<String, Object> exact = search.searchClasses(ExactFixture.class.getName());
            List<?> exactClasses = (List<?>) exact.get("classes");
            assertEquals(1, exactClasses.size());
            Map<?, ?> exactClass = (Map<?, ?>) exactClasses.getFirst();
            assertEquals(ExactFixture.class.getName(), exactClass.get("binary_name"));
            assertEquals(
                    Map.of("id", "fixture-mod", "name", "Fixture Mod", "kind", "mod"),
                    exactClass.get("module")
            );
            assertFalse(exactClass.containsKey("access_flags"));

            Map<String, Object> qualified = search.searchClasses("totaldebugcompanion.mcp.mcpclasssearchtest$qualified");
            List<?> qualifiedClasses = (List<?>) qualified.get("classes");
            assertEquals(1, qualifiedClasses.size());
            assertEquals(
                    QualifiedSearchFixture.class.getName(),
                    ((Map<?, ?>) qualifiedClasses.getFirst()).get("binary_name")
            );
            assertFalse(qualified.containsKey("query"));
            assertFalse(qualified.containsKey("count"));
        }
    }

    @Test
    void ownerSearchReplacesTheOldClassMembersTool() throws Exception {
        try (ClassIndex index = ClassIndex.fromBytes(List.of(classBytes(MemberFixture.class)))) {
            Map<String, Object> result = search(index).searchSymbols(null, MemberFixture.class.getName());
            List<?> symbols = (List<?>) result.get("symbols");
            assertTrue(symbols.stream().map(Map.class::cast).anyMatch(symbol ->
                    symbol.get("kind").equals("field")
                            && symbol.get("name").equals("CONSTANT")
                            && symbol.get("descriptor").equals("I")
                            && ((List<?>) symbol.get("modifiers")).containsAll(List.of("static", "final"))
            ));
            assertTrue(symbols.stream().map(Map.class::cast).anyMatch(symbol ->
                    symbol.get("kind").equals("method")
                            && symbol.get("name").equals("answer")
                            && symbol.get("descriptor").equals("()I")
            ));
            assertFalse(result.toString().contains("access_flags"));
        }
    }

    @Test
    void exposesIndexedUsagesAndLiteralValuesWithoutIndexLocalIds() throws Exception {
        try (ClassIndex index = ClassIndex.fromBytes(List.of(
                classBytes(UsageFixture.class),
                classBytes(MemberFixture.class)
        ))) {
            CompanionMcpSearchService search = search(index);
            Map<String, Object> usages = search.findUsages(Map.of(
                    "kind", "method",
                    "owner", MemberFixture.class.getName(),
                    "name", "answer",
                    "descriptor", "()I"
            ));
            assertTrue(((List<?>) usages.get("usages")).stream().map(Map.class::cast).anyMatch(usage -> {
                Map<?, ?> sourceTarget = (Map<?, ?>) usage.get("source_target");
                return sourceTarget.equals(Map.of(
                        "kind", "method",
                        "owner", UsageFixture.class.getName(),
                        "name", "use",
                        "descriptor", "(L" + MemberFixture.class.getName().replace('.', '/') + ";)I"
                ));
            }));
            assertFalse(usages.toString().contains("site_id"));
            assertFalse(usages.toString().contains("source_id"));

            Map<String, Object> literals = search.searchLiterals("literal-proof");
            assertTrue(((List<?>) literals.get("literals")).stream().map(Map.class::cast).anyMatch(literal ->
                    literal.get("value").equals("mcp-literal-proof")
            ));
        }
    }

    @Test
    void decodesClassAccessFlagsInsteadOfReturningAnInteger() {
        int flags = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_RECORD | Opcodes.ACC_SYNTHETIC;

        assertEquals("record", CompanionMcpSearchService.classKind(flags));
        assertEquals(
                List.of("public", "final", "synthetic"),
                CompanionMcpSearchService.classModifiers(flags)
        );
    }

    @Test
    void rejectsIncompleteSymbolAndUsageQueriesAtRuntime() throws Exception {
        try (ClassIndex index = ClassIndex.fromBytes(List.of(classBytes(MemberFixture.class)))) {
            CompanionMcpSearchService search = search(index);

            assertThrows(IllegalArgumentException.class, () -> search.searchSymbols(null, null));
            assertThrows(IllegalArgumentException.class, () -> search.findUsages(Map.of(
                    "kind", "method",
                    "owner", MemberFixture.class.getName()
            )));
        }
    }

    private static CompanionMcpSearchService search(ClassIndex index) {
        return new CompanionMcpSearchService(() -> index, sourceId -> FIXTURE_MODULE);
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

    private static final class ExactFixture {
    }

    private static final class QualifiedSearchFixture {
    }

    private static final class MemberFixture {
        private static final int CONSTANT = 42;

        int answer() {
            return CONSTANT;
        }
    }

    private static final class UsageFixture {
        private static final String MARKER = "mcp-literal-proof";

        int use(MemberFixture fixture) {
            return fixture.answer() + MARKER.length();
        }
    }
}
