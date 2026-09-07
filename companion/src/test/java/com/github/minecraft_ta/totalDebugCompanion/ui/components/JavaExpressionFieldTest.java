package com.github.minecraft_ta.totalDebugCompanion.ui.components;

import com.formdev.flatlaf.FlatDarkLaf;
import org.junit.jupiter.api.Test;
import javax.swing.SwingUtilities;
import static org.junit.jupiter.api.Assertions.*;

class JavaExpressionFieldTest {
    @Test
    void expandedEditorUsesEnterForNewlinesAndControlEnterForEvaluation() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FlatDarkLaf.setup();
            JavaExpressionField field = new JavaExpressionField(30);
            field.setExpandable(true);
            new ExpressionCompletionSupport(field);
            java.util.concurrent.atomic.AtomicInteger evaluations = new java.util.concurrent.atomic.AtomicInteger();
            field.addActionListener(event -> evaluations.incrementAndGet());
            field.setText("1 + 2");
            press(field, "ENTER");
            assertEquals(1, evaluations.get());
            field.setMultiline(true);
            field.setCaretPosition(field.getDocument().getLength());
            press(field, "ENTER");
            assertTrue(field.getText().endsWith("\n"));
            assertEquals(1, evaluations.get());
            press(field, "TAB");
            assertTrue(!field.getText().substring(field.getText().indexOf('\n') + 1).isEmpty());
            press(field, "ctrl ENTER");
            assertEquals(2, evaluations.get());
        });
    }

    private static void press(JavaExpressionField field, String stroke) {
        Object binding = field.getInputMap().get(javax.swing.KeyStroke.getKeyStroke(stroke));
        field.getActionMap().get(binding).actionPerformed(new java.awt.event.ActionEvent(field, 0, stroke));
    }

    @Test
    void multilinePasteExpandsAndCollapsePreservesSource() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FlatDarkLaf.setup();
            JavaExpressionField field = new JavaExpressionField(30);
            field.setExpandable(true);
            String source = "var count = 1;\nreturn count;";
            field.replaceSelection(source);
            assertTrue(field.isMultiline());
            assertEquals(source, field.getText());
            field.setMultiline(false);
            assertEquals(source, field.getText());
            field.setMultiline(true);
            assertEquals(source, field.getText());
        });
    }
}
