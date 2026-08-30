package com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerValueText;
import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryLabel;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryText;
import com.github.minecraft_ta.totalDebugCompanion.ui.speedsearch.SpeedSearch;

import javax.swing.Icon;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import java.awt.Component;

/** Value-tree row model and renderer, independent of debugger request scheduling. */
final class DebuggerValueTree {
    private DebuggerValueTree() {
    }

    static JTree create(DefaultTreeModel model) {
        JTree tree = new JTree(model);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(UiMetrics.TREE_ROW_HEIGHT);
        tree.putClientProperty("JTree.wideSelection", true);
        tree.setCellRenderer(new Renderer());
        SpeedSearch.install(tree, path -> searchText(
                ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject()
        ));
        return tree;
    }

    private static String searchText(Object value) {
        DebugValue debugValue = debugValue(value);
        if (debugValue != null) {
            return debugValue.name() + ' ' + debugValue.evaluateName() + ' '
                    + debugValue.value() + ' ' + debugValue.type() + ' '
                    + (debugValue.preview().available() ? debugValue.preview().summary() : "");
        }
        return switch (value) {
            case ExpressionStatus status -> status.expression() + ' ' + status.text();
            case StatusValue status -> status.text();
            case MoreChildren more -> more.owner().name() + " load more";
            case Placeholder ignored -> "Loading";
            default -> String.valueOf(value);
        };
    }

    static DefaultMutableTreeNode valueNode(DebugValue value) {
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(value);
        addPlaceholder(node, value);
        return node;
    }

    static void addPlaceholder(DefaultMutableTreeNode node, DebugValue value) {
        if (value.variablesReference() > 0) {
            node.add(new DefaultMutableTreeNode(Placeholder.INSTANCE));
        }
    }

    static boolean hasPlaceholder(DefaultMutableTreeNode node) {
        return node.getChildCount() == 1
                && ((DefaultMutableTreeNode) node.getFirstChild()).getUserObject() == Placeholder.INSTANCE;
    }

    static DebugValue debugValue(Object value) {
        return switch (value) {
            case DebugValue debugValue -> debugValue;
            case ExpressionValue expressionValue -> expressionValue.value();
            default -> null;
        };
    }

    static String expressionOf(Object value) {
        return switch (value) {
            case ExpressionValue expressionValue -> expressionValue.value().name();
            case ExpressionStatus status -> status.expression();
            default -> "";
        };
    }

    static boolean isWatch(Object value) {
        return switch (value) {
            case ExpressionValue expressionValue -> expressionValue.watch();
            case ExpressionStatus status -> status.watch();
            default -> false;
        };
    }

    record ExpressionValue(DebugValue value, boolean watch) {
    }

    record ExpressionStatus(String expression, String text, boolean error, boolean watch) {
    }

    record StatusValue(String text, boolean error) {
    }

    record MoreChildren(DebugValue owner, int nextStart, int remaining) {
    }

    record DebugValue(
            String name,
            String evaluateName,
            String value,
            String type,
            DebugEngine.VariableKind kind,
            int variablesReference,
            int namedVariables,
            int indexedVariables,
            DebugEngine.ValuePreview preview,
            DebugEngine.Variable sourceVariable
    ) {
        static DebugValue from(DebugEngine.Variable variable) {
            return new DebugValue(
                    variable.name(),
                    variable.evaluateName(),
                    variable.value(),
                    variable.type(),
                    variable.kind(),
                    variable.variablesReference(),
                    variable.namedVariables(),
                    variable.indexedVariables(),
                    DebugEngine.ValuePreview.NONE,
                    variable
            );
        }

        static DebugValue from(String expression, DebugEngine.EvaluationResult result) {
            return new DebugValue(
                    expression,
                    expression,
                    result.value(),
                    result.type(),
                    DebugEngine.VariableKind.EXPRESSION,
                    result.variablesReference(),
                    0,
                    result.indexedVariables(),
                    DebugEngine.ValuePreview.NONE,
                    null
            );
        }

        DebugValue withPreview(DebugEngine.ValuePreview replacement) {
            return new DebugValue(
                    this.name,
                    this.evaluateName,
                    this.value,
                    this.type,
                    this.kind,
                    this.variablesReference,
                    this.namedVariables,
                    this.indexedVariables,
                    replacement,
                    this.sourceVariable
            );
        }
    }

    enum Placeholder {
        INSTANCE
    }

    private static final class Renderer extends DefaultTreeCellRenderer {
        private final PrimarySecondaryLabel valueLabel = new PrimarySecondaryLabel();

        @Override
        public Component getTreeCellRendererComponent(
                JTree tree,
                Object value,
                boolean selected,
                boolean expanded,
                boolean leaf,
                int row,
                boolean hasFocus
        ) {
            Component component = super.getTreeCellRendererComponent(
                    tree, value, selected, expanded, leaf, row, hasFocus
            );
            if (!(value instanceof DefaultMutableTreeNode node)) {
                return component;
            }
            DebugValue debugValue = debugValue(node.getUserObject());
            if (debugValue != null) {
                Icon rowIcon = node.getUserObject() instanceof ExpressionValue expressionValue
                        ? expressionValue.watch() ? Icons.WATCH : Icons.EVALUATE_EXPRESSION
                        : icon(debugValue);
                String visibleValue = DebuggerValueText.visibleValue(debugValue.value(), debugValue.type());
                String simpleType = DebuggerValueText.simpleTypeName(debugValue.type());
                String secondary = debugValue.preview().available()
                        ? debugValue.preview().summary()
                        : visibleValue.equals(simpleType) ? "" : simpleType;
                this.valueLabel.configure(
                        new PrimarySecondaryText(debugValue.name() + " = " + visibleValue, secondary),
                        rowIcon,
                        tree.getFont(),
                        selected,
                        getTextSelectionColor(),
                        getBackgroundSelectionColor(),
                        tree
                );
                String detail = debugValue.preview().available()
                        ? debugValue.preview().detail()
                        : debugValue.value();
                this.valueLabel.setToolTipText(debugValue.type().isBlank()
                        ? detail
                        : debugValue.type() + (detail.isBlank() ? "" : "  " + detail));
                return this.valueLabel;
            }
            switch (node.getUserObject()) {
                case ExpressionStatus status -> {
                    setText(status.expression() + " = " + status.text());
                    setToolTipText(status.error() ? status.text() : null);
                    setIcon(status.error() ? Icons.ERROR
                            : status.watch() ? Icons.WATCH : Icons.EVALUATE_EXPRESSION);
                }
                case StatusValue status -> {
                    setText(status.text());
                    setToolTipText(status.error() ? status.text() : null);
                    setIcon(status.error() ? Icons.ERROR : Icons.INFORMATION);
                }
                case MoreChildren more -> {
                    setText(more.remaining() < 0
                            ? "Load more"
                            : "Load more (" + more.remaining() + " remaining)");
                    setToolTipText("Load the next " + DebuggerInspector.CHILD_PAGE_SIZE + " values");
                    setIcon(Icons.INFORMATION);
                }
                case Placeholder ignored -> {
                    setText("Loading…");
                    setIcon(Icons.JAVA_VARIABLE);
                }
                default -> {
                }
            }
            return component;
        }

        private static Icon icon(DebugValue value) {
            return switch (value.kind()) {
                case UNKNOWN, LOCAL -> Icons.JAVA_VARIABLE;
                case PARAMETER -> Icons.JAVA_PARAMETER;
                case FIELD -> Icons.FIELD;
                case THIS -> Icons.VALUE;
                case ARRAY_ELEMENT -> value.variablesReference() > 0 ? Icons.VALUE : Icons.PRIMITIVE;
                case RETURN_VALUE -> Icons.JAVA_METHOD;
                case EXPRESSION -> value.indexedVariables() > 0 || value.type().endsWith("[]")
                        ? Icons.ARRAY
                        : value.variablesReference() > 0 ? Icons.VALUE : Icons.PRIMITIVE;
            };
        }
    }
}
