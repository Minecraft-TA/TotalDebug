package com.github.minecraft_ta.totalDebugCompanion.script;

import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerCompletionProposal;
import com.github.minecraft_ta.totalDebugCompanion.debugger.fixture.ExternalCompletionType;
import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaSnippetSource;
import com.github.minecraft_ta.totalDebugCompanion.jdt.completion.CompletionItem;
import com.github.minecraft_ta.totalDebugCompanion.jdt.completion.CustomCompletionRequestor;
import com.github.minecraft_ta.totalDebugCompanion.jdt.impls.CompilationUnitImpl;
import com.github.tth05.jindex.ClassIndex;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnippetExpressionSupportTest {
    @BeforeAll
    static void initializeClassIndex() throws IOException {
        CompanionClassIndex.set(ClassIndex.fromBytes(List.of(
                classBytes(Object.class),
                classBytes(com.github.minecraft_ta.totaldebug.TotalDebug.class),
                classBytes(ExternalCompletionType.class),
                classBytes(com.github.minecraft_ta.totalDebugCompanion.debugger.fixture.a.Blocks.class),
                classBytes(com.github.minecraft_ta.totalDebugCompanion.debugger.fixture.z.Blocks.class),
                classBytes(org.example.totaldebug.fixture.LaterType.class)
        )));
    }

    @AfterAll
    static void closeClassIndex() {
        CompanionClassIndex.get().close();
        CompanionClassIndex.clear();
    }

    @Test
    void acceptingATypeCompletionAddsItsImportToExecutableSource() {
        SnippetExpressionSupport support = new SnippetExpressionSupport("CompanionExpression");
        List<DebuggerCompletionProposal> proposals = support.complete("Blo", 3, true).join().stream()
                .filter(proposal -> proposal.kind() == DebuggerCompletionProposal.Kind.TYPE)
                .filter(proposal -> proposal.insertionText().equals("Blocks"))
                .toList();
        assertTrue(proposals.stream().anyMatch(proposal -> proposal.requiredImports().contains(
                "com.github.minecraft_ta.totalDebugCompanion.debugger.fixture.a.Blocks"
        )), proposals.toString());
        DebuggerCompletionProposal blocks = proposals.stream()
                .filter(proposal -> proposal.requiredImports().contains(
                        "com.github.minecraft_ta.totalDebugCompanion.debugger.fixture.z.Blocks"
                ))
                .findFirst()
                .orElseThrow();

        support.accepted(blocks);

        assertTrue(blocks.requiredImports().contains(
                "com.github.minecraft_ta.totalDebugCompanion.debugger.fixture.z.Blocks"
        ), blocks.toString());
        assertTrue(support.source("Blocks.CORRECT").source().contains(
                "import com.github.minecraft_ta.totalDebugCompanion.debugger.fixture.z.Blocks;"
        ));
    }

    @Test
    void acceptingAnotherTypeWithTheSameSimpleNameReplacesTheOldImport() {
        SnippetExpressionSupport support = new SnippetExpressionSupport("CompanionExpression");
        List<DebuggerCompletionProposal> proposals = support.complete("Blo", 3, true).join().stream()
                .filter(proposal -> proposal.kind() == DebuggerCompletionProposal.Kind.TYPE)
                .filter(proposal -> proposal.insertionText().equals("Blocks"))
                .toList();
        DebuggerCompletionProposal first = proposals.stream()
                .filter(proposal -> proposal.requiredImports().contains(
                        "com.github.minecraft_ta.totalDebugCompanion.debugger.fixture.a.Blocks"
                ))
                .findFirst()
                .orElseThrow();
        DebuggerCompletionProposal second = proposals.stream()
                .filter(proposal -> proposal.requiredImports().contains(
                        "com.github.minecraft_ta.totalDebugCompanion.debugger.fixture.z.Blocks"
                ))
                .findFirst()
                .orElseThrow();

        support.accepted(first);
        support.accepted(second);

        assertEquals(List.of("com.github.minecraft_ta.totalDebugCompanion.debugger.fixture.z.Blocks"),
                support.imports());
        String source = support.source("Blocks.CORRECT").source();
        assertTrue(source.contains("import com.github.minecraft_ta.totalDebugCompanion.debugger.fixture.z.Blocks;"));
        assertFalse(source.contains("import com.github.minecraft_ta.totalDebugCompanion.debugger.fixture.a.Blocks;"));
    }

    @Test
    void autoImportsAnUnrelatedExternalType() {
        SnippetExpressionSupport support = new SnippetExpressionSupport("CompanionExpression");
        DebuggerCompletionProposal externalType = support.complete("ExternalComp", 12, true).join().stream()
                .filter(proposal -> proposal.kind() == DebuggerCompletionProposal.Kind.TYPE)
                .filter(proposal -> proposal.insertionText().equals("ExternalCompletionType"))
                .findFirst()
                .orElseThrow();

        support.accepted(externalType);

        assertTrue(externalType.requiredImports().contains(ExternalCompletionType.class.getName()),
                externalType.toString());
        String expression = "ExternalCompletionType.externalStatic()";
        assertTrue(support.complete("ExternalCompletionType.", "ExternalCompletionType.".length(), true)
                .join().stream().anyMatch(proposal -> proposal.insertionText().startsWith("externalStatic")));
        assertTrue(support.source(expression).source().contains(
                "import " + ExternalCompletionType.class.getName() + ";"
        ));
    }

    @Test
    void autoImportsTotalDebugBeforeCompletingItsLoggerField() {
        SnippetExpressionSupport support = new SnippetExpressionSupport("CompanionExpression");
        DebuggerCompletionProposal totalDebug = support.complete("TotalDeb", 8, true).join().stream()
                .filter(proposal -> proposal.kind() == DebuggerCompletionProposal.Kind.TYPE)
                .filter(proposal -> proposal.insertionText().equals("TotalDebug"))
                .findFirst()
                .orElseThrow();

        support.accepted(totalDebug);

        assertTrue(totalDebug.requiredImports().contains("com.github.minecraft_ta.totaldebug.TotalDebug"),
                totalDebug.toString());
        assertTrue(support.complete("TotalDebug.", "TotalDebug.".length(), true).join().stream()
                .anyMatch(proposal -> proposal.insertionText().equals("LOGGER")));
        assertTrue(support.source("TotalDebug.LOGGER").source().contains(
                "import com.github.minecraft_ta.totaldebug.TotalDebug;"
        ));
    }

    @Test
    void mapsJdtImportEditsBackIntoTheVisibleScriptBody() throws Exception {
        assertImportEditMaps("LaterT", "LaterType", org.example.totaldebug.fixture.LaterType.class.getName());
        assertImportEditMaps("ExternalComp", "ExternalCompletionType", ExternalCompletionType.class.getName());
    }

    private static void assertImportEditMaps(String editor, String insertion, String qualifiedName) throws Exception {
        JavaSnippetSource.GeneratedSource generated = JavaSnippetSource.body("Proof", editor);
        int caret = generated.sourceMap().toGeneratedOffset(editor.length());
        CompilationUnitImpl unit = new CompilationUnitImpl("Proof", generated.source());
        CompletableFuture<List<CompletionItem>> completed = new CompletableFuture<>();
        CustomCompletionRequestor requestor = new CustomCompletionRequestor(
                unit,
                caret,
                (ignored, items) -> completed.complete(items)
        );

        unit.codeComplete(caret, requestor, requestor);

        CompletionItem externalType = completed.join().stream()
                .filter(item -> item.getTextEdits().stream()
                        .anyMatch(edit -> edit.getNewText().equals(insertion)))
                .findFirst()
                .orElseThrow();
        var importEdit = externalType.getTextEdits().stream()
                .filter(edit -> edit.getNewText().contains("import " + qualifiedName))
                .findFirst()
                .orElseThrow();
        String message = qualifiedName + " import edit " + importEdit.getRange().getOffset()
                + ":" + importEdit.getRange().getLength();
        int mappedStart = generated.sourceMap().toEditorOffset(importEdit.getRange().getOffset());
        int mappedEnd = generated.sourceMap().toEditorOffset(importEdit.getRange().getEndOffset());
        assertTrue(mappedStart >= 0, message);
        assertTrue(mappedEnd >= mappedStart, message);
        String mappedText = generated.sourceMap().mapInsertionText(
                importEdit.getRange().getOffset(),
                importEdit.getNewText()
        );
        String applied = editor.substring(0, mappedStart) + mappedText + editor.substring(mappedEnd);
        assertTrue(applied.startsWith("import " + qualifiedName + ";\n"),
                message + " produced " + applied.replace('\n', '|'));
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (var stream = Objects.requireNonNull(type.getResourceAsStream(resource), resource)) {
            return stream.readAllBytes();
        }
    }
}
