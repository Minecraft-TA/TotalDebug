package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

/** Generated read-only text, such as SNBT, in the editor's font and colors, with search. */
public final class ReadOnlyTextPanel extends AbstractTextViewPanel {
    public ReadOnlyTextPanel() {
        super();
        this.editorPane.setEditable(false);
        enableSearch();
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
