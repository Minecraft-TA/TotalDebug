package com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics;

import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaAst;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JDTHacks;
import com.github.minecraft_ta.totalDebugCompanion.jdt.semanticHighlighting.SemanticTokensVisitor;
import com.github.minecraft_ta.totalDebugCompanion.jdt.insight.SourceDeclaration;
import com.github.minecraft_ta.totalDebugCompanion.jdt.insight.SourceDeclarationAnalyzer;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.Statement;
import org.fife.ui.rsyntaxtextarea.parser.ParserNotice;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One captured source and its prepared analysis. Published JDT trees must not be mutated. */
public record JavaAnalysis(long revision, Object environment, CompilationUnit unit, String contents,
                           JavaSourceMap sourceMap, Map<Integer, Integer> tokens, List<Problem> problems,
                           List<Span> recovery, boolean importsChecked, List<SourceDeclaration> declarations, List<Span> statements) {
    public JavaAnalysis {
        tokens = Map.copyOf(tokens);
        problems = List.copyOf(problems);
        recovery = List.copyOf(recovery);
        declarations = List.copyOf(declarations);
        statements = List.copyOf(statements);
    }

    public static JavaAnalysis parse(String className, String text, JavaEditorSource source, long revision, Object environment) {
        var ast = JavaAst.parse(className, source.text());
        var errors = Arrays.stream(ast.getProblems()).filter(IProblem::isError).toList();
        boolean syntaxErrors = errors.stream().anyMatch(problem -> (problem.getID() & IProblem.Syntax) != 0);
        // Import use is unreliable when recovery discarded syntax. Semantic errors alone do not discard uses.
        boolean importsChecked = errors.isEmpty();
        if (!errors.isEmpty() && !syntaxErrors) {
            JDTHacks.checkUnusedImports(ast);
            importsChecked = true;
        }
        var problems = new ArrayList<Problem>();
        var recovery = new ArrayList<Span>();
        for (var problem : ast.getProblems()) {
            if (source.privilegedAccess() && JavaEditorSource.isAccessProblem(problem.getID())) continue;
            boolean syntax = (problem.getID() & IProblem.Syntax) != 0;
            int start = source.sourceMap().toEditorDiagnosticOffset(problem.getSourceStart(), syntax);
            int end = source.sourceMap().toEditorDiagnosticOffset(problem.getSourceEnd(), syntax);
            if (start < 0 || end < start || text.isEmpty()) continue;
            var span = new Span(Math.min(start, text.length() - 1), Math.min(end + 1, text.length()));
            if (syntax) recovery.add(span);
            String imported = problem.getID() == IProblem.UnusedImport ? text.substring(span.start(), span.end()) : null;
            if (imported != null) {
                for (Object candidate : ast.imports()) {
                    var declaration = (ImportDeclaration) candidate;
                    if (problem.getSourceStart() >= declaration.getStartPosition()
                            && problem.getSourceEnd() < declaration.getStartPosition() + declaration.getLength()) {
                        imported = (declaration.isStatic() ? "static " : "") + declaration.getName().getFullyQualifiedName()
                                + (declaration.isOnDemand() ? ".*" : "");
                        break;
                    }
                }
            }
            problems.add(new Problem(problem.getID(), problem.getMessage(), span,
                    problem.isError() ? ParserNotice.Level.ERROR : problem.isWarning() ? ParserNotice.Level.WARNING : ParserNotice.Level.INFO,
                    imported));
        }
        var generatedTokens = new HashMap<Integer, Integer>();
        var statements = new ArrayList<Span>();
        ast.accept(new SemanticTokensVisitor(generatedTokens) {
            @Override public void preVisit(ASTNode node) {
                if (!(node instanceof Statement) || node instanceof Block) return;
                int start = source.sourceMap().toEditorOffset(node.getStartPosition());
                int last = source.sourceMap().toEditorOffset(node.getStartPosition() + node.getLength() - 1);
                if (start >= 0 && last >= start) statements.add(new Span(start, last + 1));
            }
        });
        var tokens = new HashMap<Integer, Integer>();
        generatedTokens.forEach((offset, kind) -> {
            int editorOffset = source.sourceMap().toEditorOffset(offset);
            if (editorOffset >= 0) tokens.put(editorOffset, kind);
        });
        return new JavaAnalysis(revision, environment, ast, text, source.sourceMap(), tokens, problems, recovery, importsChecked,
                source.sourceMap() == JavaSourceMap.IDENTITY ? SourceDeclarationAnalyzer.analyze(ast, text) : List.of(), statements);
    }

    public record Span(int start, int end) {
        public boolean contains(int offset) { return offset >= start && offset < end; }
        public Span edited(int offset, int removed, int added) {
            if (end <= offset) return this;
            if (start >= offset + removed) return new Span(start + added - removed, end + added - removed);
            return null;
        }
    }

    public record Problem(int id, String message, Span span, ParserNotice.Level level, String unusedImport) {
        public Problem at(Span replacement) { return new Problem(id, message, replacement, level, unusedImport); }
    }
}
