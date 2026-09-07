package com.github.minecraft_ta.totalDebugCompanion.jdt.semanticHighlighting;

import org.eclipse.jdt.core.dom.*;
import org.fife.ui.rsyntaxtextarea.TokenTypes;

import java.util.Map;

public class SemanticTokensVisitor extends ASTVisitor {

    private final Map<Integer, Integer> tokens;

    public SemanticTokensVisitor(Map<Integer, Integer> tokens) {
        this.tokens = tokens;
    }

    @Override
    public boolean visit(SimpleName node) {
        var binding = node.resolveBinding();
        if (binding == null)
            return false;

        var type = getTokenType(binding);
        if (type != -1) {
            addToken(node, type);
        }

        return false;
    }

    private int getTokenType(IBinding binding) {
        return switch (binding.getKind()) {
            case IBinding.TYPE -> ShadowedTokenTypes.TYPE;
            case IBinding.METHOD -> TokenTypes.FUNCTION;
            case IBinding.VARIABLE -> ((IVariableBinding) binding).isField() ? TokenTypes.VARIABLE : -1;
            default -> -1;
        };
    }

    private void addToken(ASTNode node, int type) {
        this.tokens.put(node.getStartPosition(), type);
    }
}
