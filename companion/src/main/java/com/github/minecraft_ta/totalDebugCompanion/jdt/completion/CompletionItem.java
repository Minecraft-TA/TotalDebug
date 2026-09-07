package com.github.minecraft_ta.totalDebugCompanion.jdt.completion;

import com.github.minecraft_ta.totalDebugCompanion.ui.views.BaseListPopup;

import java.util.ArrayList;
import java.util.List;

public class CompletionItem implements BaseListPopup.ListItem {

    private String label;
    private CompletionItemKind kind;
    private int relevance;
    private final List<CustomTextEdit> textEdits = new ArrayList<>();

    private final CustomCompletionRequestor requestor;

    public CompletionItem(CustomCompletionRequestor requestor) {
        this.requestor = requestor;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public void setKind(CompletionItemKind kind) {
        this.kind = kind;
    }

    public void setRelevance(int relevance) {
        this.relevance = relevance;
    }

    public String getLabel() {
        return label;
    }

    public CompletionItemKind getKind() {
        return kind;
    }

    public int getRelevance() {
        return relevance;
    }

    public void addTextEdit(CustomTextEdit customTextEdit) {
        if (customTextEdit.getRange().getLength() == 0 && customTextEdit.getNewText().isEmpty())
            return;

        this.textEdits.add(customTextEdit);
    }

    public List<CustomTextEdit> getTextEdits() {
        return textEdits;
    }

    public CustomCompletionRequestor getRequestor() {
        return requestor;
    }

    @Override
    public int getLabelLength() {
        return this.label.length();
    }
}
