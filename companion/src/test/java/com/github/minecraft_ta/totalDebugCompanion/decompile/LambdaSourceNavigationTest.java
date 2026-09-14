package com.github.minecraft_ta.totalDebugCompanion.decompile;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceLocation;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceQuery;
import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.CodeSymbol;
import com.github.minecraft_ta.totalDebugCompanion.navigation.RuntimeMember;
import com.github.minecraft_ta.totalDebugCompanion.source.SourceDocument;
import com.github.minecraft_ta.totalDebugCompanion.source.SourceLineMap;
import com.github.minecraft_ta.totalDebugCompanion.source.SourceVariableNames;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class LambdaSourceNavigationTest {
    private static final String OWNER = "example.Use";
    private static final String DESCRIPTOR = "(Ljava/lang/String;)Ljava/lang/String;";

    @Test
    void identifiesSameLineLambdasByMethodIdentityRatherThanNamesOrOriginalLines() {
        String source = "package example;\nclass Use { Object a = x -> \"first\"; Object b = x -> \"second\"; }";
        SourceDocument document = document(source,
                parameter("a", source.indexOf("x ->")), parameter("b", source.lastIndexOf("x ->")));
        assertEquals(source.indexOf("x ->"), document.navigate(member("a")).caret());
        assertEquals(source.lastIndexOf("x ->"), document.navigate(member("b")).caret());
        var use = document.usage(location("b"), ReferenceQuery.stringLiteral("second"));
        assertEquals(SourceDocument.Kind.OCCURRENCE, use.kind());
        assertEquals(source.indexOf("\"second\""), use.caret());
        assertEquals(SourceDocument.Kind.CONSTRUCT, document.navigate(member("b")).kind());
        assertTrue(document.declaration(location("b")).isEmpty(), "A lambda is not an explicit method declaration");
    }

    @Test
    void searchingAnOuterLambdaDoesNotStealAnOccurrenceFromAnInnerLambda() {
        String source = "package example;\nclass Use { Object value = x -> y -> \"inner\"; }";
        var document = document(source, parameter("outer", source.indexOf("x ->")), parameter("inner", source.indexOf("y ->")));
        assertEquals(SourceDocument.Kind.CONSTRUCT,
                document.usage(location("outer"), ReferenceQuery.stringLiteral("inner")).kind());
        assertEquals(source.indexOf("\"inner\""),
                document.usage(location("inner"), ReferenceQuery.stringLiteral("inner")).caret());
        assertEquals(SourceDocument.Kind.CLASS,
                document.usage(ReferenceLocation.method(OWNER, "<clinit>", "()V"), ReferenceQuery.stringLiteral("inner")).kind());
    }

    @Test
    void multipleSourceLambdasForOneMethodRemainAmbiguous() {
        String source = "package example;\nclass Use { Object a = x -> \"one\"; Object b = x -> \"two\"; }";
        var document = document(source, parameter("shared", source.indexOf("x ->")), parameter("shared", source.lastIndexOf("x ->")));
        assertEquals(SourceDocument.Kind.CLASS, document.navigate(member("shared")).kind());
    }

    @Test
    void ordinaryMethodParametersAreNotMistakenForLambdaOrigins() {
        String source = "package example;\nclass Use { void method(String x) { Object f = y -> \"text\"; } }";
        var document = document(source, parameter("unrendered", source.indexOf("x)")));
        assertEquals(SourceDocument.Kind.CLASS, document.navigate(member("unrendered")).kind());
    }

    @Test
    void localDeclarationsIdentifyZeroArgumentLambdaBlocks() {
        String source = "package example;\nclass Use { Object value = () -> { int sum = 0; return sum; }; }";
        var symbol = new CodeSymbol.MethodSymbol(OWNER, "body", "()I");
        var document = document(source, new SourceDocument.SymbolSpan(symbol,
                SourceDocument.SymbolRole.METHOD_LOCAL, source.indexOf("sum ="), 3));
        var destination = document.navigate(new RuntimeMember.Method(OWNER, "body", "()I"));
        assertEquals(SourceDocument.Kind.CONSTRUCT, destination.kind());
        assertEquals(source.indexOf("() ->"), destination.caret());
    }

    @Test
    void variableReferencesCannotBeUsedAsOwningMethodDeclarations() {
        String source = "package example;\nclass Use { Object value = () -> input; }";
        var document = document(source, new SourceDocument.SymbolSpan(
                new CodeSymbol.MethodSymbol(OWNER, "body", DESCRIPTOR), SourceDocument.SymbolRole.METHOD_LOCAL,
                source.indexOf("input"), 5));
        assertEquals(SourceDocument.Kind.CLASS, document.navigate(member("body")).kind());
    }

    private static SourceDocument document(String source, SourceDocument.SymbolSpan... spans) {
        return new SourceDocument(OWNER, source, SourceLineMap.empty(), SourceVariableNames.empty(), List.of(spans));
    }

    private static SourceDocument.SymbolSpan parameter(String method, int offset) {
        return new SourceDocument.SymbolSpan(new CodeSymbol.MethodSymbol(OWNER, method, DESCRIPTOR),
                SourceDocument.SymbolRole.METHOD_PARAMETER, offset, 1);
    }

    private static RuntimeMember.Method member(String name) { return new RuntimeMember.Method(OWNER, name, DESCRIPTOR); }
    private static ReferenceLocation location(String name) { return ReferenceLocation.method(OWNER, name, DESCRIPTOR); }
}
