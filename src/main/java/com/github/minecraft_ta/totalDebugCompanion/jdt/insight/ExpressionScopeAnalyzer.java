package com.github.minecraft_ta.totalDebugCompanion.jdt.insight;

import com.github.minecraft_ta.totalDebugCompanion.debugger.ExpressionSuggestion;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CatchClause;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.ForStatement;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.TryStatement;
import org.eclipse.jdt.core.dom.VariableDeclarationExpression;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.eclipse.jdt.core.dom.CompilationUnit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Finds evaluator-compatible names visible at one exact Java source offset. */
public final class ExpressionScopeAnalyzer {
    private ExpressionScopeAnalyzer() {
    }

    public static List<ExpressionSuggestion> analyze(CompilationUnit unit, int sourceOffset) {
        Objects.requireNonNull(unit, "unit");
        if (sourceOffset < 0 || sourceOffset > unit.getLength()) {
            throw new IllegalArgumentException("sourceOffset is outside the compilation unit");
        }
        ASTNode selected = NodeFinder.perform(unit, sourceOffset, 0);
        MethodDeclaration method = ancestor(selected, MethodDeclaration.class);
        AbstractTypeDeclaration type = ancestor(selected, AbstractTypeDeclaration.class);
        if (method == null || type == null) {
            return literals();
        }

        Map<String, ExpressionSuggestion> suggestions = new LinkedHashMap<>();
        boolean staticContext = Modifier.isStatic(method.getModifiers());
        if (!staticContext) {
            add(suggestions, new ExpressionSuggestion("this", type.getName().getIdentifier(),
                    ExpressionSuggestion.Kind.KEYWORD));
        }
        for (Object declaration : type.bodyDeclarations()) {
            if (!(declaration instanceof FieldDeclaration field)
                    || staticContext && !Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            for (Object candidate : field.fragments()) {
                VariableDeclarationFragment fragment = (VariableDeclarationFragment) candidate;
                add(suggestions, new ExpressionSuggestion(
                        fragment.getName().getIdentifier(),
                        field.getType().toString(),
                        ExpressionSuggestion.Kind.FIELD
                ));
            }
        }
        for (Object candidate : method.parameters()) {
            SingleVariableDeclaration parameter = (SingleVariableDeclaration) candidate;
            add(suggestions, variable(parameter));
        }
        method.accept(new org.eclipse.jdt.core.dom.ASTVisitor() {
            @Override
            public boolean visit(VariableDeclarationFragment fragment) {
                if (fragment.getParent() instanceof FieldDeclaration) {
                    return true;
                }
                if (isVisible(fragment, sourceOffset, method)) {
                    add(suggestions, new ExpressionSuggestion(
                            fragment.getName().getIdentifier(),
                            variableType(fragment),
                            ExpressionSuggestion.Kind.VARIABLE
                    ));
                }
                return true;
            }

            @Override
            public boolean visit(SingleVariableDeclaration declaration) {
                if (!method.parameters().contains(declaration)
                        && isVisible(declaration, sourceOffset, method)) {
                    add(suggestions, variable(declaration));
                }
                return true;
            }
        });
        literals().forEach(suggestion -> add(suggestions, suggestion));
        return List.copyOf(suggestions.values());
    }

    private static boolean isVisible(ASTNode declaration, int offset, MethodDeclaration method) {
        int visibleFrom = declaration.getStartPosition() + declaration.getLength();
        if (offset < visibleFrom) {
            return false;
        }
        ASTNode scope = declaration.getParent();
        while (scope != null && scope != method) {
            if (scope instanceof Block
                    || scope instanceof ForStatement
                    || scope instanceof EnhancedForStatement
                    || scope instanceof CatchClause
                    || scope instanceof LambdaExpression
                    || scope instanceof TryStatement) {
                return offset < scope.getStartPosition() + scope.getLength();
            }
            scope = scope.getParent();
        }
        return scope == method && offset < method.getStartPosition() + method.getLength();
    }

    private static String variableType(VariableDeclarationFragment fragment) {
        return switch (fragment.getParent()) {
            case VariableDeclarationStatement statement -> statement.getType().toString();
            case VariableDeclarationExpression expression -> expression.getType().toString();
            default -> "";
        };
    }

    private static ExpressionSuggestion variable(SingleVariableDeclaration declaration) {
        return new ExpressionSuggestion(
                declaration.getName().getIdentifier(),
                declaration.getType() + (declaration.isVarargs() ? "..." : ""),
                ExpressionSuggestion.Kind.VARIABLE
        );
    }

    private static List<ExpressionSuggestion> literals() {
        List<ExpressionSuggestion> result = new ArrayList<>();
        result.add(new ExpressionSuggestion("true", "boolean literal", ExpressionSuggestion.Kind.KEYWORD));
        result.add(new ExpressionSuggestion("false", "boolean literal", ExpressionSuggestion.Kind.KEYWORD));
        result.add(new ExpressionSuggestion("null", "null literal", ExpressionSuggestion.Kind.KEYWORD));
        return result;
    }

    private static void add(Map<String, ExpressionSuggestion> suggestions, ExpressionSuggestion suggestion) {
        suggestions.putIfAbsent(suggestion.text(), suggestion);
    }

    private static <T extends ASTNode> T ancestor(ASTNode start, Class<T> type) {
        ASTNode current = start;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getParent();
        }
        return null;
    }
}
