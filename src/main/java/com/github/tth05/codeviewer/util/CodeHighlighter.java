package com.github.tth05.codeviewer.util;

import java.util.List;

public class CodeHighlighter {

    private static final String[] JAVA_KEYWORDS = {
            "abstract", "continue", "for", "new", "switch",
            "assert", "default	", "goto", "package", "synchronized",
            "boolean", "do", "if", "private", "this",
            "break", "double", "implements", "protected", "throw",
            "byte", "else", "import", "public", "throws",
            "case", "enum", "instanceof", "return", "transient",
            "catch", "extends", "int", "short", "try",
            "char", "final", "interface", "static", "void",
            "class", "finally", "long", "volatile",
            "const", "float", "native", "super", "while"
    };

    public static void highlightJavaCode(List<String> lines) {
        for (int i = 0, linesSize = lines.size(); i < linesSize; i++) {
            String line = lines.get(i);

            for (String javaKeyword : JAVA_KEYWORDS) {
                line = line.replace(javaKeyword, "\u00a7d" + javaKeyword + "\u00a7r");
            }

            lines.set(i, line);
        }
    }
}
