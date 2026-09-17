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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import org.fife.ui.rsyntaxtextarea.Token;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class ScriptCompletionDiagnosticsTest {
    @TempDir Path directory;
    private static final String SOURCE = "import java.util.List;\nimport java.util.Map;\nimport java.util.concurrent.ConcurrentHashMap;\n\n"
            + "String text = \"stable\";\ntext.length();\n\n";
    private ScriptPanel panel;

    @Test void actualCompletionImportRewriteKeepsOtherWarningsAndColorsThroughErrorsAndUndo() throws Exception {
        Path home = Files.createDirectories(directory.resolve("app"));
        GlobalConfig.getInstance().loadFrom(home);
        var configure = CompanionApp.class.getDeclaredMethod("configureLookAndFeel"); configure.setAccessible(true); configure.invoke(null);
        try (var index = JavaAnalysisFixtures.index(Optional.class, List.class, Map.class, ConcurrentHashMap.class);
             var app = new CompanionApplication(new CompanionLaunchConfiguration(home), "test-token")) {
            app.openProject(CompanionProfile.forGame(Files.createDirectories(home.resolve("game")))).get(10, TimeUnit.SECONDS);
            edt(() -> {
                var window = app.createWindow();
                CompanionClassIndex.set(index);
                panel = (ScriptPanel) new ScriptView(window.editorContext(), "RecordingProof").getComponent();
                panel.editorPane.setText(SOURCE);
                panel.editorPane.setCaretPosition(SOURCE.length());
                return null;
            });
            settle(); check("initial", 3);
            edt(() -> { panel.editorPane.append("Optio"); panel.editorPane.setCaretPosition(panel.editorPane.getDocument().getLength()); return null; });
            settle(); check("typed Optio", 3);
            String text = edt(() -> panel.editorPane.getText());
            var generated = JavaSnippetSource.body("RecordingProof", text);
            var unit = new CompilationUnitImpl("RecordingProof", generated.source());
            var suggestions = new CompletableFuture<List<CompletionItem>>();
            int offset = generated.sourceMap().toGeneratedOffset(text.length());
            var request = new CustomCompletionRequestor(unit, offset, (ignored, items) -> suggestions.complete(items));
            unit.codeComplete(offset, request, request);
            var item = suggestions.get(10, TimeUnit.SECONDS).stream().filter(candidate -> candidate.getName().equals("Optional")
                    && candidate.getType().equals("java.util")).findFirst().orElseThrow();
            edt(() -> {
                var mapper = ScriptPanel.class.getDeclaredMethod("mapCompletionEdits", CompletionItem.class, JavaSnippetSource.GeneratedSource.class);
                mapper.setAccessible(true); mapper.invoke(null, item, generated);
                var activeRequest = ScriptPanel.class.getDeclaredField("completionRequestor"); activeRequest.setAccessible(true); activeRequest.set(panel, request);
                var accept = ScriptPanel.class.getDeclaredMethod("doAutoCompletion", CompletionItem.class); accept.setAccessible(true); accept.invoke(panel, item);
                check("actual completion before yielding EDT to analysis", 3);
                return null;
            });
            check("after actual completion insertion, before new analysis", 3);
            settle(); check("after Optional analysis", 3);
            edt(() -> { panel.editorPane.undoLastAction(); check("undo before analysis", 3); return null; });
            check("completion undo pending", 3);
            settle(); check("completion undo settled", 3);
            edt(() -> { panel.editorPane.redoLastAction(); check("redo before analysis", 3); return null; });
            check("completion redo pending", 3);
            settle(); check("completion redo settled", 3);
            edt(() -> { panel.editorPane.append(".EMPTY;"); panel.editorPane.setCaretPosition(panel.editorPane.getDocument().getLength()); return null; });
            settle(); check("Optional.EMPTY; syntax error must not erase unrelated imports", 3);

            edt(() -> { panel.editorPane.setText(SOURCE); return null; });
            settle(); check("reset", 3);
            edt(() -> {
                int semicolon = panel.editorPane.getText().indexOf("ConcurrentHashMap;") + "ConcurrentHashMap".length();
                panel.editorPane.replaceRange("", semicolon, semicolon + 1);
                panel.editorPane.setCaretPosition(semicolon);
                return null;
            });
            check("removed final import semicolon, pending", 2);
            settle(); check("removed final import semicolon, analyzed", 2);
            edt(() -> { panel.dispose(); return null; });
        } finally { CompanionClassIndex.clear(); }

    }

    private void check(String label, int expected) throws Exception {
        edt(() -> {
            var notices = panel.editorPane.getParserNotices();
            long unused = notices.stream().filter(n -> n.getMessage().contains("never used")).count();
            assertEquals(expected, unused, label);
            int method = panel.editorPane.getText().indexOf("length()");
            var token = panel.editorPane.getTokenListForLine(panel.editorPane.getLineOfOffset(method));
            while (token != null && !token.containsPosition(method)) token = token.getNextToken();
            assertNotNull(token, label);
            assertEquals(Token.FUNCTION, token.getType(), label + " semantic method color");
            for (var notice : notices) {
                if (notice.getMessage().contains("never used")) {
                    String marked = panel.editorPane.getText(notice.getOffset(), notice.getLength());
                    assertTrue(marked.startsWith("java.util."), label + " incorrect warning location: " + marked);
                }
            }
            return null;
        });
    }
    private void settle() throws Exception {
        var ready = new CountDownLatch(1);
        Runnable remove = edt(() -> {
            String expected = panel.editorPane.getText();
            var unsubscribe = panel.astCache().addChangeListener(panel.astKey(), result -> {
                if (result != null && result.contents().equals(expected)) ready.countDown();
            });
            var current = panel.astCache().getSnapshot(panel.astKey());
            if (current != null && current.contents().equals(expected)) ready.countDown();
            panel.analysis.requestNow();
            return unsubscribe;
        });
        if (!ready.await(15, TimeUnit.SECONDS)) throw new AssertionError("Analysis did not settle");
        edt(() -> { remove.run(); return null; });
    }
    private static <T> T edt(Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return action.call();
        var result = new FutureTask<>(action); SwingUtilities.invokeAndWait(result); return result.get();
    }
}
