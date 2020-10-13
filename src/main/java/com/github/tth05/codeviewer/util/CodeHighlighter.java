package com.github.tth05.codeviewer.util;

import com.github.tth05.codeviewer.lexer.java.Java9Lexer;
import com.github.tth05.codeviewer.lexer.java.Java9Parser;
import org.antlr.v4.runtime.*;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

//Thanks to RubbaBoy and his MSPaintIDE for the help on this https://github.com/MSPaintIDE/MSPaintIDE
public class CodeHighlighter {

    private static final List<Pair<List<String>, String>> javaTokenPairs = new ArrayList<>();

    static {
        javaTokenPairs.add(Pair.of(Arrays.asList("'transitive'", "'abstract'", "'assert'", "'boolean'", "'break'",
                "'byte'", "'case'", "'catch'", "'char'", "'class'", "'const'", "'continue'", "'default'", "'do'",
                "'double'", "'else'", "'enum'", "'extends'", "'final'", "'finally'", "'float'", "'for'", "'if'",
                "'goto'", "'implements'", "'import'", "'instanceof'", "'int'", "'interface'", "'long'", "'native'",
                "'new'", "'package'", "'private'", "'protected'", "'public'", "'return'", "'short'", "'static'",
                "'strictfp'", "'super'", "'switch'", "'synchronized'", "'this'", "'throw'", "'throws'", "'transient'",
                "'try'", "'void'", "'volatile'", "'while'", "BooleanLiteral", "CharacterLiteral", "'null'"), "\u00a7d"));
        javaTokenPairs.add(Pair.of(Collections.singletonList("StringLiteral"), "\u00a7a"));
        javaTokenPairs.add(Pair.of(Arrays.asList("IntegerLiteral", "FloatingPointLiteral"), "\u00a73"));
        javaTokenPairs.add(Pair.of(Arrays.asList("'_'", "'('", "')'", "'{'", "'}'", "'['", "']'", "';'", "','", "'.'",
                "'...'", "'@'", "'::'", "'='", "'>'", "'<'", "'!'", "'~'", "'?'", "':'", "'->'", "'=='", "'<='", "'>='",
                "'!='", "'&&'", "'||'", "'++'", "'--'", "'+'", "'-'", "'*'", "'/'", "'&'", "'|'", "'^'", "'%'", "'+='",
                "'-='", "'*='", "'/='", "'&='", "'|='", "'^='", "'%='", "'<<='", "'>>='", "'>>>='"), "\u00a77"));
        javaTokenPairs.add(Pair.of(Collections.singletonList("Identifier"), "\u00a7f"));
    }

    public static List<String> getHighlightedJavaCode(String str) {
        Java9Lexer lexer = new Java9Lexer(CharStreams.fromString(str));

        //TODO: detect newline character
        List<String> lines = Arrays.asList(str.split("\r\n"));

        lexer.setTokenFactory(new CommonTokenFactory(true));

        TokenStream tokens = new UnbufferedTokenStream<CommonToken>(lexer);
        Java9Parser parser = new Java9Parser(tokens);

        parser.setBuildParseTree(false);

        Vocabulary vocabulary = parser.getVocabulary();

        int currentLine = 0;
        List<Triple<Integer, Integer, String>> lineReplacements = new ArrayList<>();

        int i = 0;
        for (Token token; (token = tokens.get(i)).getType() != Token.EOF; i++, tokens.consume()) {
            if (token.getText().trim().isEmpty()) continue;

            String colorCode = getColorCode(vocabulary.getDisplayName(token.getType()));

            if (!colorCode.isEmpty()) {
                int line = token.getLine() - 1;
                if (line > currentLine) {
                    lines.set(currentLine, replaceInLine(lines.get(currentLine), lineReplacements));
                    currentLine = line;

                    lineReplacements.clear();
                }

                int from = token.getCharPositionInLine();
                int to = from + token.getText().length();

                lineReplacements.add(Triple.of(from, to, colorCode));
            }
        }

        lines.set(currentLine, replaceInLine(lines.get(currentLine), lineReplacements));

        return lines;
    }

    private static String replaceInLine(String str, List<Triple<Integer, Integer, String>> lineReplacements) {
        StringBuilder builder = new StringBuilder(str);

        int offset = 0;

        for (Triple<Integer, Integer, String> r : lineReplacements) {

            builder.replace(
                    r.getLeft() + offset,
                    r.getMiddle() + offset,
                    r.getRight() + builder.substring(r.getLeft() + offset, r.getMiddle() + offset) + "\u00a7r"
            );

            //add color code and reset code to offset
            offset += r.getRight().length() + 2;
        }

        return builder.toString();
    }

    private static String getColorCode(String tokenName) {
        for (Pair<List<String>, String> javaTokenPair : javaTokenPairs) {
            if (javaTokenPair.getLeft().contains(tokenName))
                return javaTokenPair.getRight();
        }

        return "";
    }
}
