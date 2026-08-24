package com.github.minecraft_ta.totalDebugCompanion.syntax;

import org.fife.ui.rsyntaxtextarea.AbstractTokenMaker;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenMap;
import org.fife.ui.rsyntaxtextarea.TokenTypes;

import javax.swing.text.Segment;

/** Tokenizes the header and continuation-line structure of a JAR manifest. */
public final class ManifestTokenMaker extends AbstractTokenMaker {

    @Override
    public TokenMap getWordsToHighlight() {
        return new TokenMap();
    }

    @Override
    public Token getTokenList(Segment text, int initialTokenType, int startOffset) {
        resetTokenList();
        int start = text.offset;
        int end = text.offset + text.count;
        if (start == end) {
            addNullToken();
            return firstToken;
        }

        if (text.array[start] == ' ') {
            addToken(text, start, start, TokenTypes.WHITESPACE, startOffset);
            if (start + 1 < end) {
                addToken(text, start + 1, end - 1, TokenTypes.LITERAL_STRING_DOUBLE_QUOTE, startOffset + 1);
            }
            addNullToken();
            return firstToken;
        }

        int colon = -1;
        for (int i = start; i < end; i++) {
            if (text.array[i] == ':') {
                colon = i;
                break;
            }
        }
        if (colon == -1) {
            addToken(text, start, end - 1, TokenTypes.IDENTIFIER, startOffset);
            addNullToken();
            return firstToken;
        }

        if (colon > start) {
            addToken(text, start, colon - 1, TokenTypes.RESERVED_WORD, startOffset);
        }
        addToken(text, colon, colon, TokenTypes.OPERATOR, startOffset + colon - start);
        int valueStart = colon + 1;
        while (valueStart < end && Character.isWhitespace(text.array[valueStart])) {
            valueStart++;
        }
        if (valueStart > colon + 1) {
            addToken(text, colon + 1, valueStart - 1, TokenTypes.WHITESPACE, startOffset + colon + 1 - start);
        }
        if (valueStart < end) {
            addToken(text, valueStart, end - 1, TokenTypes.LITERAL_STRING_DOUBLE_QUOTE, startOffset + valueStart - start);
        }
        addNullToken();
        return firstToken;
    }
}
