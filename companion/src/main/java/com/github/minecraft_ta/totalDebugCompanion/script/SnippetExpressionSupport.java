package com.github.minecraft_ta.totalDebugCompanion.script;

import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerCompletionProposal;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaSnippetSource;
import com.github.minecraft_ta.totalDebugCompanion.jdt.completion.CompletionItem;
import com.github.minecraft_ta.totalDebugCompanion.jdt.completion.CompletionItemKind;
import com.github.minecraft_ta.totalDebugCompanion.jdt.completion.CustomCompletionRequestor;
import com.github.minecraft_ta.totalDebugCompanion.jdt.completion.CustomTextEdit;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaAst;
import com.github.minecraft_ta.totalDebugCompanion.jdt.impls.CompilationUnitImpl;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.SimpleName;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** JDT completion, imports, and semantic coloring for a standalone Java expression. */
public final class SnippetExpressionSupport {
    private static final Pattern IMPORT = Pattern.compile("import\\s+((?:static\\s+)?[^;]+);");
    private static final Pattern PLACEHOLDER_WITH_DEFAULT = Pattern.compile("\\$\\{\\d+:([^}]*)}");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{?\\d+}?");

    private final String className;
    private volatile List<String> imports = List.of();
    private volatile JavaSnippetSource.Mode mode = JavaSnippetSource.Mode.EXPRESSION;
    private boolean automaticMode;

    public void setAutomaticMode(boolean automaticMode) { this.automaticMode = automaticMode; }
    private JavaSnippetSource.Mode mode(String source) {
        return this.automaticMode ? JavaSnippetSource.detectMode(source) : this.mode;
    }

    public void setMode(JavaSnippetSource.Mode mode) { this.mode = java.util.Objects.requireNonNull(mode); }

    public SnippetExpressionSupport(String className) {
        this.className = className;
    }

    public CompletableFuture<List<DebuggerCompletionProposal>> complete(
            String expression,
            int caret,
            boolean explicit
    ) {
        return CompletableFuture.supplyAsync(() -> completeNow(expression, caret));
    }

    public CompletableFuture<List<DebugEngine.ExpressionToken>> tokens(String expression) {
        return CompletableFuture.supplyAsync(() -> tokensNow(expression));
    }

    public synchronized void accepted(DebuggerCompletionProposal proposal) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(this.imports);
        for (String requiredImport : proposal.requiredImports()) {
            String simpleName = importedTypeSimpleName(requiredImport);
            if (simpleName != null) {
                merged.removeIf(existing -> simpleName.equals(importedTypeSimpleName(existing)));
            }
            merged.add(requiredImport);
        }
        this.imports = List.copyOf(merged);
    }

    public List<String> imports() {
        return this.imports;
    }

    public synchronized void setImports(List<String> imports) {
        this.imports = List.copyOf(new LinkedHashSet<>(imports));
    }

    public JavaSnippetSource.GeneratedSource source(String expression) {
        return JavaSnippetSource.build(this.className, combined(expression), mode(expression));
    }

    private List<DebuggerCompletionProposal> completeNow(String expression, int caret) {
        String combined = combined(expression);
        int prefix = combined.length() - expression.length();
        JavaSnippetSource.GeneratedSource generated = JavaSnippetSource.build(this.className, combined, mode(expression));
        int generatedCaret = generated.sourceMap().toGeneratedOffset(prefix + caret);
        CompilationUnitImpl unit = new CompilationUnitImpl(this.className, generated.source());
        CompletableFuture<List<CompletionItem>> result = new CompletableFuture<>();
        CustomCompletionRequestor requestor = new CustomCompletionRequestor(
                unit,
                generatedCaret,
                (ignored, items) -> result.complete(items)
        );
        try {
            unit.codeComplete(generatedCaret, requestor, requestor);
        } catch (OperationCanceledException exception) {
            return List.of();
        } catch (RuntimeException exception) {
            if (exception.getCause() instanceof OperationCanceledException) {
                return List.of();
            }
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to complete Java expression", exception);
        }

        List<DebuggerCompletionProposal> proposals = new ArrayList<>();
        for (CompletionItem item : result.join()) {
            DebuggerCompletionProposal proposal = proposal(item, generated, prefix, caret);
            if (proposal != null) {
                proposals.add(proposal);
            }
        }
        return proposals;
    }

    private DebuggerCompletionProposal proposal(
            CompletionItem item,
            JavaSnippetSource.GeneratedSource generated,
            int prefix,
            int caret
    ) {
        List<String> requiredImports = new ArrayList<>();
        CustomTextEdit main = null;
        int mainStart = -1;
        int mainEnd = -1;
        for (CustomTextEdit edit : item.getTextEdits()) {
            Matcher importMatcher = IMPORT.matcher(edit.getNewText());
            while (importMatcher.find()) {
                requiredImports.add(importMatcher.group(1).trim());
            }
            int combinedStart = generated.sourceMap().toEditorOffset(edit.getRange().getOffset());
            int combinedEnd = generated.sourceMap().toEditorOffset(edit.getRange().getEndOffset());
            if (combinedStart < prefix || combinedEnd < combinedStart) {
                continue;
            }
            int candidateStart = combinedStart - prefix;
            int candidateEnd = combinedEnd - prefix;
            if (main == null && candidateStart <= caret && candidateEnd >= caret) {
                main = edit;
                mainStart = candidateStart;
                mainEnd = candidateEnd;
            } else if (main == null && !edit.getNewText().contains("import ")) {
                main = edit;
                mainStart = candidateStart;
                mainEnd = candidateEnd;
            }
        }
        if (main == null || mainStart < 0 || mainEnd < mainStart) {
            return null;
        }
        PlainSnippet insertion = plainSnippet(main.getNewText());
        if (insertion.text().isBlank()) {
            return null;
        }
        return new DebuggerCompletionProposal(
                item.getLabel(),
                insertion.text(),
                kind(item.getKind()),
                String.join(", ", requiredImports),
                mainStart,
                mainEnd,
                insertion.caret(),
                item.getRelevance(),
                requiredImports
        );
    }

    private List<DebugEngine.ExpressionToken> tokensNow(String expression) {
        String combined = combined(expression);
        int prefix = combined.length() - expression.length();
        JavaSnippetSource.GeneratedSource generated = JavaSnippetSource.build(this.className, combined, mode(expression));
        var ast = JavaAst.parse(this.className, generated.source());
        List<DebugEngine.ExpressionToken> result = new ArrayList<>();
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName node) {
                IBinding binding = node.resolveBinding();
                DebugEngine.ExpressionTokenKind kind = switch (binding) {
                    case null -> null;
                    case IBinding ignored when binding.getKind() == IBinding.TYPE ->
                            DebugEngine.ExpressionTokenKind.TYPE;
                    case IBinding ignored when binding.getKind() == IBinding.METHOD ->
                            DebugEngine.ExpressionTokenKind.METHOD;
                    case IVariableBinding variable when variable.isField() ->
                            DebugEngine.ExpressionTokenKind.FIELD;
                    default -> null;
                };
                if (kind == null) {
                    return false;
                }
                int combinedStart = generated.sourceMap().toEditorOffset(node.getStartPosition());
                int editorStart = combinedStart - prefix;
                if (combinedStart >= prefix && editorStart + node.getLength() <= expression.length()) {
                    result.add(new DebugEngine.ExpressionToken(editorStart, node.getLength(), kind));
                }
                return false;
            }
        });
        return result;
    }

    private String combined(String expression) {
        StringBuilder result = new StringBuilder();
        for (String imported : this.imports) {
            result.append("import ").append(imported).append(";\n");
        }
        return result.append(expression).toString();
    }

    private static String importedTypeSimpleName(String imported) {
        String normalized = imported.trim();
        if (normalized.startsWith("static ") || normalized.endsWith(".*")) {
            return null;
        }
        int separator = normalized.lastIndexOf('.');
        return separator < 0 ? normalized : normalized.substring(separator + 1);
    }

    private static DebuggerCompletionProposal.Kind kind(CompletionItemKind kind) {
        return switch (kind) {
            case FIELD -> DebuggerCompletionProposal.Kind.FIELD;
            case CONSTANT, ENUM_MEMBER -> DebuggerCompletionProposal.Kind.CONSTANT;
            case METHOD, CONSTRUCTOR -> DebuggerCompletionProposal.Kind.METHOD;
            case CLASS, INTERFACE, ENUM -> DebuggerCompletionProposal.Kind.TYPE;
            case KEYWORD -> DebuggerCompletionProposal.Kind.KEYWORD;
            default -> DebuggerCompletionProposal.Kind.VARIABLE;
        };
    }

    private static PlainSnippet plainSnippet(String source) {
        Matcher withDefault = PLACEHOLDER_WITH_DEFAULT.matcher(source);
        StringBuilder result = new StringBuilder();
        int cursor = 0;
        int caret = -1;
        while (withDefault.find()) {
            result.append(source, cursor, withDefault.start());
            if (caret < 0) {
                caret = result.length();
            }
            result.append(withDefault.group(1));
            cursor = withDefault.end();
        }
        result.append(source, cursor, source.length());
        String withDefaults = result.toString();
        Matcher empty = PLACEHOLDER.matcher(withDefaults);
        result.setLength(0);
        cursor = 0;
        while (empty.find()) {
            result.append(withDefaults, cursor, empty.start());
            if (caret < 0) {
                caret = result.length();
            }
            cursor = empty.end();
        }
        result.append(withDefaults, cursor, withDefaults.length());
        return new PlainSnippet(result.toString(), caret < 0 ? result.length() : caret);
    }

    private record PlainSnippet(String text, int caret) {
    }
}
