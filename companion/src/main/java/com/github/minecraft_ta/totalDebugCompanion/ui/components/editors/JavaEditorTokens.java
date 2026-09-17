package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenTypes;

/** Shared lexical predicates for display policy; semantic resolution stays in JDT. */
final class JavaEditorTokens {
    private JavaEditorTokens() { }

    static boolean isTrivia(Token token) {
        return !token.isPaintable() || token.isComment() || token.isWhitespace();
    }

    static boolean isLiteral(Token token) {
        return switch (token.getType()) {
            case TokenTypes.LITERAL_STRING_DOUBLE_QUOTE, TokenTypes.LITERAL_CHAR, TokenTypes.LITERAL_BACKQUOTE,
                    TokenTypes.ERROR_STRING_DOUBLE, TokenTypes.ERROR_CHAR -> true;
            default -> false;
        };
    }

    static boolean isCode(Token token) { return !isTrivia(token) && !isLiteral(token); }

    static boolean isName(String word) {
        if (word.isEmpty() || !Character.isJavaIdentifierStart(word.charAt(0))) return false;
        for (int i = 1; i < word.length(); i++)
            if (!Character.isJavaIdentifierPart(word.charAt(i)) && word.charAt(i) != '.') return false;
        return true;
    }
}
