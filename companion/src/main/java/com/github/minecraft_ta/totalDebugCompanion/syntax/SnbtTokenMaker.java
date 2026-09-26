package com.github.minecraft_ta.totalDebugCompanion.syntax;

import org.fife.ui.rsyntaxtextarea.AbstractTokenMaker;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenMap;
import org.fife.ui.rsyntaxtextarea.TokenTypes;

import javax.swing.text.Segment;
import java.util.regex.Pattern;

/**
 * A line lexer for SNBT as Minecraft prints it: compound keys as {@link TokenTypes#MARKUP_TAG_ATTRIBUTE}, quoted
 * strings, numbers with their type suffix, and the {@code B;}, {@code I;} and {@code L;} array prefixes.
 */
public final class SnbtTokenMaker extends AbstractTokenMaker {
    private static final Pattern NUMBER = Pattern.compile(
            "[+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?[bBsSlLfFdD]?|[+-]?(?:NaN|Infinity)[fFdD]");

    @Override
    public TokenMap getWordsToHighlight() {
        return new TokenMap();
    }

    @Override
    public String getBracketPairs() {
        return "{}[]";
    }

    @Override
    public Token getTokenList(Segment text, int initialTokenType, int startOffset) {
        resetTokenList();
        int end = text.offset + text.count;
        int cursor = text.offset;
        while (cursor < end) {
            char current = text.array[cursor];
            if (Character.isWhitespace(current)) {
                int tokenEnd = cursor + 1;
                while (tokenEnd < end && Character.isWhitespace(text.array[tokenEnd])) tokenEnd++;
                add(text, cursor, tokenEnd - 1, TokenTypes.WHITESPACE, startOffset);
                cursor = tokenEnd;
                continue;
            }
            if (current == '"' || current == '\'') {
                int tokenEnd = stringEnd(text.array, cursor, end, current);
                add(text, cursor, tokenEnd - 1, followedByColon(text.array, tokenEnd, end)
                        ? TokenTypes.MARKUP_TAG_ATTRIBUTE : TokenTypes.LITERAL_STRING_DOUBLE_QUOTE, startOffset);
                cursor = tokenEnd;
                continue;
            }
            if ("{}[],:;".indexOf(current) >= 0) {
                add(text, cursor, cursor, TokenTypes.SEPARATOR, startOffset);
                cursor++;
                continue;
            }
            int tokenEnd = cursor + 1;
            while (tokenEnd < end && !Character.isWhitespace(text.array[tokenEnd])
                    && "{}[],:;\"'".indexOf(text.array[tokenEnd]) < 0) {
                tokenEnd++;
            }
            String token = new String(text.array, cursor, tokenEnd - cursor);
            int type;
            if (followedByColon(text.array, tokenEnd, end)) {
                type = TokenTypes.MARKUP_TAG_ATTRIBUTE;
            } else if (tokenEnd < end && text.array[tokenEnd] == ';' && token.length() == 1) {
                type = TokenTypes.DATA_TYPE;
            } else if (token.equals("true") || token.equals("false")) {
                type = TokenTypes.LITERAL_BOOLEAN;
            } else if (NUMBER.matcher(token).matches()) {
                type = TokenTypes.LITERAL_NUMBER_DECIMAL_INT;
            } else {
                type = TokenTypes.IDENTIFIER;
            }
            add(text, cursor, tokenEnd - 1, type, startOffset);
            cursor = tokenEnd;
        }
        addNullToken();
        return firstToken;
    }

    private static boolean followedByColon(char[] text, int from, int end) {
        int index = from;
        while (index < end && Character.isWhitespace(text[index])) index++;
        return index < end && text[index] == ':';
    }

    private static int stringEnd(char[] text, int start, int end, char quote) {
        for (int index = start + 1; index < end; index++) {
            if (text[index] == '\\') {
                index++;
            } else if (text[index] == quote) {
                return index + 1;
            }
        }
        return end;
    }

    private void add(Segment segment, int start, int end, int type, int startOffset) {
        if (end >= start) {
            addToken(segment, start, end, type, startOffset + start - segment.offset);
        }
    }
}
