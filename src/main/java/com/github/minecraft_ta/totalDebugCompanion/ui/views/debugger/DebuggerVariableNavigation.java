package com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger;

import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.navigation.RuntimeMember;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CatchClause;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.ForStatement;
import org.eclipse.jdt.core.dom.Initializer;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import java.util.Optional;

/** Resolves debugger values to stable semantic navigation targets. */
final class DebuggerVariableNavigation {
    private DebuggerVariableNavigation() {
    }

    static Optional<NavigationTarget> declarationTarget(
            DebugEngine.Source source,
            DebugEngine.StackFrame frame,
            DebugEngine.Variable variable,
            DebugEngine.Variable parent
    ) {
        return switch (variable.kind()) {
            case THIS -> Optional.of(new NavigationTarget.RuntimeClass(frame.binaryName()));
            case PARAMETER, LOCAL -> localDeclaration(source, frame, variable.name());
            case FIELD -> fieldDeclaration(frame, variable, parent);
            default -> Optional.empty();
        };
    }

    static Optional<NavigationTarget.RuntimeClass> typeTarget(
            DebugEngine.StackFrame frame,
            DebugEngine.Variable variable
    ) {
        if (variable.kind() == DebugEngine.VariableKind.THIS) {
            return Optional.of(new NavigationTarget.RuntimeClass(frame.binaryName()));
        }
        return typeTarget(variable.type());
    }

    static Optional<NavigationTarget.RuntimeClass> typeTarget(String type) {
        return runtimeType(type).map(NavigationTarget.RuntimeClass::new);
    }

    private static Optional<NavigationTarget> localDeclaration(
            DebugEngine.Source source,
            DebugEngine.StackFrame frame,
            String variableName
    ) {
        if (source == null || variableName.isBlank() || frame.line() < 1) {
            return Optional.empty();
        }
        CompilationUnit unit = parse(source.contents());
        int frameOffset = unit.getPosition(frame.line(), 0);
        if (frameOffset < 0) {
            return Optional.empty();
        }
        ASTNode executable = enclosingExecutable(unit, frameOffset);
        if (executable == null) {
            return Optional.empty();
        }

        int[] closestOffset = {-1};
        executable.accept(new ASTVisitor() {
            @Override
            public boolean visit(SingleVariableDeclaration declaration) {
                consider(declaration, declaration.getName().getIdentifier());
                return true;
            }

            @Override
            public boolean visit(VariableDeclarationFragment declaration) {
                if (!(declaration.getParent() instanceof FieldDeclaration)) {
                    consider(declaration, declaration.getName().getIdentifier());
                }
                return true;
            }

            private void consider(ASTNode declaration, String candidateName) {
                int candidateOffset = declaration.getStartPosition();
                if (candidateName.equals(variableName)
                        && candidateOffset <= frameOffset
                        && visibleAt(declaration, executable, frameOffset)
                        && candidateOffset > closestOffset[0]) {
                    closestOffset[0] = candidateOffset;
                }
            }
        });
        if (closestOffset[0] < 0) {
            return Optional.empty();
        }
        int line = unit.getLineNumber(closestOffset[0]);
        return line < 1
                ? Optional.empty()
                : Optional.of(new NavigationTarget.RuntimeLine(frame.binaryName(), line));
    }

    private static Optional<NavigationTarget> fieldDeclaration(
            DebugEngine.StackFrame frame,
            DebugEngine.Variable variable,
            DebugEngine.Variable parent
    ) {
        String adapterName = variable.adapterName();
        String fieldName = adapterName;
        String declaredOwner = "";
        int ownerStart = adapterName.lastIndexOf(" (");
        if (ownerStart > 0 && adapterName.endsWith(")")) {
            fieldName = adapterName.substring(0, ownerStart);
            declaredOwner = adapterName.substring(ownerStart + 2, adapterName.length() - 1);
        }
        if (fieldName.isBlank()) {
            return Optional.empty();
        }

        Optional<String> owner = runtimeType(declaredOwner);
        if (owner.isEmpty() && parent != null) {
            owner = parent.kind() == DebugEngine.VariableKind.THIS
                    ? Optional.of(frame.binaryName())
                    : runtimeType(parent.type());
        }
        if (owner.isEmpty()) {
            owner = Optional.of(frame.binaryName());
        }
        String resolvedFieldName = fieldName;
        return owner.map(className -> new NavigationTarget.RuntimeDeclaration(
                new RuntimeMember.Field(className, resolvedFieldName)
        ));
    }

    private static Optional<String> runtimeType(String type) {
        if (type == null) {
            return Optional.empty();
        }
        String normalized = type.trim();
        int genericStart = normalized.indexOf('<');
        if (genericStart >= 0) {
            normalized = normalized.substring(0, genericStart);
        }
        while (normalized.endsWith("[]")) {
            normalized = normalized.substring(0, normalized.length() - 2);
        }
        if (normalized.endsWith("...")) {
            normalized = normalized.substring(0, normalized.length() - 3);
        }
        normalized = normalized.replace('/', '.');
        return normalized.contains(".") && !normalized.endsWith(".")
                ? Optional.of(normalized)
                : Optional.empty();
    }

    private static CompilationUnit parse(String source) {
        ASTParser parser = ASTParser.newParser(AST.JLS21);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(source.toCharArray());
        parser.setStatementsRecovery(true);
        return (CompilationUnit) parser.createAST(null);
    }

    private static boolean visibleAt(ASTNode declaration, ASTNode executable, int offset) {
        ASTNode current = declaration.getParent();
        while (current != null && current != executable) {
            if ((current instanceof Block
                    || current instanceof CatchClause
                    || current instanceof ForStatement
                    || current instanceof EnhancedForStatement)
                    && !contains(current, offset)) {
                return false;
            }
            current = current.getParent();
        }
        return current == executable;
    }

    private static boolean contains(ASTNode node, int offset) {
        return node.getStartPosition() <= offset
                && offset < node.getStartPosition() + node.getLength();
    }

    private static ASTNode enclosingExecutable(CompilationUnit unit, int offset) {
        java.util.ArrayList<ASTNode> candidates = new java.util.ArrayList<>();
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                add(node);
                return true;
            }

            @Override
            public boolean visit(Initializer node) {
                add(node);
                return true;
            }

            @Override
            public boolean visit(LambdaExpression node) {
                add(node);
                return true;
            }

            private void add(ASTNode node) {
                if (node.getStartPosition() <= offset
                        && offset < node.getStartPosition() + node.getLength()) {
                    candidates.add(node);
                }
            }
        });
        return candidates.stream()
                .min(java.util.Comparator.comparingInt(ASTNode::getLength))
                .orElse(null);
    }
}
