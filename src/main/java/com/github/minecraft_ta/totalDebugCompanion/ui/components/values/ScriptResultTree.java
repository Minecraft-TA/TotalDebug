package com.github.minecraft_ta.totalDebugCompanion.ui.components.values;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.script.ExecutionValue;
import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryLabel;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryText;
import com.github.minecraft_ta.totalDebugCompanion.ui.speedsearch.SpeedSearch;

import javax.swing.Icon;
import javax.swing.JTree;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import java.awt.Component;

/** Lazy Swing tree for the immutable result graph captured by a live snippet. */
public final class ScriptResultTree extends JTree {
    private static final int MAX_LABEL_VALUE_CHARACTERS = 512;
    private static final int MAX_MAP_KEY_CHARACTERS = 160;
    private static final int MAX_SEARCH_TEXT_CHARACTERS = 2_048;
    private static final int MAX_TOOLTIP_VALUE_CHARACTERS = 2_048;
    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode();
    private final DefaultTreeModel model = new DefaultTreeModel(this.root);

    public ScriptResultTree() {
        setModel(this.model);
        setRootVisible(false);
        setShowsRootHandles(true);
        setRowHeight(UiMetrics.TREE_ROW_HEIGHT);
        putClientProperty("JTree.wideSelection", true);
        setCellRenderer(new Renderer());
        addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent event) {
                Object value = event.getPath().getLastPathComponent();
                if (value instanceof SnapshotNode node) {
                    load(node);
                }
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent event) {
            }
        });
        SpeedSearch.install(this, path -> searchText(
                ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject()
        ));
    }

    public void showResult(ExecutionValue snapshot) {
        this.root.removeAllChildren();
        this.root.add(node("result", null, snapshot, true));
        this.model.reload();
        setSelectionRow(0);
    }

    public void clearResult() {
        this.root.removeAllChildren();
        this.model.reload();
    }

    private SnapshotNode node(
            String name,
            ExecutionValue.ChildKind childKind,
            ExecutionValue snapshot,
            boolean rootResult
    ) {
        SnapshotNode node = new SnapshotNode(new Row(name, childKind, snapshot, rootResult));
        if (!snapshot.children().isEmpty() || snapshot.truncated()) {
            node.add(new DefaultMutableTreeNode(Placeholder.INSTANCE));
        }
        return node;
    }

    private void load(SnapshotNode node) {
        if (node.loaded) {
            return;
        }
        node.loaded = true;
        node.removeAllChildren();
        ExecutionValue snapshot = node.row.snapshot();
        for (ExecutionValue.Child child : snapshot.children()) {
            String childName = child.kind() == ExecutionValue.ChildKind.MAP_ENTRY
                    ? "[" + child.key().displayValue(MAX_MAP_KEY_CHARACTERS) + "]"
                    : child.name().text();
            node.add(node(childName, child.kind(), child.value(), false));
        }
        if (snapshot.truncated()) {
            int omitted = Math.max(0, snapshot.totalChildren() - snapshot.children().size());
            node.add(new DefaultMutableTreeNode(new TruncatedValue(
                    omitted == 0 ? "Value truncated" : omitted + " values not captured"
            )));
        }
        this.model.nodeStructureChanged(node);
    }

    private static String searchText(Object value) {
        return switch (value) {
            case Row row -> boundedSearchText(row.name(), row.snapshot());
            case TruncatedValue truncated -> truncated.text();
            case Placeholder ignored -> "Loading";
            default -> String.valueOf(value);
        };
    }

    static String boundedSearchText(String name, ExecutionValue snapshot) {
        StringBuilder text = new StringBuilder(MAX_SEARCH_TEXT_CHARACTERS);
        appendSearchPart(text, name);
        appendSearchPart(text, snapshot.type().text());
        appendSearchPart(text, snapshot.value().text());
        appendSearchPart(text, snapshot.preview().text());
        return text.toString();
    }

    private static void appendSearchPart(StringBuilder target, String part) {
        int remaining = MAX_SEARCH_TEXT_CHARACTERS - target.length();
        if (remaining <= 0 || part.isEmpty()) {
            return;
        }
        if (!target.isEmpty()) {
            target.append(' ');
            remaining--;
        }
        int end = Math.min(part.length(), remaining);
        if (end > 0 && end < part.length()
                && Character.isHighSurrogate(part.charAt(end - 1))
                && Character.isLowSurrogate(part.charAt(end))) {
            end--;
        }
        target.append(part, 0, end);
    }

    private static String simpleType(String type) {
        int separator = Math.max(type.lastIndexOf('.'), type.lastIndexOf('$'));
        return separator < 0 ? type : type.substring(separator + 1);
    }

    private static Icon icon(Row row) {
        if (row.rootResult()) {
            return Icons.EVALUATE_EXPRESSION;
        }
        if (row.childKind() == ExecutionValue.ChildKind.FIELD
                || row.childKind() == ExecutionValue.ChildKind.RECORD_COMPONENT) {
            return Icons.FIELD;
        }
        return switch (row.snapshot().kind()) {
            case ARRAY -> Icons.ARRAY;
            case COLLECTION, MAP, OBJECT, OPTIONAL, REFERENCE -> Icons.VALUE;
            case ERROR -> Icons.ERROR;
            default -> Icons.PRIMITIVE;
        };
    }

    private record Row(
            String name,
            ExecutionValue.ChildKind childKind,
            ExecutionValue snapshot,
            boolean rootResult
    ) {
    }

    private record TruncatedValue(String text) {
    }

    private enum Placeholder {
        INSTANCE
    }

    private static final class SnapshotNode extends DefaultMutableTreeNode {
        private final Row row;
        private boolean loaded;

        private SnapshotNode(Row row) {
            super(row);
            this.row = row;
        }
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
            if (node.getUserObject() instanceof Row result) {
                ExecutionValue snapshot = result.snapshot();
                String simpleType = simpleType(snapshot.type().text());
                String primary = clip(result.name(), MAX_MAP_KEY_CHARACTERS) + " = "
                        + snapshot.displayValue(MAX_LABEL_VALUE_CHARACTERS);
                String preview = snapshot.preview().text()
                        + (snapshot.preview().truncated() ? "…" : "");
                String secondary = preview.isBlank() ? simpleType : preview;
                this.valueLabel.configure(
                        new PrimarySecondaryText(primary, secondary),
                        icon(result),
                        tree.getFont(),
                        selected,
                        getTextSelectionColor(),
                        getBackgroundSelectionColor(),
                        tree
                );
                String detail = preview.isBlank()
                        ? snapshot.displayValue(MAX_TOOLTIP_VALUE_CHARACTERS)
                        : preview;
                if (snapshot.value().truncated()) {
                    detail += "  (retained " + snapshot.value().text().length() + " of "
                            + snapshot.value().totalCharacters() + " characters)";
                } else if (snapshot.preview().truncated()) {
                    detail += "  (preview retained " + snapshot.preview().text().length() + " of "
                            + snapshot.preview().totalCharacters() + " characters)";
                }
                String type = clip(snapshot.type().text(), MAX_TOOLTIP_VALUE_CHARACTERS);
                this.valueLabel.setToolTipText(type.isBlank()
                        ? detail
                        : type + (detail.isBlank() ? "" : "  " + detail));
                return this.valueLabel;
            }
            if (node.getUserObject() instanceof TruncatedValue(String text)) {
                setText(text);
                setIcon(Icons.WARNING);
            } else if (node.getUserObject() == Placeholder.INSTANCE) {
                setText("Loading…");
                setIcon(Icons.JAVA_VARIABLE);
            }
            return this;
        }

        private static String clip(String text, int maxCharacters) {
            if (text.length() <= maxCharacters) {
                return text;
            }
            int end = maxCharacters;
            if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))
                    && Character.isLowSurrogate(text.charAt(end))) {
                end--;
            }
            return text.substring(0, end) + '…';
        }
    }
}
