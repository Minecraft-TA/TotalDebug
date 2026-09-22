package com.github.minecraft_ta.totalDebugCompanion.ui.components;

import com.github.minecraft_ta.totalDebugCompanion.testui.UiTest;
import com.formdev.flatlaf.FlatDarkLaf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.fife.ui.rsyntaxtextarea.AbstractTokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import com.github.minecraft_ta.totalDebugCompanion.jdt.semanticHighlighting.CustomJavaTokenMaker;
import javax.swing.SwingUtilities;
import static org.junit.jupiter.api.Assertions.*;

@UiTest
class JavaExpressionFieldTest {
    @BeforeEach void configureJava() {
        ((AbstractTokenMakerFactory) TokenMakerFactory.getDefaultInstance()).putMapping(
                SyntaxConstants.SYNTAX_STYLE_JAVA, CustomJavaTokenMaker.class.getName());
    }
    @Test void updatingAnUnfocusedFieldDoesNotActivateItsCaret() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FlatDarkLaf.setup();
            var field = new JavaExpressionField();
            assertFalse(field.isFocusOwner());
            field.setText("condition");
            field.setCaretPosition(3);
            assertFalse(field.getCaret().isVisible(), "Loading breakpoint values must not show an unfocused caret");
            field.getCaret().setVisible(false);
            field.setText("replacement");
            assertFalse(field.getCaret().isVisible());
        });
    }

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
