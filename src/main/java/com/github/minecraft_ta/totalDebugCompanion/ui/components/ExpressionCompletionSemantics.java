package com.github.minecraft_ta.totalDebugCompanion.ui.components;

import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerCompletionProposal;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.SimpleName;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Derives semantic colors from a source-backed debugger completion provider. */
public final class ExpressionCompletionSemantics {
    private ExpressionCompletionSemantics() {
    }

    public static CompletableFuture<List<DebugEngine.ExpressionToken>> tokens(
            String expression,
            ExpressionCompletionSupport.CompletionProvider completionProvider
    ) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_EXPRESSION);
        parser.setSource(expression.toCharArray());
        parser.setStatementsRecovery(true);
        Expression root = (Expression) parser.createAST(null);
        if (root == null) {
            return CompletableFuture.completedFuture(List.of());
        }

        List<CompletableFuture<DebugEngine.ExpressionToken>> requests = new ArrayList<>();
        root.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName name) {
                int start = name.getStartPosition();
                int end = start + name.getLength();
                CompletableFuture<DebugEngine.ExpressionToken> request;
                try {
                    request = completionProvider.complete(expression, end, true)
                            .thenApply(proposals -> token(name, start, end, proposals));
                } catch (RuntimeException ignored) {
                    return true;
                }
                requests.add(request.exceptionally(ignored -> null));
                return true;
            }
        });
        return CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> requests.stream()
                        .map(CompletableFuture::join)
                        .filter(java.util.Objects::nonNull)
                        .sorted(Comparator.comparingInt(DebugEngine.ExpressionToken::start))
                        .toList());
    }

    private static DebugEngine.ExpressionToken token(
            SimpleName name,
            int start,
            int end,
            List<DebuggerCompletionProposal> proposals
    ) {
        String identifier = name.getIdentifier();
        return proposals.stream()
                .filter(proposal -> proposal.replacementStart() <= start && proposal.replacementEnd() >= end)
                .filter(proposal -> proposal.label().equals(identifier)
                        || proposal.label().startsWith(identifier + "("))
                .min(Comparator.comparingInt(DebuggerCompletionProposal::rank))
                .map(proposal -> switch (proposal.kind()) {
                    case TYPE -> new DebugEngine.ExpressionToken(
                            start, name.getLength(), DebugEngine.ExpressionTokenKind.TYPE);
                    case FIELD, CONSTANT -> new DebugEngine.ExpressionToken(
                            start, name.getLength(), DebugEngine.ExpressionTokenKind.FIELD);
                    case METHOD -> new DebugEngine.ExpressionToken(
                            start, name.getLength(), DebugEngine.ExpressionTokenKind.METHOD);
                    case VARIABLE, KEYWORD -> null;
                })
                .orElse(null);
    }
}
