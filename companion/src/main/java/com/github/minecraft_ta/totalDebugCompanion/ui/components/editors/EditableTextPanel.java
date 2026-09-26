package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.ui.theme.EditorPalette;
import org.fife.ui.rsyntaxtextarea.SyntaxScheme;
import org.fife.ui.rsyntaxtextarea.TokenTypes;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import java.awt.event.ActionEvent;
import java.util.Objects;

/**
 * A file's text edited in place, in the editor's font and colors, with search and its own undo. It remembers the text
 * last loaded or saved, so it can tell whether there is anything to save; Ctrl+S asks for a save.
 */
public final class EditableTextPanel extends AbstractTextViewPanel {
    private String saved = "";
    private boolean loading;

    /** {@code changed} runs after every edit, {@code save} when Ctrl+S is pressed. */
    public EditableTextPanel(String syntaxStyle, Runnable changed, Runnable save) {
        super();
        Objects.requireNonNull(changed, "changed");
        Objects.requireNonNull(save, "save");
        setSyntaxStyle(syntaxStyle);
        enableSearch();
        this.editorPane.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { edited(); }
            @Override public void removeUpdate(DocumentEvent event) { edited(); }
            @Override public void changedUpdate(DocumentEvent event) { }

            private void edited() {
                if (!EditableTextPanel.this.loading) changed.run();
            }
        });
        this.editorPane.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ctrl S"), "saveText");
        this.editorPane.getActionMap().put("saveText", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                save.run();
            }
        });
    }

    @Override
    protected void applyAdditionalSyntaxColors(SyntaxScheme scheme, EditorPalette palette) {
        scheme.getStyle(TokenTypes.MARKUP_TAG_ATTRIBUTE).foreground = palette.field();
    }

    /** Shows {@code text} as the saved text, keeping the caret where it was as far as the text allows. */
    public void load(String text) {
        this.saved = text;
        if (this.editorPane.getText().equals(text)) return;
        int caret = Math.min(this.editorPane.getCaretPosition(), text.length());
        this.loading = true;
        try {
            this.editorPane.setText(text);
        } finally {
            this.loading = false;
        }
        this.editorPane.setCaretPosition(caret);
        this.editorPane.discardAllEdits();
    }

    /** Takes the current text as saved. */
    public void markSaved(String text) {
        this.saved = text;
    }

    public String text() {
        return this.editorPane.getText();
    }

    /** The text last loaded or saved. */
    public String savedText() {
        return this.saved;
    }

    /** Whether the text differs from the text last loaded or saved. */
    public boolean modified() {
        return !this.editorPane.getText().equals(this.saved);
    }

    /** Moves the caret to the start of a line, counted from 1. */
    public void goToLine(int line) {
        try {
            int index = Math.max(0, Math.min(line - 1, this.editorPane.getLineCount() - 1));
            this.editorPane.setCaretPosition(this.editorPane.getLineStartOffset(index));
            this.editorPane.requestFocusInWindow();
        } catch (BadLocationException outside) {
            // The text is shorter than the line; the caret stays.
        }
    }
}
