package com.github.minecraft_ta.totalDebugCompanion.jdt;

import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.ASTCache;
import com.github.minecraft_ta.totalDebugCompanion.jdt.insight.SourceDeclaration;
import com.github.minecraft_ta.totalDebugCompanion.jdt.insight.SourceDeclarationAnalyzer;
import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.CodeSymbol;
import com.github.tth05.jindex.ClassIndex;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SourceDeclarationAnalyzerTest {
    private static final String SOURCE = """
            package sample;

            interface Action {
                void run();
            }

            final class Implementation implements Action {
                int state;

                @Override
                public void run() {
                }
            }
            """;
    private static final String CONSTRUCTOR_SOURCE = """
            package sample;

            final class Outer {
                enum Mode {
                    ENABLED(1);

                    Mode(int code) {
                    }
                }

                final class Inner {
                    Inner(int value) {
                    }
                }
            }
            """;

    @BeforeAll
    static void initializeClassIndex() throws Exception {
        String resource = "/java/lang/Object.class";
        try (var stream = Objects.requireNonNull(Object.class.getResourceAsStream(resource), resource)) {
            CompanionClassIndex.replace(ClassIndex.fromBytes(List.of(stream.readAllBytes())));
        }
    }

    @AfterAll
    static void closeClassIndex() {
        CompanionClassIndex.close();
    }

    @Test
    void extractsTypesMethodsAndFieldsWithHeaderAnchors() {
        var unit = ASTCache.rawParse("Implementation", SOURCE);
        List<SourceDeclaration> declarations = SourceDeclarationAnalyzer.analyze(unit, SOURCE);

        assertTrue(declarations.stream().anyMatch(declaration ->
                declaration.symbol().equals(new CodeSymbol.ClassSymbol("sample.Action"))
                        && SOURCE.charAt(declaration.anchorOffset() - 1) == '{'
        ));
        assertTrue(declarations.stream().anyMatch(declaration ->
                declaration.symbol().equals(new CodeSymbol.MethodSymbol("sample.Action", "run", "()V"))
                        && SOURCE.charAt(declaration.anchorOffset() - 1) == ';'
        ));
        assertTrue(declarations.stream().anyMatch(declaration ->
                declaration.symbol().equals(new CodeSymbol.FieldSymbol("sample.Implementation", "state", "I"))
                        && SOURCE.charAt(declaration.anchorOffset() - 1) == ';'
        ));
        assertTrue(declarations.stream().anyMatch(declaration ->
                declaration.symbol().equals(new CodeSymbol.MethodSymbol(
                        "sample.Implementation", "run", "()V"
                )) && SOURCE.charAt(declaration.anchorOffset() - 1) == '{'
                        && SOURCE.substring(
                        declaration.markerOffset(),
                        declaration.endOffset()
                ).contains("run()")
        ));
        assertEquals(5, declarations.size());
    }

    @Test
    void includesCompilerParametersInNestedConstructorDescriptors() {
        var unit = ASTCache.rawParse("Outer", CONSTRUCTOR_SOURCE);
        List<CodeSymbol> symbols = SourceDeclarationAnalyzer.analyze(unit, CONSTRUCTOR_SOURCE).stream()
                .map(SourceDeclaration::symbol)
                .toList();

        assertTrue(symbols.contains(new CodeSymbol.MethodSymbol(
                "sample.Outer$Mode",
                "<init>",
                "(Ljava/lang/String;II)V"
        )));
        assertTrue(symbols.contains(new CodeSymbol.MethodSymbol(
                "sample.Outer$Inner",
                "<init>",
                "(Lsample/Outer;I)V"
        )));
    }
}
