package com.github.minecraft_ta.totalDebugCompanion.navigation;

import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.CodeSymbol;
import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.JavaSymbolResolver;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import java.util.Objects;

/** Resolves the Java member enclosing a caret without retaining AST or Swing objects. */
public final class JavaBreadcrumbResolver {
    private JavaBreadcrumbResolver() {
    }

    public static Member resolve(CompilationUnit unit, int caretOffset, NavigationTarget sourceTarget) {
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(sourceTarget, "sourceTarget");
        if (caretOffset < 0 || caretOffset > unit.getLength()) {
            return null;
        }

        ASTNode current = NodeFinder.perform(unit, caretOffset, 0);
        while (current != null) {
            SimpleName name = declarationName(current);
            if (name != null) {
                NavigationTarget target = memberTarget(name, sourceTarget);
                if (target != null) {
                    String label = current instanceof MethodDeclaration ? name.getIdentifier() + "()"
                            : name.getIdentifier();
                    return new Member(label, target, name.getStartPosition());
                }
            }
            current = current.getParent();
        }
        return null;
    }

    private static SimpleName declarationName(ASTNode node) {
        return switch (node) {
            case MethodDeclaration method -> method.getName();
            case VariableDeclarationFragment fragment when fragment.getParent() instanceof FieldDeclaration ->
                    fragment.getName();
            case FieldDeclaration field when field.fragments().size() == 1 ->
                    ((VariableDeclarationFragment) field.fragments().getFirst()).getName();
            case EnumConstantDeclaration constant -> constant.getName();
            case SingleVariableDeclaration component when component.getParent() instanceof RecordDeclaration ->
                    component.getName();
            default -> null;
        };
    }

    private static NavigationTarget memberTarget(SimpleName name, NavigationTarget sourceTarget) {
        return switch (sourceTarget) {
            case NavigationTarget.RuntimeClass ignored -> {
                CodeSymbol symbol = JavaSymbolResolver.trySymbolForBinding(name.resolveBinding());
                yield symbol instanceof CodeSymbol.FieldSymbol || symbol instanceof CodeSymbol.MethodSymbol
                        ? new NavigationTarget.RuntimeDeclaration(RuntimeMember.from(symbol))
                        : null;
            }
            case NavigationTarget.LocalFile file -> new NavigationTarget.LocalFile(file.path(), name.getStartPosition());
            default -> null;
        };
    }

    public record Member(String label, NavigationTarget target, int offset) {
        public Member {
            if (Objects.requireNonNull(label, "label").isBlank()) {
                throw new IllegalArgumentException("label must not be blank");
            }
            Objects.requireNonNull(target, "target");
            if (offset < 0) {
                throw new IllegalArgumentException("offset must not be negative");
            }
        }
    }
}
