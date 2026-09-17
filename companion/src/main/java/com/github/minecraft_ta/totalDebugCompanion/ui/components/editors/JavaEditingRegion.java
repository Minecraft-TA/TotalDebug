package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.JavaAnalysis;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.JavaAnalysis.Span;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.Statement;
import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import org.fife.ui.rsyntaxtextarea.parser.ParserNotice;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import static com.github.minecraft_ta.totalDebugCompanion.ui.components.editors.JavaEditorTokens.isCode;
import static com.github.minecraft_ta.totalDebugCompanion.ui.components.editors.JavaEditorTokens.isName;

/** Presentation-only edit state. Analysis remains complete, regardless of how long an edit takes. */
final class JavaEditingRegion {
    private Span construct;
    private Span caretScope;
    private Span editedName;
    private boolean includeCaretEnd;
    private int anchor;
    private int editProbeOffset;
    private List<Span> retainedStatements = List.of();

    void edited(RSyntaxDocument document, String text, int offset, int removed, int added) {
        String inserted = text.substring(offset, offset + added);
        boolean survivingPrefix = retainedStatements.stream().anyMatch(span -> span.start() < offset && span.end() == offset + removed);
        retainedStatements = retainedStatements.stream().map(span -> span.edited(offset, removed, added)).filter(Objects::nonNull).toList();
        if (removed == 0 && inserted.isBlank() && construct == null) return;
        anchor = offset + added;
        editedName = qualifiedNameAt(document, anchor);
        boolean nextStatement = added == 0 && retainedStatements.stream().anyMatch(span -> span.start() == anchor);
        if (nextStatement && !survivingPrefix) {
            finish();
            return;
        }
        editProbeOffset = Math.max(0, Math.min(text.length() - 1, removed > 0 && added == 0 && !nextStatement ? anchor : anchor - 1));
        var unit = editingUnitAt(document, text);
        // Only newly inserted delimiters commit an edit. Pre-existing (); do not finish a name.
        char last = inserted.isEmpty() ? '\0' : inserted.charAt(inserted.length() - 1);
        boolean closedUnit = (last == ';' || last == '}') && unit.span().end() == anchor;
        boolean closedCall = last == ')' && !insideArguments(document, unit.span().start(), anchor);
        if (unit.terminated() && (closedUnit || closedCall) && codeDelimiterAt(document, anchor - 1)) {
            finish();
            return;
        }
        construct = unit.span();
        var name = identifierAt(text, anchor);
        caretScope = unit.terminated() && name != null ? name : construct;
        includeCaretEnd = !unit.terminated() || name != null;
    }

    void completionAccepted(RSyntaxDocument document, String text, int caret) {
        anchor = caret;
        editedName = qualifiedNameAt(document, caret);
        editProbeOffset = Math.max(0, caret - 1);
        var unit = editingUnitAt(document, text);
        if (unit.terminated() && !insideArguments(document, unit.span().start(), caret)) finish();
        else {
            construct = unit.span();
            caretScope = construct;
            includeCaretEnd = !unit.terminated();
        }
    }

    private Unit editingUnitAt(RSyntaxDocument document, String text) {
        var unit = unitAt(document, text, editProbeOffset);
        var candidate = unit.span();
        // Recovery may join the following statement to a trailing dot. Its established boundary still belongs to it.
        int end = candidate.end();
        for (var span : retainedStatements) if (span.start() > editProbeOffset) end = Math.min(end, span.start());
        return new Unit(new Span(candidate.start(), end), unit.terminated() && end == candidate.end());
    }

    boolean caretMoved(int caret) {
        if (caretScope == null || caret >= caretScope.start()
                && (caret < caretScope.end() || includeCaretEnd && caret == caretScope.end())) return false;
        finish();
        return true;
    }

    void clearAnalysisHistory() { retainedStatements = List.of(); }

    void finish() { construct = null; caretScope = null; editedName = null; }

    boolean hides(JavaAnalysis.Problem problem) {
        // Missing types outside the qualified name being edited remain actionable, even in this statement.
        if (problem.id() == IProblem.UndefinedType && (editedName == null
                || problem.span().end() <= editedName.start() || problem.span().start() >= editedName.end())) return false;
        return construct != null && problem.level() == ParserNotice.Level.ERROR
                && problem.span().start() < construct.end() && problem.span().end() > construct.start();
    }

    /** Prefer real statement bounds when recovery retained them; never broaden a lexical boundary. */
    void analyzed(JavaAnalysis analysis) {
        if (analysis.recovery().isEmpty()) retainedStatements = analysis.statements();
        if (construct == null || analysis.contents().isEmpty()) return;
        int generated = analysis.sourceMap().toGeneratedOffset(Math.min(editProbeOffset, analysis.contents().length() - 1));
        if (generated < 0) return;
        for (ASTNode node = NodeFinder.perform(analysis.unit(), generated, 0); node != null; node = node.getParent()) {
            if (!(node instanceof Statement) || node instanceof Block) continue;
            int start = analysis.sourceMap().toEditorOffset(node.getStartPosition());
            int last = analysis.sourceMap().toEditorOffset(node.getStartPosition() + node.getLength() - 1);
            if (start >= construct.start() && last >= start && last < construct.end() && last + 1 >= anchor) {
                construct = new Span(start, last + 1);
            }
            return;
        }
    }

    /** Preserve established diagnostics where an incomplete neighbour corrupted JDT's recovery. */
    List<JavaAnalysis.Problem> reconcile(JavaAnalysis analysis, List<JavaAnalysis.Problem> previous) {
        if (construct == null || analysis.recovery().isEmpty()) return analysis.problems();
        var result = new ArrayList<JavaAnalysis.Problem>();
        analysis.problems().stream().filter(problem -> !inRetainedStatement(problem)).forEach(result::add);
        previous.stream().filter(this::inRetainedStatement).forEach(result::add);
        return result;
    }

    private boolean inRetainedStatement(JavaAnalysis.Problem problem) {
        return retainedStatements.stream().anyMatch(span -> problem.span().start() >= span.start() && problem.span().end() <= span.end()
                && (span.end() <= construct.start() || span.start() >= construct.end()));
    }

    private static Span qualifiedNameAt(RSyntaxDocument document, int caret) {
        int start = -1, end = -1;
        boolean afterDot = false;
        for (var token : document) {
            if (!isCode(token)) continue;
            String word = token.getLexeme();
            boolean dot = word.equals(".");
            boolean name = isName(word);
            if (!dot && (!name || !afterDot)) {
                if (start >= 0 && start <= caret && caret <= end) return new Span(start, end);
                start = name ? token.getOffset() : -1;
            }
            if (start >= 0) end = token.getEndOffset();
            afterDot = dot;
        }
        return start >= 0 && start <= caret && caret <= end ? new Span(start, end) : null;
    }

    private static Span identifierAt(String text, int caret) {
        int start = caret, end = caret;
        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) start--;
        while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end))) end++;
        return start < end ? new Span(start, end) : null;
    }

    private record Unit(Span span, boolean terminated) { }

    /** Find statement boundaries with the existing lexer, including syntax JDT discarded after a dot. */
    private static Unit unitAt(RSyntaxDocument document, String text, int point) {
        int start = 0, parentheses = 0, brackets = 0;
        var blocks = new ArrayDeque<int[]>();
        for (var token : document) {
            if (!isCode(token)) continue;
            String word = token.getLexeme();
            boolean boundary = false;
            switch (word) {
                case "(" -> parentheses++;
                case ")" -> parentheses = Math.max(0, parentheses - 1);
                case "[" -> brackets++;
                case "]" -> brackets = Math.max(0, brackets - 1);
                case "{" -> {
                    blocks.push(new int[]{parentheses, brackets});
                    parentheses = brackets = 0;
                    boundary = true;
                }
                case "}" -> {
                    var outer = blocks.poll();
                    parentheses = outer == null ? 0 : outer[0];
                    brackets = outer == null ? 0 : outer[1];
                    boundary = true;
                }
                case ";" -> boundary = parentheses == 0 && brackets == 0;
                default -> { }
            }
            if (boundary) {
                int end = token.getEndOffset();
                if (point < end) return new Unit(new Span(skipWhitespace(text, start), end), true);
                start = end;
            }
        }
        return new Unit(new Span(skipWhitespace(text, start), text.length()), false);
    }

    private static int skipWhitespace(String text, int start) {
        while (start < text.length() && Character.isWhitespace(text.charAt(start))) start++;
        return start;
    }

    private static boolean insideArguments(RSyntaxDocument document, int start, int caret) {
        int depth = 0;
        for (var token : document) {
            if (token.getOffset() < start || token.getOffset() >= caret || !isCode(token)) continue;
            switch (token.getLexeme()) {
                case "(", "[" -> depth++;
                case ")", "]" -> depth--;
                default -> { }
            }
        }
        return depth > 0;
    }

    private static boolean codeDelimiterAt(RSyntaxDocument document, int offset) {
        for (var token : document) {
            if (token.getOffset() == offset && token.length() == 1 && isCode(token)) return true;
        }
        return false;
    }

}
