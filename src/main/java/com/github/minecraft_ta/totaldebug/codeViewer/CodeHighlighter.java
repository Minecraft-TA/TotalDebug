package com.github.minecraft_ta.totaldebug.codeViewer;

import com.github.javaparser.*;
import org.apache.commons.lang3.tuple.Triple;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class CodeHighlighter {

    public static List<String> getHighlightedJavaCode(String code) {
        List<String> lines = Arrays.asList(code.split("\n"));
        ParserConfiguration conf = new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_8);

        TokenRange globalTokenRange;
        try {
            globalTokenRange = JavaParserHelper.parse(code, false).getTokenRange().get();
        } catch (Throwable t) {
            return Collections.emptyList();
        }

        int currentLine = 0;
        List<Triple<Integer, Integer, String>> lineReplacements = new ArrayList<>();

        for (JavaToken javaToken : globalTokenRange) {
            Range range = javaToken.getRange().get();
            String colorCode = getColorCode(javaToken);

            if (!colorCode.isEmpty()) {
                int line = range.begin.line - 1;
                if (line > currentLine) {
                    lines.set(currentLine, replaceInLine(lines.get(currentLine), lineReplacements));
                    currentLine = line;

                    lineReplacements.clear();
                }

                int from = range.begin.column - 1;
                int to = range.end.column;

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

    private static String getColorCode(JavaToken token) {
        switch (token.getCategory()) {
            case KEYWORD:
                return "\u00a76";
            case LITERAL:
                if (token.getKind() == JavaToken.Kind.STRING_LITERAL.getKind())
                    return "\u00a7a";
                return "\u00a73";
            case OPERATOR:
            case SEPARATOR:
                return "\u00a77";
            case COMMENT:
                return "\u00a78";
        }

        return "";
    }
}
