package com.github.minecraft_ta.totalDebugCompanion.jdt.completion;

import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import com.github.minecraft_ta.totalDebugCompanion.jdt.impls.CompilationUnitImpl;
import com.github.tth05.jindex.ClassIndex;
import org.eclipse.jdt.core.CompletionContext;
import org.eclipse.jdt.core.CompletionProposal;
import org.eclipse.jdt.core.Flags;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CompletionPresentationTest {
    @BeforeAll
    static void index() throws Exception {
        List<byte[]> classes = new ArrayList<>();
        for (Class<?> type : List.of(Object.class, String.class)) {
            try (var stream = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                classes.add(stream.readAllBytes());
            }
        }
        CompanionClassIndex.replace(ClassIndex.fromBytes(classes));
    }

    @AfterAll
    static void closeIndex() {
        CompanionClassIndex.close();
    }

    @Test
    void labelsGenericVarargsAndInsertsArgumentStops() {
        String source = "class Proof { void run() { collect } }";
        CompletionProposal proposal = proposal(CompletionProposal.METHOD_REF, "collect", "collect()",
                "(Ljava.util.List<+Ljava.lang.Number;>;[Ljava.lang.String;)V", source);
        proposal.setFlags(Flags.AccVarargs);
        assertEquals("collect(List<? extends Number> arg0, String... arg1) : void",
                CompletionLabels.label(proposal, new CompletionContext()));
        CompletionItem item = convert(source, proposal);
        assertEquals("collect(${1:arg0}, ${2:arg1})${0};", item.getTextEdits().getFirst().getNewText());
        assertTrue(item.getTextEdits().getFirst().isSnippet());
    }

    @Test
    void preservesMethodReferencesAndAnExistingSemicolon() {
        String source = "class Proof { void run() { call; } }";
        CompletionProposal call = proposal(CompletionProposal.METHOD_REF, "call", "call()", "()V", source);
        assertEquals("call()${0}", convert(source, call).getTextEdits().getFirst().getNewText());
        CompletionProposal reference = proposal(CompletionProposal.METHOD_NAME_REFERENCE, "call", "call", "()V", source);
        assertEquals("call", convert(source, reference).getTextEdits().getFirst().getNewText());
    }

    @Test
    void createsLambdaArgumentStops() {
        String source = "class Proof { void run() { lambda } }";
        CompletionProposal lambda = proposal(CompletionProposal.LAMBDA_EXPRESSION, "lambda", "", "(II)I", source);
        assertEquals("(${1:arg0}, ${2:arg1}) -> ${0}", convert(source, lambda).getTextEdits().getFirst().getNewText());
    }

    @Test
    void qualifiesARequiredStaticMethodTypeWithADot() {
        String source = "class Proof { void run() { random } }";
        CompletionProposal method = proposal(CompletionProposal.METHOD_REF, "random", "random()", "()D", source);
        CompletionProposal required = CompletionProposal.create(CompletionProposal.TYPE_IMPORT, 0);
        required.setSignature("Ljava.lang.Math;".toCharArray());
        method.setRequiredProposals(new CompletionProposal[]{required});
        assertEquals("Math.random()${0}", convert(source, method).getTextEdits().getFirst().getNewText());
    }

    @Test
    void importConflictKeepsTheNewTypeQualified() {
        String source = "import java.util.Date;\nclass Proof { Date field; Dat value; }";
        CompletionProposal type = proposal(CompletionProposal.TYPE_REF, "Dat", "java.sql.Date", "Ljava.sql.Date;", source);
        // Complete the partial type, not the existing Date declaration.
        int offset = source.indexOf("Dat value");
        type.setReplaceRange(offset, offset + 3);
        CompletionItem item = convert(source, type);
        assertEquals("java.sql.Date", item.getTextEdits().getFirst().getNewText());
        assertEquals(1, item.getTextEdits().size());
    }

    @Test
    void preservesTypedQualificationAndImportCompletion() {
        String source = "class Proof { java.lang.Str field; }";
        CompletionProposal type = proposal(CompletionProposal.TYPE_REF, "java.lang.Str", "java.lang.String", "Ljava.lang.String;", source);
        assertEquals("java.lang.String", convert(source, type).getTextEdits().getFirst().getNewText());
        type.setCompletion("java.lang.String;".toCharArray());
        assertEquals("java.lang.String;", convert(source, type).getTextEdits().getFirst().getNewText());
    }

    @Test
    void constructorCompletionUsesTheRequiredTypeRangeAndDiamond() {
        String source = "class Proof { void run() { new Box } }";
        int start = source.indexOf("Box");
        CompletionProposal constructor = new org.eclipse.jdt.internal.codeassist.InternalCompletionProposal(
                CompletionProposal.CONSTRUCTOR_INVOCATION, start + 3) {
            @Override public boolean isConstructor() { return true; }
            @Override public boolean canUseDiamond(CompletionContext context) { return true; }
        };
        constructor.setName("Box".toCharArray());
        constructor.setCompletion("()".toCharArray());
        constructor.setSignature("(TE;)V".toCharArray());
        constructor.setReplaceRange(start + 3, start + 3);
        CompletionProposal type = proposal(CompletionProposal.TYPE_REF, "Box", "org.example.Box",
                "Lorg.example.Box;", source);
        constructor.setRequiredProposals(new CompletionProposal[]{type});
        CompletionItem item = convert(source, constructor);
        String result = apply(source, item);
        assertTrue(result.contains("import org.example.Box;"), result);
        assertTrue(result.contains("new Box<>(${1:arg0})${0}"), result);
        assertEquals(start, item.getTextEdits().getFirst().getRange().getOffset());
    }

    @Test
    void snippetsPreserveSpacesAndTabsAtEndOfBuffer() {
        assertEquals("  \t", SnippetCompletionProposalProvider.indentationAt(
                new CompilationUnitImpl("Proof", "class Proof {\n  \tsout"), 23));
        assertEquals("", SnippetCompletionProposalProvider.indentationAt(
                new CompilationUnitImpl("Proof", ""), 0));
        assertEquals("", SnippetCompletionProposalProvider.indentationAt(
                new CompilationUnitImpl("Proof", "\n  sout"), 0));
    }

    private static CompletionProposal proposal(int kind, String token, String completion, String signature, String source) {
        int offset = source.indexOf(token);
        CompletionProposal proposal = CompletionProposal.create(kind, offset);
        proposal.setName(token.toCharArray());
        proposal.setCompletion(completion.toCharArray());
        proposal.setSignature(signature.toCharArray());
        proposal.setReplaceRange(offset, offset + token.length());
        return proposal;
    }

    private static CompletionItem convert(String source, CompletionProposal proposal) {
        CompletionItem item = new CompletionItem(null);
        new CompletionEdits(new CompilationUnitImpl("Proof", source), new CompletionContext()).populate(proposal, item);
        return item;
    }

    private static String apply(String source, CompletionItem item) {
        StringBuilder result = new StringBuilder(source);
        item.getTextEdits().stream().sorted(Comparator.comparingInt((CustomTextEdit e) -> e.getRange().getOffset()).reversed())
                .forEach(edit -> result.replace(edit.getRange().getOffset(), edit.getRange().getEndOffset(), edit.getNewText()));
        return result.toString();
    }
}
