package com.github.minecraft_ta.totalDebugCompanion.jdt.completion;

import org.eclipse.jdt.core.CompletionProposal;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.internal.codeassist.RelevanceConstants;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Semantic relevance first; application preferences only break comparable matches. */
final class CompletionRanking {
    static List<CompletionItem> order(List<CompletionItem> candidates, String token) {
        Set<String> minecraftNames = candidates.stream().filter(CompletionRanking::minecraftType)
                .map(CompletionItem::getName).collect(Collectors.toSet());
        int bestRelevance = candidates.stream().filter(item -> item.proposal != null)
                .mapToInt(CompletionItem::getRelevance).max().orElse(0);
        for (var item : candidates) {
            if (item.proposal == null) item.setRelevance(item.getName().equals(token) ? bestRelevance + 1 : 0);
        }
        var unique = new LinkedHashMap<String, CompletionItem>();
        candidates.stream().sorted(Comparator.comparingInt((CompletionItem item) -> relevance(item, minecraftNames)).reversed()
                        .thenComparingInt(item -> match(item.getName(), token))
                        .thenComparingInt(item -> item.receiverCast() == null ? 0 : 1)
                        .thenComparingInt(CompletionRanking::typePreference)
                        .thenComparingInt(CompletionRanking::scope)
                        .thenComparingInt(item -> Flags.isPublic(item.getModifiers()) ? 0 : 1)
                        .thenComparing(CompletionItem::getName)
                        .thenComparingInt(CompletionRanking::arity)
                        .thenComparing(CompletionItem::getIdentity))
                .forEach(item -> unique.putIfAbsent(item.getIdentity(), item));
        return List.copyOf(unique.values());
    }

    private static int match(String name, String token) {
        if (name.equals(token)) return 0;
        if (name.startsWith(token)) return 1;
        if (name.regionMatches(true, 0, token, 0, token.length())) return 2;
        return 3;
    }

    private static int typePreference(CompletionItem item) {
        if (item.proposal == null || item.proposal.getKind() != CompletionProposal.TYPE_REF) return 0;
        return minecraftType(item) ? 0 : 1;
    }

    private static boolean minecraftType(CompletionItem item) {
        return item.proposal != null && item.proposal.getKind() == CompletionProposal.TYPE_REF
                && CompletionLabels.text(item.proposal.getSignature()).replace('/', '.').startsWith("Lnet.minecraft.");
    }

    private static int relevance(CompletionItem item, Set<String> minecraftNames) {
        // JDT's generic Java-library preference should not choose logging.Level over Minecraft's Level.
        // Import, expected-type and name-match scores remain intact; unrelated Java types are unaffected.
        boolean collision = item.proposal != null && item.proposal.getKind() == CompletionProposal.TYPE_REF
                && minecraftNames.contains(item.getName())
                && CompletionLabels.text(item.proposal.getSignature()).replace('/', '.').startsWith("Ljava.");
        return item.getRelevance() - (collision ? RelevanceConstants.R_JAVA_LIBRARY : 0);
    }

    private static int scope(CompletionItem item) {
        return switch (item.getKind()) {
            case VARIABLE -> 0;
            case FIELD, CONSTANT, ENUM_MEMBER, METHOD, CONSTRUCTOR -> 1;
            default -> 2;
        };
    }

    private static int arity(CompletionItem item) {
        if (item.proposal == null) return 0;
        return switch (item.proposal.getKind()) {
            case CompletionProposal.METHOD_REF, CompletionProposal.METHOD_NAME_REFERENCE,
                    CompletionProposal.CONSTRUCTOR_INVOCATION, CompletionProposal.METHOD_REF_WITH_CASTED_RECEIVER,
                    CompletionProposal.LAMBDA_EXPRESSION -> Signature.getParameterCount(item.proposal.getSignature());
            default -> 0;
        };
    }
}
