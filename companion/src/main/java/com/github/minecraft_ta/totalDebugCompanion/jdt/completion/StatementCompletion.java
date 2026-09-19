package com.github.minecraft_ta.totalDebugCompanion.jdt.completion;

import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaSnippetSource;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JdtConfiguration;
import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.compiler.InvalidInputException;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ArrayCreation;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.Statement;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.eclipse.jdt.core.compiler.ITerminalSymbols.*;

/** Syntax-only, editor-coordinate repairs. Never consults bindings or the generated script wrapper. */
public final class StatementCompletion {
    public record Edit(int offset, int length, String text) { }
    public record Plan(List<Edit> edits, int caret) { }
    private record Token(int kind, int start, int end, String text) {
        boolean is(String value) { return text.equals(value); }
        boolean trivia() { return kind == TokenNameCOMMENT_LINE || kind == TokenNameCOMMENT_BLOCK || kind == TokenNameCOMMENT_JAVADOC; }
    }
    private static final Set<String> HEADERS = Set.of("if", "while", "for", "else");
    private static final Set<String> NEEDS_OPERAND = Set.of("=", "+", "-", "*", "/", "%", "&&", "||", "&", "|", "^", "!", "~", "==", "!=", "<", ">", "<=", ">=", "?", ":", ",", ".", "+=", "-=", "*=", "/=", "return", "throw", "new", "instanceof", "import", "static");

    private final String source;
    private final String newline;
    private final String indentUnit;
    private final int caret;
    private final List<Token> tokens;
    private final List<Token> comments;
    private final List<Edit> edits = new ArrayList<>();

    private StatementCompletion(String source, int caret, String indentUnit, List<Token> tokens) {
        this.source = source;
        this.caret = caret;
        this.indentUnit = indentUnit;
        this.newline = source.contains("\r\n") ? "\r\n" : "\n";
        this.tokens = tokens.stream().filter(t -> !t.trivia()).toList();
        this.comments = tokens.stream().filter(Token::trivia).toList();
    }

    public static Plan plan(String source, int caret, String indentUnit) {
        if (caret < 0 || caret > source.length()) throw new IllegalArgumentException("Caret outside source");
        var all = scan(source);
        for (var token : all) {
            if (token.trivia() && token.start <= caret && caret < token.end)
                return new Plan(List.of(), caret);
            if (token.kind == TokenNameCOMMENT_LINE && token.start <= caret && caret == token.end
                    && !token.text.endsWith("\n") && !token.text.endsWith("\r"))
                return new Plan(List.of(), caret);
        }
        return new StatementCompletion(source, caret, indentUnit, all).complete();
    }

    private Plan complete() {
        int lineStart = source.lastIndexOf('\n', caret - 1) + 1;
        int lineEnd = source.indexOf('\n', caret);
        if (lineEnd < 0) lineEnd = source.length();
        if (source.substring(lineStart, lineEnd).isBlank()) return result(caret);
        int start = statementStart();
        int first = 0;
        while (first < tokens.size() && tokens.get(first).start < start) first++;
        if (first == tokens.size()) return result(caret);
        var token = tokens.get(first);
        if (token.start > lineEnd) return result(caret);
        if (HEADERS.contains(token.text)) return header(first);
        if (Set.of("switch", "try", "catch", "finally", "do", "synchronized", "class", "interface", "enum", "record", "}").contains(token.text))
            return result(caret);
        return statement(first);
    }

    private int statementStart() {
        // Recovery supplies starts for multiline and nested statements; token boundaries constrain repairs below.
        var parser = JdtConfiguration.createParser();
        parser.setKind(ASTParser.K_STATEMENTS);
        parser.setStatementsRecovery(true);
        parser.setSource(JavaSnippetSource.splitImports(source).body().toCharArray());
        ASTNode root = parser.createAST(null);
        int[] best = {-1, Integer.MAX_VALUE};
        boolean tokenStartsAtCaret = tokens.stream().anyMatch(token -> token.start == caret);
        root.accept(new ASTVisitor() {
            @Override public void preVisit(ASTNode node) {
                if (!(node instanceof Statement) || node instanceof Block) return;
                int start = node.getStartPosition(), end = start + node.getLength();
                boolean trailingSpace = end < caret && source.substring(end, caret).isBlank()
                        && !source.substring(end, caret).contains("\n");
                if (start <= caret && (caret < end || caret == end && !tokenStartsAtCaret || trailingSpace) && node.getLength() < best[1]) {
                    best[0] = start;
                    best[1] = node.getLength();
                }
            }
        });
        int start = 0, parentheses = 0, brackets = 0;
        for (int i = 0; i < tokens.size() && tokens.get(i).end <= caret; i++) {
            var token = tokens.get(i);
            if (token.is("(")) parentheses++;
            if (token.is(")")) parentheses = Math.max(0, parentheses - 1);
            if (token.is("[")) brackets++;
            if (token.is("]")) brackets = Math.max(0, brackets - 1);
            if (token.is("{") || token.is("}")) { start = token.end; parentheses = brackets = 0; }
            if (token.is(";") && parentheses == 0 && brackets == 0 && token.end < caret) start = token.end;
            if (i + 1 < tokens.size() && tokens.get(i + 1).start <= caret && breaksLine(token, tokens.get(i + 1)) && parentheses == 0 && brackets == 0)
                start = tokens.get(i + 1).start;
        }
        int boundary = start;
        var first = tokens.stream().filter(t -> t.start >= boundary).findFirst().orElse(null);
        if (first != null && HEADERS.contains(first.text)) {
            int depth = 0;
            boolean closed = false;
            for (var token : tokens) {
                if (token.start < first.end || token.start >= caret) continue;
                if (token.is("(")) depth++;
                if (token.is(")")) {
                    depth--;
                    if (depth == 0) { closed = token.end < caret; break; }
                }
            }
            if (!closed) return start;
        }
        return best[0] >= 0 ? best[0] : start;
    }

    private Plan statement(int first) {
        var stack = new ArrayList<String>();
        int last = first;
        for (int i = first; i < tokens.size(); i++) {
            var token = tokens.get(i);
            if (i > first && stack.isEmpty() && breaksLine(tokens.get(i - 1), token)
                    && validStatement(source.substring(tokens.get(first).start, tokens.get(i - 1).end) + ";")) break;
            if (token.is("}") && stack.isEmpty()) break;
            if (token.is(";") && !stack.contains("{")) { last = i; break; }
            if (token.is("(") || token.is("[") || token.is("{")) stack.add(token.text);
            else if (token.is(")") || token.is("]") || token.is("}")) {
                if (stack.isEmpty() || !closes(stack.removeLast(), token.text)) return result(caret);
            }
            last = i;
        }
        var endToken = tokens.get(last);
        if (endToken.end < caret && !source.substring(endToken.end, caret).isBlank()) return result(caret);
        var lastContent = endToken.is(";") && last > first ? tokens.get(last - 1) : endToken;
        boolean wildcardImport = tokens.get(first).is("import") && lastContent.is("*");
        if (NEEDS_OPERAND.contains(lastContent.text) && !wildcardImport)
            return result(endToken.is(";") ? endToken.start : Math.max(caret, lastContent.end));
        if (stack.contains("{")) return result(caret); // A missing lambda/initializer body is not a call repair.
        StringBuilder suffix = new StringBuilder();
        for (int i = stack.size() - 1; i >= 0; i--) suffix.append(stack.get(i).equals("(") ? ')' : ']');
        int insertAt = endToken.is(";") ? endToken.start : endToken.end;
        String text = source.substring(tokens.get(first).start, insertAt) + suffix + ";";
        if (!validStatement(text)) return result(caret);
        if (!endToken.is(";")) suffix.append(';');
        add(insertAt, suffix.toString());
        return nextLine(endToken.end, indentation(tokens.get(first).start));
    }

    private Plan header(int first) {
        var keyword = tokens.get(first);
        int next = first + 1;
        int end = keyword.end;
        int hole = -1;
        boolean insertedCondition = false;
        if (keyword.is("else")) {
            if (next < tokens.size() && tokens.get(next).is("if")) return header(next);
        } else if (next >= tokens.size() || !tokens.get(next).is("(")) {
            if (next < tokens.size() && !tokens.get(next).is("{")) return result(caret);
            add(end, " ()");
            hole = end;
            insertedCondition = true;
        } else {
            int open = next++, depth = 1;
            for (; next < tokens.size(); next++) {
                var token = tokens.get(next);
                if (token.is("{") || token.is("}")) break;
                if (token.is("(")) depth++;
                if (token.is(")")) depth--;
                if (depth == 0) break;
            }
            boolean closed = next < tokens.size() && tokens.get(next).is(")") && depth == 0;
            int insideEnd = closed ? tokens.get(next).start : next > open + 1 ? tokens.get(next - 1).end : tokens.get(open).end;
            String inside = source.substring(tokens.get(open).end, insideEnd);
            end = closed ? tokens.get(next++).end : insideEnd;
            if (inside.isBlank()) hole = tokens.get(open).end;
            else {
                var content = scan(inside).stream().filter(t -> !t.trivia()).toList();
                if (content.isEmpty()) hole = tokens.get(open).end;
                else {
                    boolean missingOperand = NEEDS_OPERAND.contains(content.getLast().text);
                    String checked = inside + (missingOperand ? " __statementInput" : "")
                            + ")".repeat(Math.max(0, depth - 1));
                    if (!validStatement(keyword.text + " (" + checked + ") {}")) return result(caret);
                    if (missingOperand) hole = insideEnd;
                }
            }
            if (!closed) add(end, ")".repeat(depth));
        }
        String indent = indentation(keyword.start);
        if (next < tokens.size() && tokens.get(next).is("{")) {
            var brace = tokens.get(next);
            if (hole >= 0) return result(insertedCondition ? mappedBefore(hole) + 2 : mappedBefore(hole));
            if (next + 1 < tokens.size() && !tokens.get(next + 1).is("}")) return result(mapped(tokens.get(next + 1).start));
            int close = next + 1 < tokens.size() ? tokens.get(next + 1).start : source.length();
            String gap = source.substring(brace.end, close);
            if (!gap.isBlank()) return result(mapped(brace.end)); // Preserve comments in an otherwise empty body.
            if (gap.contains("\n") && next + 1 < tokens.size()) {
                int line = source.indexOf('\n', brace.end) + 1;
                int content = line;
                while (content < close && (source.charAt(content) == ' ' || source.charAt(content) == '\t')) content++;
                if (content < close) return result(mapped(content));
            }
            String inner = newline + indent + indentUnit;
            String ending = newline + indent + (next + 1 < tokens.size() ? "" : "}");
            edits.add(new Edit(brace.end, gap.length(), inner + ending));
            return result(mappedBefore(brace.end) + inner.length());
        }
        if (next < tokens.size() && !tokens.get(next).is("}") && !tokens.get(next).is("else")) {
            // An existing unbraced body is left intact. Never adopt a following line after an incomplete header.
            return result(hole >= 0 ? mapped(hole) : mapped(tokens.get(next).start));
        }
        int bodyEnd = trailingCommentsEnd(end);
        add(end, " {");
        add(bodyEnd, newline + indent + indentUnit + newline + indent + "}");
        return result(hole >= 0 ? mappedBefore(hole) + (insertedCondition ? 2 : 0) : mapped(bodyEnd) - (newline + indent + "}").length());
    }

    /** A trailing block comment can span lines; only its end is an editing boundary. */
    private int trailingCommentsEnd(int end) {
        for (var comment : comments) {
            if (comment.start < end) continue;
            String gap = source.substring(end, comment.start);
            if (!gap.isBlank() || gap.contains("\n")) break;
            end = comment.end;
            if (comment.kind == TokenNameCOMMENT_LINE) {
                while (end > comment.start && (source.charAt(end - 1) == '\n' || source.charAt(end - 1) == '\r')) end--;
                break;
            }
        }
        return end;
    }

    private Plan nextLine(int statementEnd, String indent) {
        int end = trailingCommentsEnd(statementEnd);
        int lineEnd = source.indexOf('\n', end);
        if (lineEnd < 0) lineEnd = source.length();
        // Split after complete trailing comments, before any following same-line statement.
        var following = tokens.stream().filter(t -> t.start >= end).findFirst().orElse(null);
        int position = following != null && following.start < lineEnd ? end : lineEnd;
        if (position > end && source.charAt(position - 1) == '\r') position--;
        if (position < source.length() && (source.charAt(position) == '\n' || source.startsWith("\r\n", position))) {
            int after = source.charAt(position) == '\r' ? position + 2 : position + 1;
            int content = after;
            while (content < source.length() && (source.charAt(content) == ' ' || source.charAt(content) == '\t')) content++;
            if (content == source.length() || source.charAt(content) == '\n' || source.charAt(content) == '\r') {
                if (content == after) add(after, indent);
                return result(mapped(content));
            }
        }
        int length = 0;
        if (following != null && following.start < lineEnd && position == end && source.substring(end, following.start).isBlank())
            length = following.start - end;
        String beforeClosingBrace = "";
        if (following != null && following.is("}") && following.start < lineEnd) {
            int outerLength = indent.endsWith(indentUnit) ? indent.length() - indentUnit.length() : 0;
            beforeClosingBrace = newline + indent.substring(0, outerLength);
        }
        edits.add(new Edit(position, length, newline + indent + beforeClosingBrace));
        return result(mapped(position) + length - beforeClosingBrace.length());
    }

    private boolean breaksLine(Token left, Token right) {
        if (!source.substring(left.end, right.start).contains("\n")) return false;
        return !NEEDS_OPERAND.contains(left.text) && !left.is("(") && !left.is("[")
                && !right.is(".") && !right.is("(") && !right.is("[") && !right.is(")") && !right.is("]")
                && !right.is(";") && !right.is(",") && !NEEDS_OPERAND.contains(right.text);
    }

    private String indentation(int offset) {
        int start = source.lastIndexOf('\n', offset - 1) + 1;
        int end = start;
        while (end < source.length() && (source.charAt(end) == ' ' || source.charAt(end) == '\t')) end++;
        int depth = 0;
        for (var token : tokens) {
            if (token.start < start || token.start >= offset) continue;
            if (token.is("{")) depth++;
            if (token.is("}")) depth = Math.max(0, depth - 1);
        }
        return source.substring(start, end) + indentUnit.repeat(depth);
    }

    private static boolean validStatement(String text) {
        var parser = JdtConfiguration.createParser();
        if (text.startsWith("import") && text.length() > 6 && Character.isWhitespace(text.charAt(6))) parser.setKind(ASTParser.K_COMPILATION_UNIT);
        else parser.setKind(ASTParser.K_STATEMENTS);
        parser.setSource(text.toCharArray());
        var root = parser.createAST(null);
        boolean[] valid = {!(root instanceof Block block) || block.statements().size() == 1};
        root.accept(new ASTVisitor() {
            @Override public void preVisit(ASTNode node) {
                if ((node.getFlags() & (ASTNode.MALFORMED | ASTNode.RECOVERED)) != 0) valid[0] = false;
                if (node instanceof ArrayCreation array && array.dimensions().isEmpty() && array.getInitializer() == null) valid[0] = false;
            }
        });
        return valid[0];
    }

    private static boolean closes(String open, String close) {
        return open.equals("(") && close.equals(")") || open.equals("[") && close.equals("]") || open.equals("{") && close.equals("}");
    }

    private static List<Token> scan(String source) {
        var scanner = ToolFactory.createScanner(true, false, false, JdtConfiguration.JAVA_VERSION);
        scanner.setSource(source.toCharArray());
        var tokens = new ArrayList<Token>();
        try {
            int kind;
            while ((kind = scanner.getNextToken()) != TokenNameEOF) {
                int start = scanner.getCurrentTokenStartPosition(), end = scanner.getCurrentTokenEndPosition() + 1;
                tokens.add(new Token(kind, start, end, source.substring(start, end)));
            }
        } catch (InvalidInputException invalid) {
            // Treat an unfinished literal as opaque through EOF. Never add punctuation inside it.
            tokens.add(new Token(TokenNameStringLiteral, scanner.getCurrentTokenStartPosition(), source.length(), source.substring(scanner.getCurrentTokenStartPosition())));
        }
        return tokens;
    }

    private void add(int offset, String text) { if (!text.isEmpty()) edits.add(new Edit(offset, 0, text)); }
    private int mapped(int offset) { return offset + edits.stream().filter(e -> e.offset <= offset).mapToInt(e -> e.text.length() - e.length).sum(); }
    private int mappedBefore(int offset) { return offset + edits.stream().filter(e -> e.offset < offset).mapToInt(e -> e.text.length() - e.length).sum(); }
    private Plan result(int caret) { return new Plan(List.copyOf(edits), caret); }
}
