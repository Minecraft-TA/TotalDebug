package com.github.minecraft_ta.totalDebugCompanion.jdt.completion;


import org.eclipse.jdt.core.CompletionProposal;

import java.util.ArrayList;
import java.util.List;

public class CompletionItem {

    private String name = "";
    private String detail = "";
    private String type = "";
    private int modifiers;
    record ReceiverCast(Range range, String signature) { }
    private ReceiverCast receiverCast;
    final CompletionProposal proposal;
    private CompletionItemKind kind;
    private int relevance;
    private final List<CustomTextEdit> textEdits = new ArrayList<>();

    private final CustomCompletionRequestor requestor;

    public CompletionItem(CustomCompletionRequestor requestor) {
        this(requestor, null);
    }

    CompletionItem(CustomCompletionRequestor requestor, CompletionProposal proposal) {
        this.requestor = requestor;
        this.proposal = proposal;
        if (proposal != null) {
            this.name = CompletionLabels.name(proposal);
            this.modifiers = proposal.getFlags();
            this.relevance = proposal.getRelevance();
            if (proposal.getKind() == CompletionProposal.FIELD_REF_WITH_CASTED_RECEIVER
                    || proposal.getKind() == CompletionProposal.METHOD_REF_WITH_CASTED_RECEIVER) {
                receiverCast = new ReceiverCast(new Range(proposal.getReceiverStart(),
                        proposal.getReceiverEnd() - proposal.getReceiverStart()), new String(proposal.getReceiverSignature()));
            }
        }
    }

    public void setPresentation(String name, String detail, String type) {
        this.name = name;
        this.detail = detail;
        this.type = type;
    }

    public String getName() { return this.name; }
    public String getDetail() { return this.detail; }
    public String getType() { return this.type; }
    public int getModifiers() { return this.modifiers; }
    public void setModifiers(int modifiers) { this.modifiers = modifiers; }
    ReceiverCast receiverCast() { return receiverCast; }
    void setReceiverCast(ReceiverCast cast) { receiverCast = cast; }
    public String getCastType() { return receiverCast == null ? "" : CompletionLabels.type(receiverCast.signature().toCharArray()); }
    public String getIdentity() {
        return proposal == null ? "template:" + name : proposal.getKind() + ":" + name + ":"
                + CompletionLabels.text(proposal.getDeclarationSignature()) + ":" + CompletionLabels.text(proposal.getSignature())
                + (receiverCast == null ? "" : ":cast:" + receiverCast.signature());
    }

    public void setKind(CompletionItemKind kind) {
        this.kind = kind;
    }

    public void setRelevance(int relevance) {
        this.relevance = relevance;
    }

    public String getLabel() {
        return name + detail + (receiverCast == null ? "" : " (cast to " + getCastType() + ")")
                + (type.isEmpty() ? "" : " : " + type);
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

}
