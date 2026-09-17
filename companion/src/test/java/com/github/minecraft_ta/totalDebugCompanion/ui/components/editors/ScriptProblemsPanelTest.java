package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaSnippetSource;
import com.github.minecraft_ta.totaldebug.evaluation.CompilationDiagnostic;
import org.junit.jupiter.api.Test;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.ActionEvent;
import java.util.List;
import javax.swing.*;
import javax.tools.Diagnostic;
import static org.junit.jupiter.api.Assertions.*;

class ScriptProblemsPanelTest {
    @Test void enterNavigatesAndChangesDisableItUntilTheSubmittedTextIsRestored() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            String text = "// header\nimport missing.Type;\nreturn 1;";
            var editor = new JTextArea(text);
            var panel = new ScriptProblemsPanel(editor);
            var generated = JavaSnippetSource.body("Proof", text);
            int start = generated.source().indexOf("missing");
            panel.showProblems(generated, List.of(problem("compiler.err.doesnt.exist", start, start + 7)));
            JList<?> list = find(panel, JList.class);
            Action enter = list.getActionMap().get("jumpToProblem");
            enter.actionPerformed(new ActionEvent(list, 0, ""));
            assertEquals("missing", editor.getSelectedText());
            assertEquals(text.indexOf("missing"), editor.getSelectionStart());
            editor.setText("return 2;");
            panel.refreshSourceState();
            assertFalse(enter.isEnabled());
            int editedCaret = editor.getCaretPosition();
            enter.actionPerformed(new ActionEvent(list, 0, ""));
            assertEquals(editedCaret, editor.getCaretPosition());
            assertTrue(find(panel, JLabel.class).isVisible());
            editor.setText(text); panel.refreshSourceState();
            assertTrue(enter.isEnabled());
            assertFalse(find(panel, JLabel.class).isVisible());
            panel.clear(); assertEquals(0, list.getModel().getSize()); assertFalse(enter.isEnabled());
        });
    }

    @Test void endOfInputAndMissingLocationsAreHandledWithoutInvalidCaretPositions() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            String text = "if (true) {\nreturn 1;";
            var editor = new JTextArea(text);
            var panel = new ScriptProblemsPanel(editor);
            var source = JavaSnippetSource.body("Proof", text);
            panel.showProblems(source, List.of(problem("compiler.err.premature.eof", source.source().length(), source.source().length())));
            var list = find(panel, JList.class);
            var enter = list.getActionMap().get("jumpToProblem");
            enter.actionPerformed(new ActionEvent(list, 0, ""));
            assertEquals(text.length(), editor.getCaretPosition());
            panel.showProblems(source, List.of(problem("compiler.err.cant.resolve", -1, -1)));
            assertEquals(1, list.getModel().getSize()); assertFalse(enter.isEnabled());
            editor.setText("different source before the result arrives");
            panel.showProblems(source, List.of(problem("compiler.err.premature.eof", source.source().length(), source.source().length())));
            assertFalse(enter.isEnabled()); assertTrue(find(panel, JLabel.class).isVisible());
        });
    }

    private static CompilationDiagnostic problem(String code, int start, int end) {
        return new CompilationDiagnostic(Diagnostic.Kind.ERROR, code, "Example compiler error", start, end, 1, 1);
    }
    private static <T> T find(Container root, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container container) {
                T found = find(container, type); if (found != null) return found;
            }
        }
        return null;
    }
}
