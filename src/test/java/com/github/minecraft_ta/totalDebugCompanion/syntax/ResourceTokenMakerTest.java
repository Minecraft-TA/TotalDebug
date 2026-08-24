package com.github.minecraft_ta.totalDebugCompanion.syntax;

import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenMaker;
import org.fife.ui.rsyntaxtextarea.TokenTypes;
import org.junit.jupiter.api.Test;

import javax.swing.text.Segment;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceTokenMakerTest {

    @Test
    void tokenizesTomlKeysValuesAndComments() {
        List<TokenInfo> tokens = tokens(new TomlTokenMaker(), "enabled = true # comment", TokenTypes.NULL);

        assertTrue(tokens.contains(new TokenInfo("enabled", TokenTypes.DATA_TYPE)));
        assertTrue(tokens.contains(new TokenInfo("=", TokenTypes.OPERATOR)));
        assertTrue(tokens.contains(new TokenInfo("true", TokenTypes.LITERAL_BOOLEAN)));
        assertTrue(tokens.contains(new TokenInfo("# comment", TokenTypes.COMMENT_EOL)));
    }

    @Test
    void carriesTomlMultilineStringsAcrossLines() {
        TomlTokenMaker maker = new TomlTokenMaker();
        Segment first = segment("description = \"\"\"first");
        int continuation = maker.getLastTokenTypeOnLine(first, TokenTypes.NULL);
        assertEquals(TokenTypes.LITERAL_STRING_DOUBLE_QUOTE, continuation);

        List<TokenInfo> tokens = tokens(maker, "second\"\"\" # done", continuation);
        assertEquals(new TokenInfo("second\"\"\"", TokenTypes.LITERAL_STRING_DOUBLE_QUOTE), tokens.getFirst());
        assertTrue(tokens.contains(new TokenInfo("# done", TokenTypes.COMMENT_EOL)));
    }

    @Test
    void tokenizesManifestHeadersAndContinuations() {
        List<TokenInfo> header = tokens(new ManifestTokenMaker(), "Manifest-Version: 1.0", TokenTypes.NULL);
        assertEquals(new TokenInfo("Manifest-Version", TokenTypes.RESERVED_WORD), header.getFirst());
        assertTrue(header.contains(new TokenInfo(":", TokenTypes.OPERATOR)));
        assertTrue(header.contains(new TokenInfo("1.0", TokenTypes.LITERAL_STRING_DOUBLE_QUOTE)));

        List<TokenInfo> continuation = tokens(new ManifestTokenMaker(), " continued", TokenTypes.NULL);
        assertEquals(new TokenInfo(" ", TokenTypes.WHITESPACE), continuation.getFirst());
        assertEquals(new TokenInfo("continued", TokenTypes.LITERAL_STRING_DOUBLE_QUOTE), continuation.get(1));
    }

    private static List<TokenInfo> tokens(TokenMaker maker, String line, int initialType) {
        Token token = maker.getTokenList(segment(line), initialType, 0);
        List<TokenInfo> result = new ArrayList<>();
        while (token != null && token.isPaintable()) {
            result.add(new TokenInfo(token.getLexeme(), token.getType()));
            token = token.getNextToken();
        }
        return result;
    }

    private static Segment segment(String line) {
        char[] chars = line.toCharArray();
        return new Segment(chars, 0, chars.length);
    }

    private record TokenInfo(String text, int type) {
    }
}
