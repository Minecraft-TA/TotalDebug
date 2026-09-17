package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaSnippetSource;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.ASTCache;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.JavaAnalysis;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.JavaAnalysisFixtures;
import com.github.minecraft_ta.totalDebugCompanion.jdt.semanticHighlighting.CustomJavaTokenMaker;
import com.github.minecraft_ta.totalDebugCompanion.jdt.semanticHighlighting.ShadowedTokenTypes;
import com.github.tth05.jindex.ClassIndex;
import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Token;
import org.eclipse.jdt.core.compiler.IProblem;
import org.fife.ui.rsyntaxtextarea.parser.ParserNotice;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import javax.swing.SwingUtilities;
import javax.swing.KeyStroke;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.awt.Graphics;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

public class JavaEditorAnalysisTest {
    public static class Unused { }
    public static class Bus { private Object listeners; public void start() { } }
    public static class Holder { public static final Object BUS = null; }
    private static ClassIndex index;
    private static final String IMPORTS = "import " + Unused.class.getCanonicalName() + ";\nimport "
            + Bus.class.getCanonicalName() + ";\nimport " + Holder.class.getCanonicalName() + ";\n";
    private static final String RECEIVER = "((Bus) Holder.BUS)";
    private static final String VALID = IMPORTS + RECEIVER + ".start();";

    @BeforeAll static void bind() throws Exception {
        index = JavaAnalysisFixtures.index(JavaEditorAnalysisTest.class, Unused.class, Bus.class, Holder.class);
        CompanionClassIndex.set(index);
    }
    @AfterAll static void closeIndex() { CompanionClassIndex.clear(); index.close(); }

    @Test void unfinishedMemberIsNeverRedWhileEditingEvenAfterAnalysisCompletes() throws Exception {
        try (var fixture = new Fixture(VALID)) {
            fixture.finish();
            onEdt(() -> {
                fixture.area.replaceRange("", VALID.lastIndexOf("start"), VALID.length());
                fixture.area.setCaretPosition(fixture.area.getDocument().getLength());
                return null;
            });
            fixture.finish();
            assertEquals(0, errors(fixture), "The trailing dot must not underline the receiver");
            assertTrue(onEdt(() -> fixture.owner.currentSnapshot().problems().stream().anyMatch(p -> p.level() == ParserNotice.Level.ERROR)),
                    "Analysis stays truthful while presentation defers unfinished-code errors");
            for (String text : List.of("l", "istene", "\n")) {
                onEdt(() -> { fixture.area.append(text); fixture.area.setCaretPosition(fixture.area.getDocument().getLength()); return null; });
                fixture.finish();
                fixture.finish();
                assertEquals(0, errors(fixture), "Pausing or a multiline continuation must not finish editing");
            }
            onEdt(() -> { fixture.area.setCaretPosition(0); return null; });
            assertTrue(errors(fixture) > 0, "Leaving unfinished code reveals its diagnostics");
        }
    }

    @Test void existingCallDelimitersDoNotCommitTheMemberNameBeingEdited() throws Exception {
        try (var fixture = new Fixture(VALID)) {
            fixture.finish();
            onEdt(() -> {
                int name = VALID.lastIndexOf("start");
                fixture.area.replaceRange("", name + 3, name + 5);
                fixture.area.setCaretPosition(name + 3);
                return null;
            });
            fixture.finish();
            assertEquals(0, errors(fixture), "Existing (); must not commit the partial name sta");
            onEdt(() -> { fixture.area.setCaretPosition(fixture.area.getDocument().getLength()); return null; });
            assertTrue(errors(fixture) > 0, "Leaving the edited name commits it");
        }
    }

    @Test void unresolvedGenericTypeRemainsVisibleWhileEditingItsInitializer() throws Exception {
        String source = IMPORTS + "class Bag<T> {}\nBag<MissingType> value = null;";
        try (var fixture = new Fixture(source)) {
            fixture.finish();
            onEdt(() -> {
                int initializer = fixture.area.getText().lastIndexOf("null");
                fixture.area.replaceRange(RECEIVER + ".", initializer, fixture.area.getDocument().getLength());
                fixture.area.setCaretPosition(fixture.area.getDocument().getLength());
                assertEquals(1, errors(fixture), "The existing missing-type error stays visible before analysis");
                return null;
            });
            fixture.finish();
            assertEquals(1, errors(fixture), "The unfinished receiver must not mask the earlier missing generic type");
            onEdt(() -> {
                assertTrue(fixture.owner.currentSnapshot().problems().stream().anyMatch(problem -> problem.id() == IProblem.UndefinedType));
                fixture.area.append("listene");
                fixture.area.setCaretPosition(fixture.area.getDocument().getLength());
                return null;
            });
            fixture.finish();
            assertEquals(1, errors(fixture));
            assertTrue(onEdt(() -> fixture.area.getParserNotices().stream().anyMatch(notice -> notice.getMessage().contains("MissingType"))));
        }
    }

    @Test void unfinishedQualifiedTypeIsNotMistakenForAnUnrelatedMissingImport() throws Exception {
        for (String type : List.of("Missing.", "Missing .", "java.util.Missin", "Missing.Inner")) {
            try (var fixture = new Fixture("return new String();")) {
                fixture.finish();
                onEdt(() -> {
                    fixture.area.replaceRange(type, "return new ".length(), fixture.area.getDocument().getLength());
                    fixture.area.setCaretPosition(fixture.area.getDocument().getLength());
                    return null;
                });
                fixture.finish();
                assertEquals(0, errors(fixture), type);
            }
        }
    }

    @Test void unresolvedTypeIsStillDeferredWhenThatTypeNameIsBeingEdited() throws Exception {
        try (var fixture = new Fixture("class Bag<T> {}\nBag<String> value = null;")) {
            fixture.finish();
            onEdt(() -> {
                int start = fixture.area.getText().indexOf("String");
                fixture.area.replaceRange("Missin", start, start + "String".length());
                fixture.area.setCaretPosition(start + "Missin".length());
                return null;
            });
            fixture.finish();
            assertEquals(0, errors(fixture));
            onEdt(() -> {
                int caret = fixture.area.getCaretPosition();
                fixture.area.replaceRange("", caret - 1, caret);
                fixture.area.setCaretPosition(caret - 1);
                return null;
            });
            fixture.finish();
            assertEquals(0, errors(fixture), "Backspacing inside a type must remain provisional");
            onEdt(() -> { fixture.area.setCaretPosition(fixture.area.getDocument().getLength()); return null; });
            assertEquals(1, errors(fixture), "Leaving the type reveals the missing import/type");
        }
    }

    @Test void sameLineCompletedErrorStaysVisibleWhileTheNextExpressionIsUnfinished() throws Exception {
        String prefix = "int count = \"wrong\"; ";
        try (var fixture = new Fixture(IMPORTS + prefix + RECEIVER + ".start();")) {
            fixture.finish();
            assertEquals(1, errors(fixture));
            onEdt(() -> {
                int start = fixture.area.getText().lastIndexOf("start");
                fixture.area.replaceRange("", start, fixture.area.getDocument().getLength());
                fixture.area.setCaretPosition(start);
                return null;
            });
            fixture.finish();
            assertEquals(1, errors(fixture), "Only the completed assignment should be red");
            onEdt(() -> { fixture.area.append("notAMethod();"); fixture.area.setCaretPosition(fixture.area.getDocument().getLength()); return null; });
            fixture.finish();
            assertEquals(2, errors(fixture), "Completing the statement reveals the real method error on that same line");
        }
    }

    @Test void placingTheCaretOnAnExistingErrorDoesNotHideIt() throws Exception {
        try (var fixture = new Fixture("return missing;")) {
            fixture.finish();
            onEdt(() -> { fixture.area.setCaretPosition("return miss".length()); return null; });
            assertEquals(1, errors(fixture));
        }
    }

    @Test void deletingFirstCharacterOfAdjacentStatementDoesNotHideThePreviousError() throws Exception {
        try (var fixture = new Fixture("int wrong = \"x\";unknownCall();")) {
            fixture.finish();
            assertEquals(2, errors(fixture));
            onEdt(() -> {
                int offset = fixture.area.getText().indexOf("unknown");
                fixture.area.replaceRange("", offset, offset + 1);
                fixture.area.setCaretPosition(offset);
                return null;
            });
            fixture.finish();
            assertEquals(1, errors(fixture));
            assertTrue(onEdt(() -> fixture.area.getParserNotices().stream().anyMatch(n -> n.getMessage().contains("String"))));
        }
    }

    @Test void trailingDotCannotAbsorbAnUntouchedFollowingStatementsErrors() throws Exception {
        try (var fixture = new Fixture(VALID + "\nint n = missing;")) {
            fixture.finish();
            assertEquals(1, errors(fixture));
            onEdt(() -> {
                int offset = VALID.lastIndexOf("start");
                fixture.area.replaceRange("", offset, offset + "start();".length());
                fixture.area.setCaretPosition(offset);
                return null;
            });
            fixture.finish();
            assertEquals(1, errors(fixture), "Keep the real missing-variable error, without recovery errors spilling into that statement");
            assertTrue(onEdt(() -> fixture.area.getParserNotices().stream().anyMatch(n -> n.getMessage().contains("missing"))));
            onEdt(() -> { fixture.area.insert("sta", fixture.area.getCaretPosition()); fixture.area.setCaretPosition(IMPORTS.length() + RECEIVER.length() + 4); return null; });
            fixture.finish();
            assertEquals(1, errors(fixture));
            onEdt(() -> { fixture.owner.completionAccepted(); return null; });
            assertEquals(1, errors(fixture), "Completion must not broaden editing into the following statement");
        }
    }

    @Test void deletingAWholeStatementDoesNotStartEditingTheUnchangedFollowingStatement() throws Exception {
        String prefix = "int count = 1;";
        try (var fixture = new Fixture(prefix + "unknownCall();")) {
            fixture.finish();
            onEdt(() -> { fixture.area.replaceRange("", 0, prefix.length()); fixture.area.setCaretPosition(0); return null; });
            assertEquals(1, errors(fixture));
            fixture.finish();
            assertEquals(1, errors(fixture));
        }
    }

    @Test void deletingOnlyTheSeparatorKeepsEditingTheSurvivingPreviousStatement() throws Exception {
        String prefix = "unknownCall();";
        try (var fixture = new Fixture(prefix + "int count = \"wrong\";")) {
            fixture.finish();
            assertEquals(2, errors(fixture));
            onEdt(() -> {
                fixture.area.replaceRange("", prefix.length() - 1, prefix.length());
                fixture.area.setCaretPosition(prefix.length() - 1);
                return null;
            });
            fixture.finish();
            assertEquals(1, errors(fixture));
            assertTrue(onEdt(() -> fixture.area.getParserNotices().stream().anyMatch(n -> n.getMessage().contains("String"))));
        }
    }

    @Test void explicitCompilationRevealsDeferredErrorsWithoutChangingTheAnalysis() throws Exception {
        try (var fixture = new Fixture(VALID)) {
            fixture.finish();
            onEdt(() -> {
                int start = VALID.lastIndexOf("start");
                fixture.area.replaceRange("", start, VALID.length());
                fixture.area.setCaretPosition(start);
                return null;
            });
            fixture.finish();
            assertEquals(0, errors(fixture));
            var captured = onEdt(fixture.owner::currentSnapshot);
            onEdt(() -> { fixture.owner.finishEditing(); return null; });
            assertTrue(errors(fixture) > 0);
            assertSame(captured, onEdt(fixture.owner::currentSnapshot));
        }
    }

    @Test void completionInsideArgumentsDoesNotEndEditingButCompletedCallDoes() throws Exception {
        try (var fixture = new Fixture("return 1;")) {
            fixture.finish();
            onEdt(() -> {
                fixture.area.setText("missingCall(unknownArgument);");
                fixture.area.setCaretPosition("missingCall(".length());
                fixture.owner.completionAccepted();
                return null;
            });
            fixture.finish();
            assertEquals(0, errors(fixture), "Selecting a method and entering its argument placeholder is still editing");
            onEdt(() -> {
                fixture.area.setCaretPosition("missingCall(unknownArgument)".length());
                fixture.owner.completionAccepted();
                return null;
            });
            assertTrue(errors(fixture) > 0, "A completed accepted call releases deferred errors");
        }
    }

    @Test void nestedCallDelimiterDoesNotCommitTheOuterArguments() throws Exception {
        try (var fixture = new Fixture("return 1;")) {
            fixture.finish();
            onEdt(() -> {
                fixture.area.setText("missingCall(otherCall(, otherArgument);");
                int caret = fixture.area.getText().indexOf(',');
                fixture.area.insert(")", caret);
                fixture.area.setCaretPosition(caret + 1);
                return null;
            });
            fixture.finish();
            assertEquals(0, errors(fixture));
            onEdt(() -> { fixture.area.setCaretPosition(fixture.area.getDocument().getLength()); return null; });
            assertTrue(errors(fixture) > 0);
        }
    }

    @Test void sameLineBoundariesIgnoreDelimitersInsideStringsAndForHeaders() throws Exception {
        try (var fixture = new Fixture("return 1;")) {
            fixture.finish();
            onEdt(() -> {
                fixture.area.setText("int wrong = \"; }\"; for (int i = 0; i < missingLimit; i++) { unknown.");
                fixture.area.setCaretPosition(fixture.area.getDocument().getLength());
                return null;
            });
            fixture.finish();
            var notices = onEdt(() -> fixture.area.getParserNotices().stream()
                    .filter(n -> n.getLevel() == ParserNotice.Level.ERROR).toList());
            assertTrue(notices.stream().anyMatch(n -> n.getMessage().contains("String")));
            assertTrue(notices.stream().anyMatch(n -> n.getMessage().contains("missingLimit")));
            assertTrue(notices.stream().noneMatch(n -> n.getMessage().contains("unknown")));
        }
    }

    @Test void typingDelimiterCharactersInsideAStringDoesNotCommitTheEditedStatement() throws Exception {
        try (var fixture = new Fixture("int wrong = \"text\";")) {
            fixture.finish();
            assertEquals(1, errors(fixture));
            onEdt(() -> {
                int offset = fixture.area.getText().indexOf("text") + 4;
                fixture.area.insert(")", offset);
                fixture.area.setCaretPosition(offset + 1);
                return null;
            });
            fixture.finish();
            assertEquals(0, errors(fixture));
            onEdt(() -> { fixture.area.setCaretPosition(fixture.area.getDocument().getLength()); return null; });
            assertEquals(1, errors(fixture));
        }
    }

    private static long errors(Fixture fixture) throws Exception {
        return onEdt(() -> fixture.area.getParserNotices().stream().filter(n -> n.getLevel() == ParserNotice.Level.ERROR).count());
    }

    @Test void enterAtEveryLineEndKeepsUnrelatedImportWarningsBeforeAndAfterAnalysis() throws Exception {
        String source = IMPORTS + "\nreturn 1;\n";
        for (int line = 0; line < source.split("\n", -1).length - 1; line++) {
            int selectedLine = line;
            try (var fixture = new Fixture(source)) {
                fixture.finish();
                assertEquals(3, unused(fixture));
                onEdt(() -> {
                    int offset = fixture.area.getLineEndOffset(selectedLine) - 1;
                    fixture.area.insert("\n", offset);
                    fixture.area.setCaretPosition(offset + 1);
                    assertEquals(3, fixture.area.getParserNotices().stream().filter(n -> n.getMessage().contains("never used")).count(),
                            "Enter on line " + selectedLine + " must not publish an empty intermediate state");
                    return null;
                });
                fixture.finish();
                assertEquals(3, unused(fixture));
            }
        }
    }

    @Test void malformedOneImportDoesNotDiscardWarningsOnOtherImports() throws Exception {
        try (var fixture = new Fixture(IMPORTS + "\n")) {
            fixture.finish();
            assertEquals(3, unused(fixture));
            onEdt(() -> {
                int offset = IMPORTS.lastIndexOf(';');
                fixture.area.replaceRange("", offset, offset + 1);
                fixture.area.setCaretPosition(offset);
                return null;
            });
            assertTrue(unused(fixture) >= 2, "The two untouched imports remain marked while analysis is pending");
            fixture.finish();
            assertTrue(unused(fixture) >= 2);
        }
    }

    @Test void malformedImportCannotSwallowTheFollowingDeclarationAndItsSemanticColors() throws Exception {
        String body = "String text = \"stable\";\ntext.length();";
        try (var fixture = new Fixture(IMPORTS + body)) {
            fixture.finish();
            assertEquals(Token.FUNCTION, onEdt(() -> token(fixture.area, "length")));
            onEdt(() -> {
                int offset = IMPORTS.lastIndexOf(';');
                fixture.area.replaceRange("", offset, offset + 1);
                fixture.area.setCaretPosition(offset);
                return null;
            });
            fixture.finish();
            assertEquals(Token.FUNCTION, onEdt(() -> token(fixture.area, "length")), "An import error cannot erase unrelated method coloring");
            assertTrue(onEdt(() -> fixture.area.getParserNotices().stream().noneMatch(n -> n.getMessage().contains("text cannot be resolved"))));
        }
    }

    @Test void replacingImportSectionKeepsWarningsForUnchangedDeclarationsDuringIncompleteCode() throws Exception {
        try (var fixture = new Fixture(IMPORTS + "Gas")) {
            // Start from a reliable warning result before entering the incomplete type, as in the recording.
            onEdt(() -> { fixture.area.replaceRange("", IMPORTS.length(), fixture.area.getDocument().getLength()); return null; });
            fixture.finish();
            assertEquals(3, unused(fixture));
            onEdt(() -> {
                fixture.area.append("Gas");
                fixture.area.setCaretPosition(fixture.area.getDocument().getLength());
                return null;
            });
            fixture.finish();
            onEdt(() -> {
                fixture.area.replaceRange(IMPORTS + "import java.util.List;\n", 0, IMPORTS.length());
                assertEquals(3, fixture.area.getParserNotices().stream().filter(n -> n.getMessage().contains("never used")).count());
                return null;
            });
            fixture.finish();
            assertTrue(unused(fixture) >= 3);
        }
    }

    private static long unused(Fixture fixture) throws Exception {
        return onEdt(() -> fixture.area.getParserNotices().stream().filter(n -> n.getMessage().contains("never used")).count());
    }

    @Test void harmlessEditsNeverClearTheWarningAndItsOffsetsFollowTheDocument() throws Exception {
        try (var fixture = new Fixture(VALID)) {
            fixture.finish();
            onEdt(() -> {
                assertEquals(1, fixture.area.getParserNotices().size());
                int before = fixture.area.getParserNotices().getFirst().getOffset();
                fixture.area.insert("\n", 0);
                assertEquals(1, fixture.area.getParserNotices().size());
                assertEquals(before + 1, fixture.area.getParserNotices().getFirst().getOffset());
                assertNull(fixture.owner.currentSnapshot());
                assertNull(fixture.cache.getSnapshot("proof"));
                fixture.area.append(" ");
                assertEquals(1, fixture.area.getParserNotices().size());
                return null;
            });
            fixture.finish();
            assertEquals(1, onEdt(() -> fixture.area.getParserNotices().size()));
        }
    }

    @Test void dotAndPartialMemberKeepReceiverColorsAndUnusedImportsWithoutTypingErrors() throws Exception {
        try (var fixture = new Fixture(VALID)) {
            fixture.finish();
            onEdt(() -> {
                fixture.area.replaceRange("", VALID.lastIndexOf("start"), VALID.length());
                assertEquals(1, fixture.area.getParserNotices().size(), "No new typing error before analysis");
                return null;
            });
            fixture.finish();
            onEdt(() -> {
                assertEquals(ShadowedTokenTypes.CONSTANT, token(fixture.area, "BUS"));
                assertTrue(fixture.area.getParserNotices().stream().anyMatch(n -> n.getMessage().contains("never used")));
                fixture.area.append("listene");
                return null;
            });
            fixture.finish();
            onEdt(() -> {
                assertEquals(ShadowedTokenTypes.CONSTANT, token(fixture.area, "BUS"));
                assertTrue(fixture.area.getParserNotices().stream().noneMatch(n -> n.getLevel() == ParserNotice.Level.ERROR));
                fixture.area.append("rs.toString();");
                return null;
            });
            fixture.finish();
            onEdt(() -> {
                assertEquals(1, fixture.area.getParserNotices().size(), fixture.area.getParserNotices().toString());
                assertEquals(Token.VARIABLE, token(fixture.area, "listeners"));
                return null;
            });
        }
    }

    @Test void onlyOneParseRunsAndOnlyTheLatestPendingRevisionMayPublish() throws Exception {
        try (var fixture = new Fixture(VALID)) {
            assertEquals(1, fixture.work.size());
            onEdt(() -> {
                for (int i = 0; i < 20; i++) { fixture.area.append(" "); fixture.owner.requestNow(); }
                assertEquals(1, fixture.work.size());
                assertNull(fixture.owner.currentSnapshot());
                return null;
            });
            fixture.runOne();
            assertNull(onEdt(fixture.owner::currentSnapshot));
            assertEquals(1, fixture.work.size());
            fixture.runOne();
            assertEquals(onEdt(fixture.area::getText), onEdt(fixture.owner::currentSnapshot).contents());
            assertEquals(2, fixture.parses);
        }
    }

    @Test void lateResultCannotReachAClosedEditorOrAReplacementWithTheSameKey() throws Exception {
        var fixture = new Fixture(VALID);
        onEdt(() -> { fixture.owner.close(); return null; });
        var replacement = fixture.cache.register("proof", () -> {});
        fixture.runOne();
        assertEquals(0, fixture.parses, "Closed queued work must not start parsing");
        assertNull(fixture.cache.getSnapshot("proof"));
        replacement.close();
        fixture.close();
    }

    @Test void environmentReplacementRejectsOldResultsAndReanalyzesUnchangedText() throws Exception {
        try (var fixture = new Fixture(VALID)) {
            onEdt(() -> {
                CompanionClassIndex.set(index);
                fixture.cache.refreshEnvironment();
                return null;
            });
            fixture.runOne();
            assertNull(onEdt(fixture.owner::currentSnapshot));
            fixture.runOne();
            assertSame(CompanionClassIndex.identity(), onEdt(fixture.owner::currentSnapshot).environment());
        }
    }

    @Test void parseFailureAndExecutorRejectionDoNotPublishEmptySuccessAndLaterEditsRecover() throws Exception {
        try (var fixture = new Fixture(VALID)) {
            fixture.finish();
            fixture.fail = true;
            onEdt(() -> { fixture.area.append(" "); return null; });
            fixture.finish();
            assertNull(onEdt(fixture.owner::currentSnapshot));
            assertEquals(1, onEdt(() -> fixture.area.getParserNotices().size()));
            fixture.fail = false;
            fixture.reject = true;
            onEdt(() -> { fixture.area.append(" "); fixture.owner.requestNow(); return null; });
            assertNull(onEdt(fixture.owner::currentSnapshot));
            fixture.reject = false;
            fixture.finish();
            assertNotNull(onEdt(fixture.owner::currentSnapshot));
        }
    }

    @Test void addingAnImportsFirstUseRemovesItsRetainedWarningEvenDuringIncompleteSyntax() throws Exception {
        try (var fixture = new Fixture(VALID)) {
            fixture.finish();
            onEdt(() -> { fixture.area.append("\nUnused."); return null; });
            assertTrue(onEdt(() -> fixture.area.getParserNotices().stream().noneMatch(n -> n.getMessage().contains("never used"))));
            fixture.finish();
            assertTrue(onEdt(() -> fixture.area.getParserNotices().stream().noneMatch(n -> n.getMessage().contains("never used"))));
        }
    }

    @Test void lexerWinsWhenACommentHidesPreviouslyColoredCode() throws Exception {
        try (var fixture = new Fixture(VALID)) {
            fixture.finish();
            onEdt(() -> { fixture.area.insert("/*", IMPORTS.length()); return null; });
            fixture.finish();
            assertNotEquals(ShadowedTokenTypes.CONSTANT, onEdt(() -> token(fixture.area, "BUS")));
            onEdt(() -> { fixture.area.replaceRange("", IMPORTS.length(), IMPORTS.length() + 2); return null; });
            fixture.finish();
            assertEquals(ShadowedTokenTypes.CONSTANT, onEdt(() -> token(fixture.area, "BUS")));
        }
    }

    @Test void joiningAnIdentifierByDeletingWhitespaceInvalidatesItsUnusedImportWarning() throws Exception {
        for (String separator : List.of(" ", "\n")) {
            try (var fixture = new Fixture(VALID)) {
                fixture.finish();
                onEdt(() -> { fixture.area.append("\nUn" + separator + "used."); return null; });
                onEdt(() -> {
                    int offset = fixture.area.getText().lastIndexOf("Un" + separator);
                    fixture.area.replaceRange("", offset + 2, offset + 2 + separator.length());
                    assertTrue(fixture.area.getParserNotices().stream().noneMatch(n -> n.getMessage().contains("never used")));
                    return null;
                });
                fixture.finish();
                assertTrue(onEdt(() -> fixture.area.getParserNotices().stream().noneMatch(n -> n.getMessage().contains("never used"))));
            }
        }
    }

    @Test void newlineExposingACommentedUseInvalidatesItsUnusedImportWarning() throws Exception {
        try (var fixture = new Fixture(VALID + "\n// Unused.")) {
            fixture.finish();
            assertEquals(1, onEdt(() -> fixture.area.getParserNotices().size()));
            onEdt(() -> {
                fixture.area.insert("\n", fixture.area.getText().indexOf("// ") + 3);
                assertTrue(fixture.area.getParserNotices().isEmpty());
                return null;
            });
            fixture.finish();
            assertTrue(onEdt(() -> fixture.area.getParserNotices().stream().noneMatch(n -> n.getMessage().contains("never used"))));
        }
    }

    @Test void compoundDocumentEventsAreCoalescedWithoutATimer() throws Exception {
        try (var fixture = new Fixture(VALID)) {
            fixture.finish();
            var submitted = new CountDownLatch(1);
            fixture.onSubmit = submitted;
            onEdt(() -> {
                for (int i = 0; i < 50; i++) fixture.area.append(" ");
                assertTrue(fixture.work.isEmpty(), "A compound EDT edit must not submit intermediate source");
                return null;
            });
            assertTrue(submitted.await(5, TimeUnit.SECONDS), "The next EDT turn admits the latest text without an idle deadline");
            assertEquals(1, fixture.work.size());
            fixture.runOne();
            assertNotNull(onEdt(fixture.owner::currentSnapshot));
        }
    }

    @Test void compoundCompletionEditsAndUndoPublishOnlyTheFinalSource() throws Exception {
        try (var fixture = new Fixture("return 1;")) {
            fixture.finish();
            int before = fixture.parses;
            onEdt(() -> {
                fixture.area.beginAtomicEdit();
                fixture.area.replaceRange(RECEIVER + ".start();", 0, fixture.area.getDocument().getLength());
                fixture.area.insert(IMPORTS, 0);
                fixture.area.endAtomicEdit();
                assertTrue(fixture.work.isEmpty());
                return null;
            });
            fixture.finish();
            assertEquals(before + 1, fixture.parses);
            assertEquals(VALID, onEdt(fixture.owner::currentSnapshot).contents());
            onEdt(() -> { fixture.area.undoLastAction(); return null; });
            fixture.finish();
            assertEquals("return 1;", onEdt(fixture.owner::currentSnapshot).contents());
            onEdt(() -> { fixture.area.redoLastAction(); return null; });
            fixture.finish();
            assertEquals(VALID, onEdt(fixture.owner::currentSnapshot).contents());
        }
    }

    @Test void firstOpenWithIncompleteSyntaxNeedsNoPreviousSuccessfulResult() throws Exception {
        try (var fixture = new Fixture(IMPORTS + RECEIVER + ".listene")) {
            fixture.finish();
            onEdt(() -> {
                assertNotNull(fixture.owner.currentSnapshot());
                assertTrue(fixture.area.getParserNotices().stream().anyMatch(n -> n.getMessage().contains("listene")));
                assertEquals(ShadowedTokenTypes.CONSTANT, token(fixture.area, "BUS"));
                return null;
            });
        }
    }

    @Test void staticAndWildcardImportsDoNotKeepFalseWarningsWhenTheirFirstUseIsTyped() throws Exception {
        for (String imported : List.of("import " + JavaEditorAnalysisTest.class.getCanonicalName() + ".*;",
                "import static " + Holder.class.getCanonicalName() + ".BUS;")) {
            try (var fixture = new Fixture(imported + "\nreturn 1;")) {
                fixture.finish();
                assertTrue(onEdt(() -> fixture.area.getParserNotices().stream().anyMatch(n -> n.getMessage().contains("never used"))));
                onEdt(() -> {
                    fixture.area.append(imported.contains("static") ? "\nBUS." : "\nBus.");
                    return null;
                });
                fixture.finish();
                assertTrue(onEdt(() -> fixture.area.getParserNotices().stream().noneMatch(n -> n.getMessage().contains("never used"))));
            }
        }
    }

    @Test void deletingTheLastImportUseProducesANewWarningAfterAnalysis() throws Exception {
        String imported = "import " + Unused.class.getCanonicalName() + ";\n";
        try (var fixture = new Fixture(imported + "return new Unused();")) {
            fixture.finish();
            assertTrue(onEdt(() -> fixture.area.getParserNotices().isEmpty()));
            onEdt(() -> { fixture.area.replaceRange("return 1;", imported.length(), fixture.area.getDocument().getLength()); return null; });
            fixture.finish();
            assertEquals(1, onEdt(() -> fixture.area.getParserNotices().size()));
            assertTrue(onEdt(() -> fixture.area.getParserNotices().getFirst().getMessage().contains("never used")));
        }
    }

    @Test void freshAnalysisRemovesAColorWhenTheSameNameChangesFromAFieldToALocal() throws Exception {
        try (var fixture = new Fixture("class Local { int value; int get() { return value; } }\nreturn new Local().get();")) {
            fixture.finish();
            assertEquals(Token.VARIABLE, onEdt(() -> token(fixture.area, "value")));
            onEdt(() -> {
                fixture.area.insert("int value = 1; ", fixture.area.getText().indexOf("return value"));
                return null;
            });
            fixture.finish();
            assertEquals(Token.IDENTIFIER, onEdt(() -> token(fixture.area, "value")));
        }
    }

    @Test void equalIdentifierCountsDoNotHideANewImportUse() throws Exception {
        String header = "import " + Unused.class.getCanonicalName() + ";\n";
        for (boolean keepName : List.of(false, true)) {
            try (var fixture = new Fixture(header + "int Unused = 1;")) {
                fixture.finish();
                assertEquals(1, unused(fixture));
                onEdt(() -> {
                    if (keepName) {
                        fixture.area.replaceRange(".", header.length() + "int Unused".length(), fixture.area.getDocument().getLength());
                        fixture.area.replaceRange("", header.length(), header.length() + 4);
                    } else fixture.area.replaceRange("Unused.", header.length(), fixture.area.getDocument().getLength());
                    assertEquals(0, unused(fixture));
                    return null;
                });
                fixture.finish();
                assertEquals(0, unused(fixture));
            }
        }
    }

    @Test void realEnterActionPreservesWarningsAndColorsAcrossEveryLine() throws Exception {
        String source = IMPORTS + "\nString text = \"stable\";\ntext.length();\n";
        for (int offset = 0; offset <= source.length(); offset++) {
            if (offset != 0 && source.charAt(offset - 1) != '\n' && (offset == source.length() || source.charAt(offset) != '\n')) continue;
            final int caret = offset;
            try (var fixture = new Fixture(source)) {
                fixture.finish();
                onEdt(() -> {
                    fixture.area.setCaretPosition(caret);
                    var action = fixture.area.getActionMap().get(fixture.area.getInputMap().get(KeyStroke.getKeyStroke("ENTER")));
                    action.actionPerformed(new ActionEvent(fixture.area, ActionEvent.ACTION_PERFORMED, "Enter"));
                    assertEquals(source.length() + 1, fixture.area.getDocument().getLength(), "Enter must insert even at document start");
                    assertEquals(3, unused(fixture), "Pending Enter at " + caret);
                    assertEquals(Token.FUNCTION, token(fixture.area, "length"));
                    return null;
                });
                fixture.finish();
                assertEquals(3, unused(fixture), "Settled Enter at " + caret);
                assertEquals(Token.FUNCTION, onEdt(() -> token(fixture.area, "length")));
            }
        }
    }

    @Test void paintedWarningPixelsNeverDisappearWhileEnterAnalysisIsPending() throws Exception {
        try (var fixture = new Fixture(IMPORTS + "\nreturn 1;")) {
            fixture.finish();
            onEdt(() -> {
                fixture.area.setSize(1300, 400);
                fixture.area.setHighlightCurrentLine(false);
                fixture.area.getCaret().setVisible(false);
                return null;
            });
            int[] before = onEdt(() -> warningPixels(fixture.area));
            onEdt(() -> {
                fixture.area.setCaretPosition(fixture.area.getDocument().getLength());
                var action = fixture.area.getActionMap().get(fixture.area.getInputMap().get(KeyStroke.getKeyStroke("ENTER")));
                action.actionPerformed(new ActionEvent(fixture.area, ActionEvent.ACTION_PERFORMED, "Enter"));
                assertArrayEquals(before, warningPixels(fixture.area), "The next paint must still contain all underlines before the worker runs");
                return null;
            });
            fixture.finish();
            assertArrayEquals(before, onEdt(() -> warningPixels(fixture.area)), "Accepted analysis must preserve identical warning pixels");
        }
    }

    private static int[] warningPixels(RSyntaxTextArea area) throws Exception {
        var image = new BufferedImage(area.getWidth(), area.getHeight(), BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try { area.paint(graphics); } finally { graphics.dispose(); }
        var notice = area.getParserNotices().stream().filter(n -> n.getMessage().contains("never used")).findFirst().orElseThrow();
        var bounds = area.modelToView2D(notice.getOffset());
        int top = (int) bounds.getY();
        int height = (int) bounds.getHeight();
        int[] pixels = image.getRGB(0, top, image.getWidth(), height, null, 0, image.getWidth());
        assertTrue(Arrays.stream(pixels).anyMatch(color -> color == notice.getColor().getRGB()),
                "Rendered warning color must exist in the image, not just in the notice list");
        return pixels;
    }

    @Test void acceptingACompleteCallMustNotRevealDiagnosticsFromThePreviousTrailingDot() throws Exception {
        try (var fixture = new Fixture(VALID)) {
            fixture.finish();
            onEdt(() -> {
                fixture.area.replaceRange("", VALID.indexOf("start"), VALID.length());
                fixture.area.setCaretPosition(fixture.area.getDocument().getLength());
                return null;
            });
            fixture.finish();
            assertEquals(0, errors(fixture));
            onEdt(() -> {
                fixture.area.append("start();");
                fixture.area.setCaretPosition(fixture.area.getDocument().getLength());
                fixture.owner.completionAccepted();
                assertEquals(0, errors(fixture), "Completion cannot expose stale trailing-dot diagnostics while the new result is pending");
                return null;
            });
            fixture.finish();
            assertEquals(0, errors(fixture));
        }
    }

    private static int token(RSyntaxTextArea area, String name) {
        int offset = area.getText().lastIndexOf(name);
        for (var token : (RSyntaxDocument) area.getDocument()) {
            if (token.isPaintable() && token.containsPosition(offset)) return token.getType();
        }
        return -1;
    }

    private static <T> T onEdt(Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return action.call();
        var task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    private static final class Fixture implements AutoCloseable {
        final ASTCache cache = new ASTCache();
        final ArrayDeque<Runnable> work = new ArrayDeque<>();
        final RSyntaxTextArea area;
        final JavaEditorAnalysis owner;
        int parses;
        boolean fail;
        boolean reject;
        volatile CountDownLatch onSubmit;

        Fixture(String text) throws Exception {
            area = onEdt(() -> {
                var editor = new RSyntaxTextArea() {
                    @Override public Graphics getGraphics() {
                        // RSyntax asks its component for font metrics before its first offscreen paint.
                        return new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics();
                    }
                };
                editor.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JAVA);
                ((RSyntaxDocument) editor.getDocument()).setSyntaxStyle(new CustomJavaTokenMaker());
                editor.setText(text);
                return editor;
            });
            owner = onEdt(() -> new JavaEditorAnalysis(area, cache, "proof", task -> {
                if (reject) throw new RejectedExecutionException("test rejection");
                work.add(task);
                if (onSubmit != null) onSubmit.countDown();
            }, true, request -> {
                parses++;
                if (fail) throw new IllegalStateException("test failure");
                return JavaAnalysis.parse("Proof", request.text(), JavaSnippetSource.body("Proof", request.text()).editorSource(),
                        request.revision(), request.environment());
            }));
            onEdt(() -> null);
        }

        void runOne() throws Exception { work.remove().run(); onEdt(() -> null); }
        void finish() throws Exception {
            onEdt(() -> { owner.requestNow(); return null; });
            while (!work.isEmpty()) runOne();
        }
        @Override public void close() throws Exception { onEdt(() -> { owner.close(); return null; }); }
    }
}
