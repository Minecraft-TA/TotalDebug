package com.github.minecraft_ta.totalDebugCompanion.jdt.insight;

import com.github.minecraft_ta.totalDebugCompanion.debugger.ExpressionSuggestion;
import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.ASTCache;
import com.github.tth05.jindex.ClassIndex;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpressionScopeAnalyzerTest {
    private static final String SOURCE = """
            class Sample {
                int field;
                static int staticField;

                void run(int parameter) {
                    int outer = 1;
                    {
                        int inner = 2;
                        field++;
                    }
                    field++;
                }

                static void staticRun() {
                    staticField++;
                }
            }
            """;

    @BeforeAll
    static void initializeClassIndex() throws IOException {
        CompanionClassIndex.replace(ClassIndex.fromBytes(List.of(classBytes(Object.class))));
    }

    @AfterAll
    static void closeClassIndex() {
        CompanionClassIndex.close();
    }

    @Test
    void keepsOnlyNamesVisibleInTheSelectedLexicalScope() {
        var unit = ASTCache.rawParse("Sample", SOURCE);
        int innerUse = SOURCE.indexOf("field++");
        int outerUse = SOURCE.indexOf("field++", innerUse + 1);

        List<String> inside = names(ExpressionScopeAnalyzer.analyze(unit, innerUse));
        List<String> outside = names(ExpressionScopeAnalyzer.analyze(unit, outerUse));

        assertTrue(inside.containsAll(List.of("this", "field", "staticField", "parameter", "outer", "inner")));
        assertTrue(outside.containsAll(List.of("this", "field", "staticField", "parameter", "outer")));
        assertFalse(outside.contains("inner"));
    }

    @Test
    void excludesInstanceNamesFromAStaticMethod() {
        var unit = ASTCache.rawParse("Sample", SOURCE);

        List<String> names = names(ExpressionScopeAnalyzer.analyze(unit, SOURCE.lastIndexOf("staticField++")));

        assertEquals(List.of("staticField", "true", "false", "null"), names);
    }

    private static List<String> names(List<ExpressionSuggestion> suggestions) {
        return suggestions.stream().map(ExpressionSuggestion::text).toList();
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (var stream = Objects.requireNonNull(type.getResourceAsStream(resource), resource)) {
            return stream.readAllBytes();
        }
    }
}
