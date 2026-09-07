package com.github.minecraft_ta.totalDebugCompanion.jdt.completion;

import org.eclipse.jdt.core.CompletionProposal;

import java.util.Comparator;

public class CompletionProposalComparator implements Comparator<CompletionProposal> {

    @Override
    public int compare(CompletionProposal o1, CompletionProposal o2) {
        var res = o2.getRelevance() - o1.getRelevance();
        if (res == 0)
            res = getNameLength(o1) - getNameLength(o2);

        return res;
    }

    private static int getNameLength(CompletionProposal proposal) {
        var name = proposal.getName();
        if (name == null)
            name = proposal.getSignature();
        if (name == null)
            name = proposal.getDeclarationSignature();
        return name == null ? 1000 : name.length;
    }
}
