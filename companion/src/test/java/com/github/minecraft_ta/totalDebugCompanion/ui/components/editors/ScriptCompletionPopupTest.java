package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaSnippetSource;
import com.github.minecraft_ta.totalDebugCompanion.jdt.completion.CompletionItem;
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
import java.awt.Component;
import java.awt.DefaultKeyboardFocusManager;
import java.awt.KeyboardFocusManager;
import java.awt.event.ActionEvent;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
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
        index = JavaAnalysisFixtures.index(ScriptCompletionPopupTest.class, Values.class, ArrayList.class, Map.class, List.class, Path.class, Optional.class, ConcurrentHashMap.class);
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
    private void press(int key) throws Exception {
        edt(() -> {
            var event = new KeyEvent(editor, KeyEvent.KEY_PRESSED, 0, 0, key, KeyEvent.CHAR_UNDEFINED);
            for (var listener : editor.getKeyListeners()) listener.keyPressed(event);
            if (!event.isConsumed()) {
                var binding = editor.getInputMap().get(KeyStroke.getKeyStroke(key, 0));
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
