package com.github.minecraft_ta.totalDebugCompanion.jdt.insight;

import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerCompletionProposal;
import com.github.minecraft_ta.totalDebugCompanion.debugger.fixture.ExternalCompletionType;
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
        CompanionClassIndex.replace(ClassIndex.fromBytes(List.of(
                classBytes(Object.class), classBytes(ExternalCompletionType.class)
        )));
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

        assertEquals(List.of("staticField", "staticRun()", "false", "null", "true"), names);
    }

    @Test
    void completesSourceMembersAndInheritedPrivateDeclarationsBeforePause() {
        String source = """
                class Base {
                    private int inherited;
                    private String baseCall() { return \"base\"; }
                    static int BASE_STATIC;
                }
                class Sample extends Base {
                    private int own;
                    void run() {
                        Sample target = null;
                        target.
                    }
                }
                """;
        var unit = ASTCache.rawParse("Sample", source);
        int context = source.indexOf("target.");
        List<DebuggerCompletionProposal> completions = ExpressionScopeAnalyzer.complete(
                unit, context, "target.", "target.".length()
        );

        assertTrue(names(completions).containsAll(List.of("own", "inherited", "baseCall()", "BASE_STATIC")), names(completions).toString());
        assertTrue(completions.stream().allMatch(proposal -> proposal.replacementStart() == "target.".length()
                && proposal.replacementEnd() == "target.".length()));
    }

    @Test
    void completesTheMemberOwnerAtTheCaretInsideACompoundExpression() {
        String source = """
                class Sample {
                    private int own;
                    void run() {
                        Sample target = null;
                    }
                }
                """;
        var unit = ASTCache.rawParse("Sample", source);
        int context = source.indexOf("target =");
        String expression = "target.own + target.";

        List<DebuggerCompletionProposal> completions = ExpressionScopeAnalyzer.complete(
                unit, context, expression, expression.length()
        );

        assertTrue(names(completions).contains("own"), names(completions).toString());
        assertTrue(completions.stream().allMatch(proposal -> proposal.replacementStart() == expression.length()
                && proposal.replacementEnd() == expression.length()));
    }

    @Test
    void completesMembersOfAnIndexedCrossFileTypeFromAParameter() throws Exception {
        String externalSource = """
                package com.github.minecraft_ta.totalDebugCompanion.debugger.fixture;
                public class ExternalCompletionType {
                    private String privateValue;
                    public String externalMethod() { return privateValue; }
                    public static String externalStatic() { return "static"; }
                }
                """;
        ASTCache.update("external-completion-type", "ExternalCompletionType", externalSource);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        while (ASTCache.getFromCache("external-completion-type") == null && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        String source = """
                package com.github.minecraft_ta.totalDebugCompanion.debugger.fixture;
                import com.github.minecraft_ta.totalDebugCompanion.debugger.fixture.ExternalCompletionType;
                class Sample {
                    void run(ExternalCompletionType parameter) {
                        parameter.
                    }
                }
                """;
        var unit = ASTCache.rawParse("Sample", source);
        int context = source.indexOf("parameter.");
        List<DebuggerCompletionProposal> completions = ExpressionScopeAnalyzer.complete(
                unit, context, "parameter.", "parameter.".length()
        );

        assertTrue(names(completions).containsAll(List.of("externalMethod()", "externalStatic()")),
                names(completions).toString());
        ASTCache.removeFromCache("external-completion-type");
    }

    private static List<String> names(List<DebuggerCompletionProposal> suggestions) {
        return suggestions.stream().map(DebuggerCompletionProposal::label).toList();
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (var stream = Objects.requireNonNull(type.getResourceAsStream(resource), resource)) {
            return stream.readAllBytes();
        }
    }
}
