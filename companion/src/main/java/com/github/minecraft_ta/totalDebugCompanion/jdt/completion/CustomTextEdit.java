package com.github.minecraft_ta.totalDebugCompanion.jdt.completion;

public class CustomTextEdit {

    private final Range range;
    private String text;

    public CustomTextEdit(Range range, String text) {
        this.range = range;
        this.text = text;
    }

    public Range getRange() {
        return this.range;
    }

    public String getNewText() {
        return this.text;
    }

    public void setNewText(String newText) {
        this.text = newText;
    }

    public boolean isSnippet() {
        return SnippetCompletionAdapter.isSnippet(this.text);
    }
}
