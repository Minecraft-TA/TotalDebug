package com.github.minecraft_ta.totalDebugCompanion.syntax;

import org.fife.ui.rsyntaxtextarea.AbstractTokenMaker;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenMap;
import org.fife.ui.rsyntaxtextarea.TokenTypes;

import javax.swing.text.Segment;
import java.util.regex.Pattern;

/** A small line lexer for the TOML constructs commonly found in mod metadata and configuration. */
public final class TomlTokenMaker extends AbstractTokenMaker {

    private static final Pattern NUMBER = Pattern.compile(
            "[+-]?(?:0x[0-9a-fA-F_]+|0o[0-7_]+|0b[01_]+|(?:\\d[\\d_]*)(?:\\.[\\d_]+)?(?:[eE][+-]?[\\d_]+)?|inf|nan)"
    );
    private static final Pattern DATE_OR_TIME = Pattern.compile("\\d{2,4}[-:]\\d.*");

    @Override
    public TokenMap getWordsToHighlight() {
        return new TokenMap();
    }

    @Override
    public String[] getLineCommentStartAndEnd(int languageIndex) {
        return new String[]{"#", null};
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

        if (initialTokenType == TokenTypes.LITERAL_STRING_DOUBLE_QUOTE
                || initialTokenType == TokenTypes.LITERAL_CHAR) {
            char quote = initialTokenType == TokenTypes.LITERAL_CHAR ? '\'' : '"';
            int close = findTriple(text.array, cursor, end, quote);
            if (close == -1) {
                add(text, cursor, end - 1, initialTokenType, startOffset);
                return firstToken;
            }
            add(text, cursor, close + 2, initialTokenType, startOffset);
            cursor = close + 3;
        }

        boolean value = false;
        while (cursor < end) {
            char current = text.array[cursor];
            if (Character.isWhitespace(current)) {
                int tokenEnd = cursor + 1;
                while (tokenEnd < end && Character.isWhitespace(text.array[tokenEnd])) {
                    tokenEnd++;
                }
                add(text, cursor, tokenEnd - 1, TokenTypes.WHITESPACE, startOffset);
                cursor = tokenEnd;
                continue;
            }
            if (current == '#') {
                add(text, cursor, end - 1, TokenTypes.COMMENT_EOL, startOffset);
                cursor = end;
                break;
            }
            if ((current == '"' || current == '\'') && hasTriple(text.array, cursor, end, current)) {
                int close = findTriple(text.array, cursor + 3, end, current);
                int type = current == '\'' ? TokenTypes.LITERAL_CHAR : TokenTypes.LITERAL_STRING_DOUBLE_QUOTE;
                if (close == -1) {
                    add(text, cursor, end - 1, type, startOffset);
                    return firstToken;
                }
                add(text, cursor, close + 2, type, startOffset);
                cursor = close + 3;
                continue;
            }
            if (current == '"' || current == '\'') {
                int tokenEnd = findStringEnd(text.array, cursor, end, current);
                int type = current == '\'' ? TokenTypes.LITERAL_CHAR : TokenTypes.LITERAL_STRING_DOUBLE_QUOTE;
                add(text, cursor, tokenEnd - 1, type, startOffset);
                cursor = tokenEnd;
                continue;
            }
            if (current == '=') {
                add(text, cursor, cursor, TokenTypes.OPERATOR, startOffset);
                value = true;
                cursor++;
                continue;
            }
            if (isSeparator(current)) {
                add(text, cursor, cursor, TokenTypes.SEPARATOR, startOffset);
                cursor++;
                continue;
            }

            int tokenEnd = cursor + 1;
            while (tokenEnd < end && !isTokenBoundary(text.array[tokenEnd])) {
                tokenEnd++;
            }
            String token = new String(text.array, cursor, tokenEnd - cursor);
            int type = value ? valueTokenType(token) : TokenTypes.DATA_TYPE;
            add(text, cursor, tokenEnd - 1, type, startOffset);
            cursor = tokenEnd;
        }

        addNullToken();
        return firstToken;
    }

    private static int valueTokenType(String token) {
        if (token.equals("true") || token.equals("false")) {
            return TokenTypes.LITERAL_BOOLEAN;
        }
        if (NUMBER.matcher(token).matches() || DATE_OR_TIME.matcher(token).matches()) {
            return token.indexOf('.') == -1 && token.indexOf('-') == -1 && token.indexOf(':') == -1
                    ? TokenTypes.LITERAL_NUMBER_DECIMAL_INT
                    : TokenTypes.LITERAL_NUMBER_FLOAT;
        }
        return TokenTypes.IDENTIFIER;
    }

    private static int findStringEnd(char[] text, int start, int end, char quote) {
        boolean escaped = false;
        for (int i = start + 1; i < end; i++) {
            char current = text[i];
            if (quote == '"' && current == '\\' && !escaped) {
                escaped = true;
                continue;
            }
            if (current == quote && !escaped) {
                return i + 1;
            }
            escaped = false;
        }
        return end;
    }

    private static int findTriple(char[] text, int start, int end, char quote) {
        for (int i = start; i + 2 < end; i++) {
            if (text[i] == quote && text[i + 1] == quote && text[i + 2] == quote) {
                return i;
            }
        }
        return -1;
    }

    private static boolean hasTriple(char[] text, int start, int end, char quote) {
        return start + 2 < end && text[start] == quote && text[start + 1] == quote && text[start + 2] == quote;
    }

    private static boolean isSeparator(char value) {
        return value == '[' || value == ']' || value == '{' || value == '}'
                || value == '(' || value == ')' || value == ',' || value == '.';
    }

    private static boolean isTokenBoundary(char value) {
        return Character.isWhitespace(value) || value == '#' || value == '=' || value == '"' || value == '\''
                || isSeparator(value);
    }

    private void add(Segment segment, int start, int end, int type, int startOffset) {
        if (end >= start) {
            addToken(segment, start, end, type, startOffset + start - segment.offset);
        }
    }
}
