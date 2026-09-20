package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaSnippetSource;
import com.github.minecraft_ta.totalDebugCompanion.jdt.completion.CompletionItem;
import com.github.minecraft_ta.totalDebugCompanion.jdt.completion.CompletionItemKind;
import com.github.minecraft_ta.totalDebugCompanion.jdt.completion.CustomTextEdit;
import com.github.minecraft_ta.totalDebugCompanion.jdt.completion.Range;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.ASTCache;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.JavaAnalysis;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.JavaAnalysisFixtures;
import com.github.minecraft_ta.totalDebugCompanion.jdt.semanticHighlighting.CustomJavaTokenMaker;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.CodeCompletionPopup;
import com.github.tth05.jindex.ClassIndex;
import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.TokenTypes;
import org.fife.ui.rsyntaxtextarea.parser.ParserNotice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import javax.swing.text.BadLocationException;
import java.awt.Component;
import java.awt.DefaultKeyboardFocusManager;
import java.awt.KeyboardFocusManager;
import java.awt.event.ActionEvent;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.SequencedCollection;
import java.util.Set;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import static org.junit.jupiter.api.Assertions.*;

/** Real controller, JDT and key handlers; only worker delivery and native window/focus are controlled. */
public class ScriptCompletionPopupTest {
    public static class Values { public Map<String, List<Path>> entries; }
    private final ArrayDeque<Runnable> completionWork = new ArrayDeque<>(), analysisWork = new ArrayDeque<>();
    private final ASTCache cache = new ASTCache();
    private ClassIndex index;
    private RSyntaxTextArea editor;
    private JavaEditorAnalysis analysis;
    private ScriptCompletionController completion;
    private TestPopup popup;
    private KeyboardFocusManager previousFocus;
    private boolean fail, reject;
    private Runnable duringCompute;

    @BeforeEach void open() throws Exception {
        index = JavaAnalysisFixtures.index(ScriptCompletionPopupTest.class, Values.class, ArrayList.class, Iterable.class, Iterator.class, Collection.class, SequencedCollection.class, Set.class, Map.class, Map.Entry.class, List.class, Path.class, Optional.class, ConcurrentHashMap.class);
        CompanionClassIndex.set(index);
        edt(() -> {
            editor = new RSyntaxTextArea();
            editor.setSyntaxEditingStyle(RSyntaxTextArea.SYNTAX_STYLE_JAVA);
            ((RSyntaxDocument) editor.getDocument()).setSyntaxStyle(new CustomJavaTokenMaker());
            editor.setText("String text = \"x\"; text.l");
            editor.setCaretPosition(editor.getDocument().getLength());
            analysis = new JavaEditorAnalysis(editor, cache, "Proof", analysisWork::add, true, request ->
                    JavaAnalysis.parse("Proof", request.text(), JavaSnippetSource.body("Proof", request.text()).editorSource(), request.revision(), request.environment()));
            popup = new TestPopup();
            previousFocus = KeyboardFocusManager.getCurrentKeyboardFocusManager();
            KeyboardFocusManager.setCurrentKeyboardFocusManager(new DefaultKeyboardFocusManager() {
                @Override public Component getFocusOwner() { return editor; }
            });
            completion = new ScriptCompletionController(editor, "Proof", popup, task -> {
                if (reject) throw new RejectedExecutionException("test rejection");
                completionWork.add(task);
            }, analysis::completionAccepted, request -> {
                if (duringCompute != null) { var action = duringCompute; duringCompute = null; action.run(); }
                if (fail) throw new IllegalStateException("test computation failure");
                return request.compute();
            });
            return null;
        });
    }

    @AfterEach void close() throws Exception {
        edt(() -> { completion.close(); analysis.close(); KeyboardFocusManager.setCurrentKeyboardFocusManager(previousFocus); return null; });
        CompanionClassIndex.clear(); index.close();
    }

    @Test void completionImportRewriteKeepsWarningsThroughAcceptanceUndoAndRedo() throws Exception {
        String source = "import java.util.List;\nimport java.util.Map;\nimport java.util.concurrent.ConcurrentHashMap;\n\nString text = \"stable\";\ntext.length();\n";
        setText(source); analyzed();
        assertEquals(3, unused());
        setText(source + "Optio"); analyzed(); request(); complete(); select("Optional"); press(KeyEvent.VK_ENTER);
        assertEquals(3, unused(), "Import rewrite must preserve warnings before analysis");
        analyzed(); assertEquals(3, unused());
        edt(() -> { editor.undoLastAction(); return null; });
        assertEquals(3, unused()); analyzed(); assertEquals(3, unused());
        edt(() -> { editor.redoLastAction(); return null; });
        assertEquals(3, unused()); analyzed(); assertEquals(3, unused());
        edt(() -> { editor.append(".EMPTY;"); return null; });
        analyzed(); assertEquals(3, unused());
        setText(source); analyzed();
        edt(() -> { int semicolon = editor.getText().indexOf("ConcurrentHashMap;") + "ConcurrentHashMap".length(); editor.replaceRange("", semicolon, semicolon + 1); return null; });
        assertEquals(2, unused()); analyzed(); assertEquals(2, unused());
    }

    private long unused() throws Exception {
        return edt(() -> {
            int offset = editor.getText().indexOf("length()");
            var token = editor.getTokenListForLine(editor.getLineOfOffset(offset));
            while (token != null && !token.containsPosition(offset)) token = token.getNextToken();
            assertNotNull(token);
            assertEquals(TokenTypes.FUNCTION, token.getType());
            return editor.getParserNotices().stream().filter(notice -> notice.getMessage().contains("never used")).count();
        });
    }

    @Test void enterWhileRefreshIsPendingAcceptsFreshRangesExactlyOnce() throws Exception {
        request(); complete(); select("length");
        edt(() -> { editor.replaceSelection("e"); return null; });
        String pending = edt(editor::getText);
        press(KeyEvent.VK_ENTER);
        assertEquals(pending, edt(editor::getText));
        complete();
        assertTrue(edt(() -> editor.getText().endsWith("text.length()")));
        assertFalse(edt(popup::isVisible));
        edt(() -> { editor.undoLastAction(); return null; });
        assertEquals(pending, edt(editor::getText));
    }

    @Test void initialEscapeCancelsWorkAndANewRequestCanStillOpen() throws Exception {
        request(); press(KeyEvent.VK_ESCAPE); complete();
        assertFalse(edt(popup::isVisible));
        request(); complete(); assertTrue(edt(popup::isVisible));
    }

    @Test void escapeDiscardsQueuedAcceptanceAndLateRefresh() throws Exception {
        request(); complete(); request();
        press(KeyEvent.VK_ENTER);
        String before = edt(editor::getText);
        press(KeyEvent.VK_ESCAPE); complete();
        assertFalse(edt(popup::isVisible)); assertEquals(before, edt(editor::getText));
    }

    @Test void typingAfterQueuedEnterInvalidatesThatIntent() throws Exception {
        request(); complete(); select("length"); request(); press(KeyEvent.VK_ENTER);
        edt(() -> { editor.replaceSelection("e"); return null; });
        complete();
        assertTrue(edt(popup::isVisible));
        assertTrue(edt(() -> editor.getText().endsWith("text.le")));
        select("length"); press(KeyEvent.VK_TAB);
        assertTrue(edt(() -> editor.getText().endsWith("text.length()")));
    }

    @Test void latestFailureAndExecutorRejectionReleaseEnterAndAllowRetry() throws Exception {
        for (boolean rejected : List.of(false, true)) {
            setText("String text = \"x\"; text.l");
            request(); complete();
            fail = !rejected; reject = rejected;
            request(); complete();
            assertFalse(edt(popup::isVisible));
            String before = edt(editor::getText);
            press(KeyEvent.VK_ENTER);
            assertEquals(before + "\n", edt(editor::getText));
            fail = reject = false;
            setText(before); request(); complete(); select("length"); press(KeyEvent.VK_ENTER);
            assertTrue(edt(() -> editor.getText().endsWith("text.length()")));
        }
    }

    @Test void staleSuccessCannotReplaceANewerRequest() throws Exception {
        request();
        duringCompute = () -> {
            try { setText("String text = \"x\"; text.isEm"); request(); }
            catch (Exception error) { throw new AssertionError(error); }
        };
        complete();
        select("isEmpty"); press(KeyEvent.VK_ENTER);
        assertTrue(edt(() -> editor.getText().endsWith("text.isEmpty()")));
    }

    @Test void caretFocusEnvironmentAndDisposalInvalidatePendingWork() throws Exception {
        for (int change = 0; change < 4; change++) {
            request();
            int selected = change;
            edt(() -> {
                switch (selected) {
                    case 0 -> editor.setCaretPosition(0);
                    case 1 -> { for (var listener : editor.getFocusListeners()) listener.focusLost(new FocusEvent(editor, FocusEvent.FOCUS_LOST)); }
                    case 2 -> CompanionClassIndex.set(index);
                    case 3 -> completion.close();
                    default -> throw new AssertionError();
                }
                return null;
            });
            complete(); assertFalse(edt(popup::isVisible));
            edt(() -> { editor.setCaretPosition(editor.getDocument().getLength()); return null; });
        }
    }

    @Test void externalPopupDismissalCancelsRefreshAndQueuedAcceptance() throws Exception {
        for (boolean accept : List.of(false, true)) {
            setText("String text = \"x\"; text.l"); request(); complete(); select("length");
            edt(() -> { editor.replaceSelection("e"); return null; });
            if (accept) press(KeyEvent.VK_ENTER);
            String pending = edt(editor::getText);
            edt(() -> { popup.setVisible(false); return null; });
            complete();
            assertFalse(edt(popup::isVisible));
            assertEquals(pending, edt(editor::getText));
        }
    }

    @Test void deletionWithoutCaretMovementInvalidatesVisibleRanges() throws Exception {
        setText("String text = \"x\"; text.l;");
        edt(() -> { editor.setCaretPosition(editor.getDocument().getLength() - 1); return null; });
        request(); complete();
        int caret = edt(editor::getCaretPosition);
        edt(() -> { editor.getDocument().remove(caret, 1); return null; });
        assertEquals(caret, edt(editor::getCaretPosition)); assertFalse(edt(popup::isVisible));
    }

    @Test void constructorRefreshPublishesTheFinalListOnce() throws Exception {
        setText("new S"); request(); complete();
        edt(() -> { editor.replaceSelection("t"); return null; }); complete();
        assertTrue(edt(popup::isVisible)); select("String"); press(KeyEvent.VK_ENTER);
        String completed = edt(editor::getText);
        assertTrue(completed.startsWith("new String("), completed);
    }

    @Test void genericConstructorKeepsDiamondAndImportsItsType() throws Exception {
        setText("new ArrayL"); request(); complete(); select("ArrayList"); press(KeyEvent.VK_ENTER);
        String completed = edt(editor::getText);
        assertTrue(completed.contains("import java.util.ArrayList;"), completed);
        assertTrue(completed.contains("new ArrayList<>("), completed);
    }

    @Test void postfixVarImportsNestedTypesAndKeepsTheNameSelected() throws Exception {
        setText("import " + Values.class.getCanonicalName() + ";\n\n((Values) null).entries.var");
        request(); complete(); select("var"); press(KeyEvent.VK_ENTER);
        edt(() -> {
            String text = editor.getText();
            for (String imported : List.of("java.util.Map", "java.util.List", "java.nio.file.Path")) assertTrue(text.contains("import " + imported + ";"), text);
            assertTrue(text.contains("Map<String,List<Path>> name = ((Values) null).entries;"), text);
            assertEquals("name", editor.getSelectedText()); return null;
        });
        analyzed(); assertTrue(edt(() -> editor.getParserNotices().stream().noneMatch(n -> n.getLevel() == ParserNotice.Level.ERROR)));
    }

    @Test void formattingPreservesArgumentNavigationAndDisposalRestoresBindings() throws Exception {
        setText("String text=\"x\"; text.subst"); request(); complete(); select("substring"); press(KeyEvent.VK_ENTER);
        String argument = edt(editor::getSelectedText);
        assertNotNull(argument);
        edt(() -> {
            int equals = editor.getText().indexOf('=');
            completion.applyEdits(List.of(new CustomTextEdit(new Range(equals, 1), " = ")));
            return null;
        });
        assertEquals(argument, edt(editor::getSelectedText));
        String beforeTab = edt(editor::getText);
        press(KeyEvent.VK_TAB);
        assertEquals(beforeTab, edt(editor::getText), "Tab navigates the preserved snippet instead of inserting whitespace");
        setText("String text=\"x\"; text.subst"); request(); complete(); select("substring"); press(KeyEvent.VK_ENTER);
        assertNotNull(edt(editor::getSelectedText));
        edt(() -> {
            completion.close();
            assertNotEquals("SnippetCompletionAdapter.snippetNextAction", editor.getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0)));
            assertNotEquals("SnippetCompletionAdapter.snippetNextAction", editor.getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)));
            return null;
        });
    }

    @Test void postfixLoopIsOneUndoableEditAndLinksTheIndexName() throws Exception {
        String original = "int[] values = {1, 2}; values.fori";
        setText(original); request(); complete(); select("fori"); press(KeyEvent.VK_ENTER);
        String expanded = edt(editor::getText);
        assertEquals("i", edt(editor::getSelectedText));
        edt(() -> { editor.undoLastAction(); return null; });
        assertEquals(original, edt(editor::getText));
        request(); complete(); select("fori"); press(KeyEvent.VK_ENTER);
        assertEquals(expanded, edt(editor::getText));
        edt(() -> { editor.replaceSelection("index"); return null; });
        edt(() -> null);
        String renamed = edt(editor::getText);
        assertTrue(renamed.contains("int index = 0; index < values.length; index++"), renamed);
        press(KeyEvent.VK_TAB);
        edt(() -> { editor.replaceSelection("logln(values[index]);"); return null; });
        analyzed();
        assertTrue(edt(() -> editor.getParserNotices().stream().noneMatch(n -> n.getLevel() == ParserNotice.Level.ERROR)));
    }

    @Test void postfixForeachImportsNestedGenericElementsAndKeepsTheNameSelected() throws Exception {
        setText("import " + Values.class.getCanonicalName() + ";\n\n((Values) null).entries.entrySet().for");
        request(); complete(); select("for"); press(KeyEvent.VK_ENTER);
        assertEquals("entry", edt(editor::getSelectedText));
        String text = edt(editor::getText);
        for (String imported : List.of("java.util.Map.Entry", "java.util.List", "java.nio.file.Path"))
            assertTrue(text.contains("import " + imported + ";"), text);
        assertTrue(text.contains("for (Entry<String,List<Path>> entry :"), text);
        press(KeyEvent.VK_TAB);
        edt(() -> { editor.replaceSelection("logln(entry);"); return null; });
        analyzed();
        assertTrue(edt(() -> editor.getParserNotices().stream().noneMatch(n -> n.getLevel() == ParserNotice.Level.ERROR)));
    }

    @Test void literalPostfixUsesItsActualTokenAndDefersIncompleteBodyErrors() throws Exception {
        setText("10.fori"); request(); complete();
        assertEquals("fori", edt(() -> ((CompletionItem) popup.items().getModel().getElementAt(0)).getName()));
        press(KeyEvent.VK_ENTER);
        press(KeyEvent.VK_TAB);
        edt(() -> { editor.replaceSelection("unfin"); return null; }); analyzed();
        assertTrue(edt(() -> editor.getParserNotices().stream().noneMatch(n -> n.getLevel() == ParserNotice.Level.ERROR)));
        edt(() -> { editor.setCaretPosition(0); return null; });
        assertTrue(edt(() -> editor.getParserNotices().stream().anyMatch(n -> n.getLevel() == ParserNotice.Level.ERROR)));
    }

    @Test void acceptingVoidCallDoesNotFlashThePreviousDotError() throws Exception {
        setText("String text = \"x\"; text.length();"); analyzed();
        edt(() -> { int start = editor.getText().indexOf("length"); editor.replaceRange("", start, editor.getDocument().getLength()); editor.setCaretPosition(start); return null; });
        analyzed(); request(); complete(); select("notify"); press(KeyEvent.VK_ENTER);
        edt(() -> {
            assertTrue(editor.getText().endsWith("text.notify();"));
            assertEquals(editor.getDocument().getLength(), editor.getCaretPosition());
            assertTrue(editor.getParserNotices().stream().noneMatch(n -> n.getLevel() == ParserNotice.Level.ERROR)); return null;
        });
        analyzed(); assertTrue(edt(() -> editor.getParserNotices().isEmpty()));
    }

    @Test void completeStatementUsesCaretWithoutPopupAndIsOneUndoStep() throws Exception {
        setText("String text = \"x\";\ntext.length()");
        edt(() -> { editor.setCaretPosition(editor.getText().indexOf("length") + 2); editor.discardAllEdits(); return null; });
        String before = edt(editor::getText);
        press(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK);
        assertEquals(before, edt(editor::getText), "Planning must not make partial visible edits");
        complete();
        assertEquals(before + ";\n", edt(editor::getText));
        assertEquals(edt(() -> editor.getDocument().getLength()), edt(editor::getCaretPosition));
        edt(() -> { editor.undoLastAction(); return null; });
        assertEquals(before, edt(editor::getText));
        edt(() -> { editor.redoLastAction(); return null; });
        assertEquals(before + ";\n", edt(editor::getText));
    }

    @Test void completeStatementDismissesPopupWithoutAcceptingSuggestionOrLateRefresh() throws Exception {
        request(); complete(); select("length"); request();
        String before = edt(editor::getText);
        press(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK);
        assertFalse(edt(popup::isVisible));
        complete();
        assertEquals(before, edt(editor::getText), "A partial member name must not accept the highlighted method");
        assertFalse(edt(popup::isVisible));
    }

    @Test void completeStatementEndsSnippetWithoutReplacingSelectedArgument() throws Exception {
        setText("String text=\"x\"; text.subst"); request(); complete(); select("substring"); press(KeyEvent.VK_ENTER);
        String before = edt(editor::getText);
        assertNotNull(edt(editor::getSelectedText));
        press(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK); complete();
        assertEquals(before + ";\n", edt(editor::getText));
        assertNotEquals("SnippetCompletionAdapter.snippetNextAction", edt(() -> editor.getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0))));
    }

    @Test void completeStatementDropsStaleResultsAndHonorsReadOnly() throws Exception {
        for (int change = 0; change < 7; change++) {
            setText("text.length()");
            press(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK);
            int selected = change;
            edt(() -> {
                switch (selected) {
                    case 0 -> editor.append(";");
                    case 1 -> editor.setCaretPosition(0);
                    case 2 -> { for (var listener : editor.getFocusListeners()) listener.focusLost(new FocusEvent(editor, FocusEvent.FOCUS_LOST)); }
                    case 3 -> editor.setEditable(false);
                    case 4 -> { for (var listener : editor.getKeyListeners()) listener.keyPressed(new KeyEvent(editor, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED)); }
                    case 5 -> { editor.append("x"); editor.replaceRange("", editor.getDocument().getLength() - 1, editor.getDocument().getLength()); }
                    case 6 -> completion.close();
                    default -> throw new AssertionError();
                }
                return null;
            });
            String before = edt(editor::getText);
            int caret = edt(editor::getCaretPosition);
            complete();
            assertEquals(before, edt(editor::getText));
            assertEquals(caret, edt(editor::getCaretPosition));
            edt(() -> { editor.setEditable(true); return null; });
        }
    }

    @Test void completeStatementRetainsWarningsAndColorsDuringEachEdit() throws Exception {
        String source = "import java.util.List;\nimport java.util.Map;\n\nString text = \"stable\";\ntext.length();\ntext.length()";
        setText(source + ";"); analyzed();
        edt(() -> { editor.replaceRange("", source.length(), source.length() + 1); return null; });
        analyzed();
        assertEquals(2, unused());
        var observations = new ArrayList<Long>();
        edt(() -> {
            editor.addCaretListener(event -> {
                observations.add(editor.getParserNotices().stream().filter(n -> n.getMessage().contains("never used")).count());
                assertTrue(editor.getParserNotices().stream().noneMatch(n -> n.getLevel() == ParserNotice.Level.ERROR));
                int offset = editor.getText().indexOf("length()");
                try {
                    var token = editor.getTokenListForLine(editor.getLineOfOffset(offset));
                    while (token != null && !token.containsPosition(offset)) token = token.getNextToken();
                    assertNotNull(token);
                    assertEquals(TokenTypes.FUNCTION, token.getType());
                } catch (BadLocationException error) { throw new AssertionError(error); }
            });
            return null;
        });
        press(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK); complete();
        assertEquals(source + ";\n", edt(editor::getText));
        assertFalse(observations.isEmpty());
        assertTrue(observations.stream().allMatch(count -> count == 2), observations.toString());
        assertEquals(2, unused()); analyzed(); assertEquals(2, unused());
    }

    @Test void completeStatementWorksWithoutIndexAndRecoversFromRejectedWork() throws Exception {
        setText("call()");
        CompanionClassIndex.clear();
        reject = true;
        press(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK);
        assertEquals("call()", edt(editor::getText));
        reject = false;
        edt(() -> { editor.setEditable(false); return null; });
        press(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK);
        assertTrue(completionWork.isEmpty());
        edt(() -> { editor.setEditable(true); editor.select(0, 3); return null; });
        press(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK); complete();
        assertEquals("call();\n", edt(editor::getText));
    }

    @Test void completeStatementOpensConditionAndBodyWithEditorIndentation() throws Exception {
        setText("if");
        edt(() -> { editor.setTabsEmulated(true); editor.setTabSize(2); return null; });
        press(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK); complete();
        assertEquals("if () {\n  \n}", edt(editor::getText));
        assertEquals(4, edt(editor::getCaretPosition));
        analyzed();
        assertTrue(edt(() -> editor.getParserNotices().stream().noneMatch(n -> n.getLevel() == ParserNotice.Level.ERROR)));
        String before = edt(editor::getText);
        press(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK); complete();
        assertEquals(before, edt(editor::getText));
        edt(() -> { editor.replaceSelection("true"); return null; });
        press(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK); complete();
        assertEquals("if (true) {\n  \n}", edt(editor::getText));
        assertEquals("if (true) {\n  ".length(), edt(editor::getCaretPosition));
    }

    @Test void ifTemplateSelectsConditionThenMovesIntoTheBody() throws Exception {
        setText("if"); request(); complete(); select("if"); press(KeyEvent.VK_ENTER);
        assertEquals("condition", edt(editor::getSelectedText));
        edt(() -> { editor.replaceSelection("true"); return null; });
        press(KeyEvent.VK_TAB);
        assertEquals("if (true) {\n\t\n}", edt(editor::getText));
        assertEquals("if (true) {\n\t".length(), edt(editor::getCaretPosition));
    }

    @Test void statementTemplatesReplaceTheWholeKeywordAtAnInteriorCaret() throws Exception {
        for (String keyword : List.of("if", "for")) {
            setText(keyword);
            edt(() -> { editor.setCaretPosition(keyword.length() - 1); return null; });
            request(); complete(); select(keyword); press(KeyEvent.VK_ENTER);
            assertEquals(keyword.equals("if") ? "if (condition) {\n\t\n}" : "for (var item : items) {\n\t\n}", edt(editor::getText));
        }
    }

    @Test void existingHeadersDoNotOfferAnotherStatementTemplate() throws Exception {
        for (String source : List.of("if (true) {}", "for (;;) {}")) {
            String keyword = source.substring(0, source.indexOf(' '));
            for (int caret : List.of(keyword.length() - 1, keyword.length())) {
                setText(source);
                edt(() -> { editor.setCaretPosition(caret); return null; });
                request(); complete();
                edt(() -> {
                    var model = popup.items().getModel();
                    for (int i = 0; i < model.getSize(); i++) {
                        var item = (CompletionItem) model.getElementAt(i);
                        assertFalse(item.getName().equals(keyword) && item.getKind() == CompletionItemKind.SNIPPET);
                    }
                    return null;
                });
                assertEquals(source, edt(editor::getText));
            }
        }
    }

    @Test void completeStatementExpandsLambdaBodyAndPreservesTheCallInOneUndoStep() throws Exception {
        for (String before : List.of("name.keySet().forEach((object) -> );", "name.keySet().forEach((object) -> )")) {
            setText(before);
            edt(() -> { editor.setCaretPosition(before.indexOf("->") + 3); editor.discardAllEdits(); return null; });
            press(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK); complete();
            String after = "name.keySet().forEach((object) -> {\n\t\n});";
            assertEquals(after, edt(editor::getText));
            assertEquals(after.indexOf('\t') + 1, edt(editor::getCaretPosition));
            press(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK); complete();
            assertEquals(after, edt(editor::getText));
            edt(() -> { editor.undoLastAction(); return null; });
            assertEquals(before, edt(editor::getText));
            edt(() -> { editor.redoLastAction(); return null; });
            assertEquals(after, edt(editor::getText));
        }
    }

    @Test void forTemplateEditsCollectionThenVariableThenBody() throws Exception {
        setText("String[] values = null;\nfor"); request(); complete(); select("for"); press(KeyEvent.VK_ENTER);
        assertEquals("items", edt(editor::getSelectedText));
        edt(() -> { editor.replaceSelection("values"); return null; });
        press(KeyEvent.VK_TAB);
        assertEquals("item", edt(editor::getSelectedText));
        edt(() -> { editor.replaceSelection("value"); return null; });
        press(KeyEvent.VK_TAB);
        assertEquals("String[] values = null;\nfor (var value : values) {\n\t\n}", edt(editor::getText));
        assertEquals(edt(editor::getText).indexOf("\t") + 1, edt(editor::getCaretPosition));
    }

    private void setText(String text) throws Exception { edt(() -> { editor.setText(text); editor.setCaretPosition(text.length()); return null; }); }
    private void request() throws Exception { edt(() -> { editor.getActionMap().get("autoComplete").actionPerformed(new ActionEvent(editor, 0, "completion")); return null; }); }
    private void complete() throws Exception { edt(() -> null); while (!completionWork.isEmpty()) { completionWork.remove().run(); edt(() -> null); } }
    private void analyzed() throws Exception {
        edt(() -> { analysis.requestNow(); return null; });
        while (!analysisWork.isEmpty()) { analysisWork.remove().run(); edt(() -> null); }
    }
    private void select(String name) throws Exception {
        edt(() -> {
            var list = popup.items();
            for (int i = 0; i < list.getModel().getSize(); i++) if (((CompletionItem) list.getModel().getElementAt(i)).getName().equals(name)) { list.setSelectedIndex(i); return null; }
            throw new AssertionError("Missing completion " + name);
        });
    }
    private void press(int key) throws Exception { press(key, 0); }
    private void press(int key, int modifiers) throws Exception {
        edt(() -> {
            var event = new KeyEvent(editor, KeyEvent.KEY_PRESSED, 0, modifiers, key, KeyEvent.CHAR_UNDEFINED);
            for (var listener : editor.getKeyListeners()) listener.keyPressed(event);
            if (!event.isConsumed()) {
                var binding = editor.getInputMap().get(KeyStroke.getKeyStroke(key, modifiers));
                var action = binding == null ? null : editor.getActionMap().get(binding);
                if (action != null) action.actionPerformed(new ActionEvent(editor, 0, "key"));
            }
            return null;
        });
    }
    static final class TestPopup extends CodeCompletionPopup {
        private boolean shown;
        TestPopup() { super(null); }
        JList<?> items() { return (JList<?>) ((JScrollPane) getContentPane().getComponent(0)).getViewport().getView(); }
        @Override public boolean isVisible() { return shown; }
        @Override public void setVisible(boolean visible) { shown = visible; if (!visible) super.setVisible(false); }
        @Override public void show(JTextComponent editor) { bindInvoker(editor); shown = true; }
    }
    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action); SwingUtilities.invokeAndWait(task); return task.get();
    }
}
