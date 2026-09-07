package com.github.minecraft_ta.totalDebugCompanion.jdt.completion;

import java.util.Comparator;

public class CompletionItemComparator implements Comparator<CompletionItem> {

    @Override
    public int compare(CompletionItem a, CompletionItem b) {
        int res = b.getRelevance() - a.getRelevance();
        if (res == 0) {
            res = a.getLabel().compareTo(b.getLabel());
            if (res == 0)
                res = a.getLabel().length() - b.getLabel().length();
        }
        return res;
    }
}
