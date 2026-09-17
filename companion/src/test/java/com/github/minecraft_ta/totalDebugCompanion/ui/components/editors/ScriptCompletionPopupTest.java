package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.CompanionApplication;
import com.github.minecraft_ta.totalDebugCompanion.GlobalConfig;
import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaSnippetSource;
import com.github.minecraft_ta.totalDebugCompanion.jdt.completion.CompletionItem;
import com.github.minecraft_ta.totalDebugCompanion.jdt.completion.CustomCompletionRequestor;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.JavaAnalysisFixtures;
import com.github.minecraft_ta.totalDebugCompanion.jdt.impls.CompilationUnitImpl;
import com.github.minecraft_ta.totalDebugCompanion.model.ScriptView;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionLaunchConfiguration;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.CodeCompletionPopup;
import com.github.tth05.jindex.ClassIndex;
import org.fife.ui.rsyntaxtextarea.parser.ParserNotice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JList;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.DefaultKeyboardFocusManager;
import java.awt.KeyboardFocusManager;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Runs real popup key handlers and JDT results; only native visibility/focus and worker delivery are controlled. */
public class ScriptCompletionPopupTest {
    public static class Values { public Map<String, List<Path>> entries; }
    @TempDir Path directory;
    private CompanionApplication app;
    private ClassIndex index;
    private ScriptPanel panel;
    private TestPopup popup;
    private KeyboardFocusManager previousFocus;

    @BeforeEach void open() throws Exception {
        GlobalConfig.getInstance().loadFrom(directory);
        var configure = CompanionApp.class.getDeclaredMethod("configureLookAndFeel");
        configure.setAccessible(true);
        configure.invoke(null);
        index = JavaAnalysisFixtures.index(ScriptCompletionPopupTest.class, Values.class, Map.class, List.class, Path.class);
        app = new CompanionApplication(new CompanionLaunchConfiguration(directory), "test-token");
        app.openProject(CompanionProfile.forGame(Files.createDirectories(directory.resolve("game")))).get(10, TimeUnit.SECONDS);
        edt(() -> {
            var window = app.createWindow();
            CompanionClassIndex.set(index);
            panel = (ScriptPanel) new ScriptView(window.editorContext(), "PopupProof").getComponent();
            ((CodeCompletionPopup) field(panel, "codeCompletionPopup").get(panel)).dispose();
            popup = new TestPopup();
            field(panel, "codeCompletionPopup").set(panel, popup);
            previousFocus = KeyboardFocusManager.getCurrentKeyboardFocusManager();
            KeyboardFocusManager.setCurrentKeyboardFocusManager(new DefaultKeyboardFocusManager() {
                @Override public Component getFocusOwner() { return panel.editorPane; }
            });
            panel.editorPane.setText("String text = \"x\"; text.l");
            panel.editorPane.setCaretPosition(panel.editorPane.getDocument().getLength());
            return null;
        });
    }

    @AfterEach void close() throws Exception {
        edt(() -> { panel.dispose(); KeyboardFocusManager.setCurrentKeyboardFocusManager(previousFocus); return null; });
        app.close();
        CompanionClassIndex.clear();
        index.close();
    }

    @Test void postfixVarAcceptedWithEnterImportsNestedTypesAndKeepsTheNameSelected() throws Exception {
        edt(() -> {
            panel.editorPane.setText("import " + Values.class.getCanonicalName() + ";\n\n((Values) null).entries.var");
            panel.editorPane.setCaretPosition(panel.editorPane.getDocument().getLength());
            return null;
        });
        complete(request(false));
        select("var");
        press(KeyEvent.VK_ENTER);
        edt(() -> {
            String text = panel.editorPane.getText();
            assertTrue(text.contains("import java.util.Map;"), text);
            assertTrue(text.contains("import java.util.List;"), text);
            assertTrue(text.contains("import java.nio.file.Path;"), text);
            assertTrue(text.contains("Map<String,List<Path>> name = ((Values) null).entries;"), text);
            assertEquals("name", panel.editorPane.getSelectedText());
            return null;
        });
        analyzed();
        assertTrue(edt(() -> panel.editorPane.getParserNotices().stream().noneMatch(n -> n.getLevel() == ParserNotice.Level.ERROR)));
    }

    @Test void enterWhileReplacementIsPendingAcceptsFreshRangesExactlyOnce() throws Exception {
        var old = request(false);
        complete(old);
        select("length");
        edt(() -> {
            field(panel, "didTypeBeforeCaretMove").setBoolean(panel, true);
            panel.editorPane.append("e");
            panel.editorPane.setCaretPosition(panel.editorPane.getDocument().getLength());
            return null;
        });
        var latest = request(true);
        String pending = edt(() -> panel.editorPane.getText());
        press(KeyEvent.VK_ENTER);
        assertEquals(pending, edt(() -> panel.editorPane.getText()), "Never insert an older proposal's ranges");
        complete(latest);
        assertTrue(edt(() -> panel.editorPane.getText().endsWith("text.length()")));
        assertFalse(edt(popup::isVisible));
    }

    @Test void latestFailureDismissesStaleItemsAndReleasesEnterThenNextRequestWorks() throws Exception {
        complete(request(false));
        var failed = request(true);
        failRequest(failed);
        assertFalse(edt(popup::isVisible));
        String before = edt(() -> panel.editorPane.getText());
        press(KeyEvent.VK_ENTER);
        assertEquals(before + "\n", edt(() -> panel.editorPane.getText()));
        edt(() -> { panel.editorPane.setText(before); panel.editorPane.setCaretPosition(before.length()); return null; });
        complete(request(false));
        select("length");
        press(KeyEvent.VK_ENTER);
        assertTrue(edt(() -> panel.editorPane.getText().endsWith("text.length()")));
    }

    @Test void obsoleteFailureCannotDismissNewResults() throws Exception {
        var old = request(false);
        var fresh = request(false);
        complete(fresh);
        failRequest(old);
        assertTrue(edt(popup::isVisible));
        select("length");
        press(KeyEvent.VK_TAB);
        assertTrue(edt(() -> panel.editorPane.getText().endsWith("text.length()")));
    }

    @Test void escapeDiscardsQueuedAcceptanceAndPreventsLateResultsReopeningPopup() throws Exception {
        complete(request(false));
        var pending = request(true);
        press(KeyEvent.VK_ENTER);
        String before = edt(() -> panel.editorPane.getText());
        press(KeyEvent.VK_ESCAPE);
        complete(pending);
        assertFalse(edt(popup::isVisible));
        assertEquals(before, edt(() -> panel.editorPane.getText()));
    }

    @Test void deletionWithoutCaretMovementInvalidatesVisibleSourceRanges() throws Exception {
        edt(() -> {
            panel.editorPane.append(";");
            panel.editorPane.setCaretPosition(panel.editorPane.getDocument().getLength() - 1);
            return null;
        });
        complete(request(false));
        int caret = edt(() -> panel.editorPane.getCaretPosition());
        edt(() -> { panel.editorPane.getDocument().remove(caret, 1); return null; });
        assertEquals(caret, edt(() -> panel.editorPane.getCaretPosition()));
        assertFalse(edt(popup::isVisible));
    }

    @Test void acceptingVoidCallWithEnterDoesNotFlashThePreviousDotError() throws Exception {
        edt(() -> { panel.editorPane.setText("String text = \"x\"; text.length();"); return null; });
        analyzed();
        edt(() -> {
            int start = panel.editorPane.getText().indexOf("length");
            panel.editorPane.replaceRange("", start, panel.editorPane.getDocument().getLength());
            panel.editorPane.setCaretPosition(start);
            return null;
        });
        analyzed();
        complete(request(false));
        select("notify");
        press(KeyEvent.VK_ENTER);
        edt(() -> {
            assertTrue(panel.editorPane.getText().endsWith("text.notify();"));
            assertEquals(panel.editorPane.getDocument().getLength(), panel.editorPane.getCaretPosition());
            assertTrue(panel.editorPane.getParserNotices().stream().noneMatch(n -> n.getLevel() == ParserNotice.Level.ERROR));
            return null;
        });
        analyzed();
        assertTrue(edt(() -> panel.editorPane.getParserNotices().isEmpty()));
    }

    private record Request(CompilationUnitImpl unit, int offset, CustomCompletionRequestor requestor) { }

    private Request request(boolean refresh) throws Exception {
        return edt(() -> {
            var generated = JavaSnippetSource.body("PopupProof", panel.editorPane.getText());
            var unit = new CompilationUnitImpl("PopupProof", generated.source());
            int offset = generated.sourceMap().toGeneratedOffset(panel.editorPane.getCaretPosition());
            var requestor = new CustomCompletionRequestor(unit, offset, (request, items) -> {
                try {
                    var accept = ScriptPanel.class.getDeclaredMethod("acceptCompletionList", CustomCompletionRequestor.class,
                            List.class, JavaSnippetSource.GeneratedSource.class, boolean.class);
                    accept.setAccessible(true);
                    accept.invoke(panel, request, items, generated, refresh);
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            });
            requestor.beginTask("controlled delivery", 1);
            field(panel, "completionRequestor").set(panel, requestor);
            field(panel, "completionToAccept").set(panel, null);
            return new Request(unit, offset, requestor);
        });
    }

    private void complete(Request request) throws Exception {
        request.unit().codeComplete(request.offset(), request.requestor(), request.requestor());
        edt(() -> null);
    }

    private void failRequest(Request request) throws Exception {
        var fail = ScriptPanel.class.getDeclaredMethod("completionFailed", CustomCompletionRequestor.class, Exception.class);
        fail.setAccessible(true);
        fail.invoke(panel, request.requestor(), new IllegalStateException("test completion failure"));
        edt(() -> null);
    }

    private void select(String name) throws Exception {
        edt(() -> {
            var list = (JList<?>) field(popup, "list").get(popup);
            for (int i = 0; i < list.getModel().getSize(); i++) {
                if (((CompletionItem) list.getModel().getElementAt(i)).getName().equals(name)) {
                    list.setSelectedIndex(i);
                    return null;
                }
            }
            throw new AssertionError("Missing completion " + name);
        });
    }

    private void press(int key) throws Exception {
        edt(() -> {
            var event = new KeyEvent(panel.editorPane, KeyEvent.KEY_PRESSED, 0, 0, key, KeyEvent.CHAR_UNDEFINED);
            for (var listener : panel.editorPane.getKeyListeners()) listener.keyPressed(event);
            if (!event.isConsumed()) {
                var binding = panel.editorPane.getInputMap().get(KeyStroke.getKeyStroke(key, 0));
                var action = panel.editorPane.getActionMap().get(binding);
                if (action != null) action.actionPerformed(new ActionEvent(panel.editorPane, ActionEvent.ACTION_PERFORMED, "test key"));
            }
            return null;
        });
    }

    private void analyzed() throws Exception {
        var ready = new CountDownLatch(1);
        Runnable remove = edt(() -> {
            String text = panel.editorPane.getText();
            var unsubscribe = panel.astCache().addChangeListener(panel.astKey(), result -> {
                if (result != null && result.contents().equals(text)) ready.countDown();
            });
            var snapshot = panel.analysis.currentSnapshot();
            if (snapshot != null && snapshot.contents().equals(text)) ready.countDown();
            panel.analysis.requestNow();
            return unsubscribe;
        });
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        edt(() -> { remove.run(); return null; });
    }

    private static final class TestPopup extends CodeCompletionPopup {
        private boolean shown;
        TestPopup() { super(null); }
        @Override public boolean isVisible() { return shown; }
        @Override public void setVisible(boolean visible) { shown = visible; if (!visible) super.setVisible(false); }
        @Override public void show(JTextComponent editor) {
            try {
                var listener = (KeyListener) field(this, "listener").get(this);
                editor.removeKeyListener(listener);
                field(this, "invoker").set(this, editor);
                editor.addKeyListener(listener);
                shown = true;
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        }
    }

    private static Field field(Object object, String name) throws NoSuchFieldException {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try { var field = type.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
