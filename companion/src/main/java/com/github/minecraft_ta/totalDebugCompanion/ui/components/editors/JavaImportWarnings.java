package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaImports;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.JavaAnalysis;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.JavaAnalysis.Problem;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.JavaAnalysis.Span;
import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static com.github.minecraft_ta.totalDebugCompanion.ui.components.editors.JavaEditorTokens.*;

/** Last checked import warnings, relocated by declaration identity while JDT's import pass is unavailable. */
final class JavaImportWarnings {
    private List<Problem> checked = List.of();
    private List<Use> checkedUses = List.of();
    private String previousSource;

    JavaImportWarnings(String source) { previousSource = source; }

    void accept(JavaAnalysis analysis, RSyntaxDocument document) {
        if (!analysis.importsChecked()) return;
        checked = analysis.problems().stream().filter(problem -> problem.unusedImport() != null).toList();
        checkedUses = scan(document, analysis.contents()).uses().stream().map(use -> {
            var context = analysis.statements().stream()
                    .filter(span -> span.start() <= use.token().start() && span.end() >= use.token().end())
                    .min(Comparator.comparingInt(span -> span.end() - span.start())).orElse(use.token());
            return new Use(use.name(), use.token(), context);
        }).toList();
    }

    void edited(String text, int offset, int removed, int added) {
        boolean trivia = previousSource.substring(offset, offset + removed).isBlank()
                && text.substring(offset, offset + added).isBlank();
        var retained = new ArrayList<Use>();
        for (var use : checkedUses) {
            var token = use.token().edited(offset, removed, added);
            if (token == null) continue;
            var context = use.context().edited(offset, removed, added);
            if (context == null && trivia) context = new Span(use.context().start(), use.context().end() + added - removed);
            if (context != null) retained.add(new Use(use.name(), token, context));
        }
        checkedUses = retained;
        previousSource = text;
    }

    void clear() { checked = List.of(); checkedUses = List.of(); }

    List<Problem> current(RSyntaxDocument document) {
        if (checked.isEmpty()) return List.of();
        var source = scan(document, previousSource);
        var known = new HashMap<Span, String>();
        for (var use : checkedUses) known.put(use.token(), use.name());
        var newUses = source.uses().stream().filter(use -> !use.name().equals(known.get(use.token()))).toList();
        var result = new ArrayList<Problem>();
        for (var problem : checked) {
            String imported = problem.unusedImport();
            var span = source.imports().get(imported);
            if (span == null) continue;
            String name = imported.substring(imported.lastIndexOf('.') + 1);
            boolean possibleNewUse = newUses.stream().anyMatch(use -> name.equals("*") || name.equals(use.name()));
            if (!possibleNewUse) result.add(problem.at(span));
        }
        return result;
    }

    private record Use(String name, Span token, Span context) { }
    private record Source(Map<String, Span> imports, List<Use> uses) { }

    /** Possible unqualified uses only; JDT remains the authority on actual usage. */
    private static Source scan(RSyntaxDocument document, String text) {
        var declarations = JavaImports.read(text);
        var imports = new HashMap<String, Span>();
        for (var declaration : declarations) {
            if (declaration.complete() && declaration.nameEnd() > declaration.nameStart())
                imports.put(declaration.name(), new Span(declaration.nameStart(), declaration.nameEnd()));
        }
        int bodyStart = declarations.isEmpty() ? 0 : declarations.getLast().editorEnd();
        var uses = new ArrayList<Use>();
        boolean qualified = false;
        for (var token : document) {
            if (token.getOffset() < bodyStart || isTrivia(token)) continue;
            if (isLiteral(token)) {
                qualified = false;
                continue;
            }
            String word = token.getLexeme();
            if (!qualified && isName(word)) {
                String first = word.contains(".") ? word.substring(0, word.indexOf('.')) : word;
                var span = new Span(token.getOffset(), token.getOffset() + first.length());
                uses.add(new Use(first, span, span));
            }
            qualified = word.equals(".");
        }
        return new Source(imports, uses);
    }

}
