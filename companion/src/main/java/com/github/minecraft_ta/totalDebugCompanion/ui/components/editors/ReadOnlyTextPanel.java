package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.ui.theme.EditorPalette;
import org.fife.ui.rsyntaxtextarea.SyntaxScheme;
import org.fife.ui.rsyntaxtextarea.TokenTypes;

/**
 * Generated read-only text, such as SNBT, in the editor's font and colors, with search. Keys that a syntax style marks
 * as attributes use the field color.
 */
public final class ReadOnlyTextPanel extends AbstractTextViewPanel {
    public ReadOnlyTextPanel(String syntaxStyle) {
        super();
        this.editorPane.setEditable(false);
        setSyntaxStyle(syntaxStyle);
        enableSearch();
    }

    @Override
    protected void applyAdditionalSyntaxColors(SyntaxScheme scheme, EditorPalette palette) {
        scheme.getStyle(TokenTypes.MARKUP_TAG_ATTRIBUTE).foreground = palette.field();
    }

    /** Replaces the text, keeping the caret where it was as far as the new text allows. */
    public void setContent(String text) {
        if (this.editorPane.getText().equals(text)) {
            return;
        }
        int caret = Math.min(this.editorPane.getCaretPosition(), text.length());
        this.editorPane.setText(text);
        this.editorPane.setCaretPosition(caret);
    }

    public String content() {
        return this.editorPane.getText();
    }
}
