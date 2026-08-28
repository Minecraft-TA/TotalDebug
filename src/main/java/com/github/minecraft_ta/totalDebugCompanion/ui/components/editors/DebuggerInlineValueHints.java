package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerValueText;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.Initializer;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SimpleName;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Maps the selected frame's visible values to source lines in its enclosing executable body. */
final class DebuggerInlineValueHints {
    record ValueHint(
            DebugEngine.StackFrame frame,
            DebuggerEditorPresentation.PresentedVariable value,
            String text
    ) {
    }

    record LineHint(java.util.List<ValueHint> values) {
        LineHint {
            values = java.util.List.copyOf(values);
        }
    }

    private DebuggerInlineValueHints() {
    }

    static Map<Integer, LineHint> create(
            CompilationUnit unit,
            String source,
            DebuggerEditorPresentation.Snapshot snapshot
    ) {
        if (unit == null || source == null || snapshot == null || snapshot.frame().line() < 1) {
            return Map.of();
        }
        int offset = firstSourceOffset(unit, source, snapshot.frame().line());
        if (offset < 0) {
            return Map.of();
        }
        ASTNode body = enclosingExecutable(unit, offset);
        if (body == null) {
            return Map.of();
        }

        Map<String, DebuggerEditorPresentation.PresentedVariable> visible = new LinkedHashMap<>();
        for (DebuggerEditorPresentation.PresentedVariable presented : snapshot.variables()) {
            DebugEngine.Variable variable = presented.variable();
            if (variable.kind() == DebugEngine.VariableKind.PARAMETER
                    || variable.kind() == DebugEngine.VariableKind.LOCAL) {
                visible.put(variable.name(), presented);
            }
        }
        if (visible.isEmpty()) {
            return Map.of();
        }

        Map<Integer, LinkedHashMap<String, DebuggerEditorPresentation.PresentedVariable>> byLine = new TreeMap<>();
        body.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName name) {
                DebuggerEditorPresentation.PresentedVariable presented = visible.get(name.getIdentifier());
                if (presented == null || !(name.resolveBinding() instanceof IVariableBinding)) {
                    return true;
                }
                int line = unit.getLineNumber(name.getStartPosition());
                if (line > 0 && line <= snapshot.frame().line()) {
                    byLine.computeIfAbsent(line, ignored -> new LinkedHashMap<>())
                            .putIfAbsent(name.getIdentifier(), presented);
                }
                return true;
            }
        });

        Map<Integer, LineHint> result = new LinkedHashMap<>();
        for (Map.Entry<Integer, LinkedHashMap<String, DebuggerEditorPresentation.PresentedVariable>> entry
                : byLine.entrySet()) {
            java.util.List<ValueHint> hints = entry.getValue().values().stream()
                    .map(value -> new ValueHint(
                            snapshot.frame(),
                            value,
                            DebuggerValueText.inlineValue(value.variable(), value.preview())
                    ))
                    .toList();
            if (!hints.isEmpty()) {
                result.put(entry.getKey(), new LineHint(hints));
            }
        }
        return Map.copyOf(result);
    }

    private static int firstSourceOffset(CompilationUnit unit, String source, int line) {
        int start = unit.getPosition(line, 0);
        if (start < 0 || start >= source.length()) {
            return -1;
        }
        int end = source.indexOf('\n', start);
        if (end < 0) {
            end = source.length();
        }
        while (start < end && Character.isWhitespace(source.charAt(start))) {
            start++;
        }
        return start;
    }

    private static ASTNode enclosingExecutable(CompilationUnit unit, int offset) {
        java.util.ArrayList<ASTNode> candidates = new java.util.ArrayList<>();
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                addIfContaining(node);
                return true;
            }

            @Override
            public boolean visit(Initializer node) {
                addIfContaining(node);
                return true;
            }

            @Override
            public boolean visit(LambdaExpression node) {
                addIfContaining(node);
                return true;
            }

            private void addIfContaining(ASTNode node) {
                if (node.getStartPosition() <= offset
                        && offset < node.getStartPosition() + node.getLength()) {
                    candidates.add(node);
                }
            }
        });
        return candidates.stream().min(Comparator.comparingInt(ASTNode::getLength)).orElse(null);
    }
}
