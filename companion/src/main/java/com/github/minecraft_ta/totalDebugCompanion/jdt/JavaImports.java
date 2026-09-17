package com.github.minecraft_ta.totalDebugCompanion.jdt;

import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.compiler.IScanner;
import org.eclipse.jdt.core.compiler.ITerminalSymbols;
import org.eclipse.jdt.core.compiler.InvalidInputException;

import java.util.ArrayList;
import java.util.List;

/** Shared prefix-import boundaries for source generation and editor presentation, including incomplete declarations. */
public final class JavaImports {
    private JavaImports() { }

    public record Import(int editorStart, int editorEnd, String name, int nameStart, int nameEnd, boolean complete) { }

    public static List<Import> read(String editorText) {
        List<Import> result = new ArrayList<>();
        IScanner scanner = null;
        int offset = 0;
        while (offset < editorText.length()) {
            offset = skipTrivia(editorText, offset);
            if (!keywordAt(editorText, offset, "import")) {
                break;
            }
            if (scanner == null) {
                scanner = ToolFactory.createScanner(false, false, false, JdtConfiguration.JAVA_VERSION);
                scanner.setSource(editorText.toCharArray());
            }
            int end = importEnd(scanner, editorText, offset + "import".length());
            int segmentEnd = includeLineTerminator(editorText, end);
            result.add(describe(scanner, editorText, offset, end, segmentEnd));
            offset = segmentEnd;
        }
        return result;
    }

    private static int skipTrivia(String text, int offset) {
        int current = offset;
        while (current < text.length()) {
            char character = text.charAt(current);
            if (Character.isWhitespace(character)) {
                current++;
                continue;
            }
            if (character != '/' || current + 1 >= text.length()) {
                break;
            }
            char next = text.charAt(current + 1);
            if (next == '/') {
                int lineEnd = text.indexOf('\n', current + 2);
                current = lineEnd < 0 ? text.length() : lineEnd + 1;
                continue;
            }
            if (next == '*') {
                int commentEnd = text.indexOf("*/", current + 2);
                current = commentEnd < 0 ? text.length() : commentEnd + 2;
                continue;
            }
            break;
        }
        return current;
    }

    private static boolean keywordAt(String text, int offset, String keyword) {
        if (offset < 0 || !text.startsWith(keyword, offset)) {
            return false;
        }
        int end = offset + keyword.length();
        return (offset == 0 || !Character.isJavaIdentifierPart(text.charAt(offset - 1)))
                && (end == text.length() || !Character.isJavaIdentifierPart(text.charAt(end)));
    }

    /** Preserve incomplete imports in the header, but never consume body code looking for their missing semicolon. */
    private static int importEnd(IScanner scanner, String text, int offset) {
        if (offset >= text.length()) return offset;
        scanner.resetTo(offset, text.length() - 1);
        int end = offset, beforeLineName = -1;
        boolean first = true, expectName = true, wildcard = false;
        try {
            while (true) {
                int token = scanner.getNextToken();
                if (first && token == ITerminalSymbols.TokenNamestatic) {
                    end = scanner.getCurrentTokenEndPosition() + 1;
                    first = false;
                    continue;
                }
                first = false;
                if (expectName && token == ITerminalSymbols.TokenNameIdentifier) {
                    int lineBreak = text.indexOf('\n', end);
                    if (beforeLineName < 0 && lineBreak >= 0 && lineBreak < scanner.getCurrentTokenStartPosition()) beforeLineName = end;
                    expectName = false;
                } else if (!expectName && !wildcard && token == ITerminalSymbols.TokenNameDOT) {
                    expectName = true;
                } else if (expectName && end > offset && token == ITerminalSymbols.TokenNameMULTIPLY) {
                    expectName = false;
                    wildcard = true;
                } else if (token == ITerminalSymbols.TokenNameSEMICOLON) return scanner.getCurrentTokenEndPosition() + 1;
                else {
                    // An incomplete import must not consume the type in the next line's "Type variable" declaration.
                    int nextLine = text.indexOf('\n', end);
                    boolean startsNextLine = nextLine >= 0 && nextLine < scanner.getCurrentTokenStartPosition();
                    return beforeLineName >= 0 && !startsNextLine && token != ITerminalSymbols.TokenNameEOF ? beforeLineName : end;
                }
                end = scanner.getCurrentTokenEndPosition() + 1;
            }
        } catch (InvalidInputException incomplete) {
            return end;
        }
    }

    private static int includeLineTerminator(String text, int offset) {
        int current = offset;
        while (current < text.length() && (text.charAt(current) == ' ' || text.charAt(current) == '\t')) {
            current++;
        }
        if (current < text.length() && text.charAt(current) == '\r') {
            current++;
        }
        return current < text.length() && text.charAt(current) == '\n' ? current + 1 : offset;
    }

    private static Import describe(IScanner scanner, String text, int start, int end, int segmentEnd) {
        var name = new StringBuilder();
        int nameStart = -1, nameEnd = -1;
        scanner.resetTo(start, Math.max(start, end - 1));
        try {
            for (int token = scanner.getNextToken(); token != ITerminalSymbols.TokenNameEOF; token = scanner.getNextToken()) {
                if (token == ITerminalSymbols.TokenNameimport || token == ITerminalSymbols.TokenNameSEMICOLON) continue;
                if (token == ITerminalSymbols.TokenNamestatic) { name.append("static "); continue; }
                if (nameStart < 0) nameStart = scanner.getCurrentTokenStartPosition();
                name.append(scanner.getCurrentTokenSource());
                if (token == ITerminalSymbols.TokenNameIdentifier) nameEnd = scanner.getCurrentTokenEndPosition() + 1;
            }
        } catch (InvalidInputException incomplete) {
            // Bounds still preserve the original incomplete header; no executable text is repaired here.
        }
        return new Import(start, segmentEnd, name.toString(), nameStart, nameEnd,
                end > start && text.charAt(end - 1) == ';' && nameStart >= 0);
    }
}
