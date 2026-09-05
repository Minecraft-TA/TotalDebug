package com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger;

import com.github.minecraft_ta.totalDebugCompanion.GlobalConfig;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerValueLease;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerSessionController;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.ExpressionCompletionSupport;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.JavaExpressionField;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.editors.DebuggerEditorPresentation;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.DynamicMatteBorder;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import static com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger.DebuggerValueTree.addPlaceholder;
import static com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger.DebuggerValueTree.debugValue;
import static com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger.DebuggerValueTree.expressionOf;
import static com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger.DebuggerValueTree.hasPlaceholder;
import static com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger.DebuggerValueTree.isWatch;
import static com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger.DebuggerValueTree.valueNode;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger.DebuggerValueTree.DebugValue;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger.DebuggerValueTree.ExpressionStatus;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger.DebuggerValueTree.ExpressionValue;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger.DebuggerValueTree.MoreChildren;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger.DebuggerValueTree.Placeholder;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger.DebuggerValueTree.StatusValue;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger.DebuggerExpressionModel.Key;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger.DebuggerExpressionModel.Outcome;

/** Expression input, watches, and the lazily paged value tree for one selected frame. */
final class DebuggerInspector extends JPanel implements AutoCloseable {
    static final int CHILD_PAGE_SIZE = 200;
    private static final String LOADING = "Loading…";
    private static final String ADD_WATCH_ACTION = "debugger.addWatch";

    interface RuntimeAccess {
        CompletableFuture<DebuggerValueLease> retainValue(String pauseId, int reference);
        CompletableFuture<List<DebugEngine.Variable>> variables(
                DebugEngine.StackFrame frame,
                int variablesReference,
                int start,
                int count
        );

        CompletableFuture<DebugEngine.ValuePreview> preview(
                DebugEngine.StackFrame frame,
                int variablesReference
        );

        CompletableFuture<DebugEngine.EvaluationResult> evaluate(
                String expression,
                DebugEngine.StackFrame frame
        );

        CompletableFuture<DebugEngine.EvaluationResult> inspect(
                String expression,
                DebugEngine.StackFrame frame
        );
    }

    private final DebuggerSessionController controller;
    private final RuntimeAccess runtime;
    private final Consumer<NavigationTarget> navigation;
    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("Variables");
    private final DefaultTreeModel model = new DefaultTreeModel(this.root);
    private final JTree tree = DebuggerValueTree.create(this.model);
    private final JavaExpressionField expression = new JavaExpressionField();
    private final ExpressionCompletionSupport expressionCompletion = new ExpressionCompletionSupport(this.expression);
    private final JButton addWatch = toolbarButton(Icons.ADD_TO_WATCH, "Add Watch (Shift+Enter)");
    private final DebuggerExpressionModel expressions = new DebuggerExpressionModel();
    private final Map<Key, DefaultMutableTreeNode> expressionNodes = new LinkedHashMap<>();
    private final PropertyChangeListener previewSettingsListener = event -> onEventThread(this::refreshPreviewMode);

    private long revision;
    private long previewRevision;
    private DebugEngine.StackFrame frame;
    private List<DebugEngine.Variable> currentVariables = List.of();
    private String variableStatus = "Variables are available while paused";
    private boolean frameReady;
    private boolean disposed;
    private boolean expressionPending;
    private Object pendingExpression;
    private boolean watchSequenceStopped;

    DebuggerInspector(
            DebuggerSessionController controller,
            Consumer<NavigationTarget> navigation
    ) {
        this(controller, navigation, new RuntimeAccess() {
            @Override public CompletableFuture<DebuggerValueLease> retainValue(String pauseId, int reference) {
                return controller.retainValue(pauseId, reference);
            }
            @Override
            public CompletableFuture<List<DebugEngine.Variable>> variables(
                    DebugEngine.StackFrame frame,
                    int variablesReference,
                    int start,
                    int count
            ) {
                return controller.variables(frame, variablesReference, start, count);
            }

            @Override
            public CompletableFuture<DebugEngine.ValuePreview> preview(
                    DebugEngine.StackFrame frame,
                    int variablesReference
            ) {
                return controller.preview(frame, variablesReference);
            }

            @Override
            public CompletableFuture<DebugEngine.EvaluationResult> evaluate(
                    String expression,
                    DebugEngine.StackFrame frame
            ) {
                return controller.evaluate(expression, frame);
            }

            @Override
            public CompletableFuture<DebugEngine.EvaluationResult> inspect(
                    String expression,
                    DebugEngine.StackFrame frame
            ) {
                return controller.inspectExpression(expression, frame);
            }
        });
    }

    DebuggerInspector(
            DebuggerSessionController controller,
            Consumer<NavigationTarget> navigation,
            RuntimeAccess runtime
    ) {
        super(new BorderLayout());
        this.controller = Objects.requireNonNull(controller, "controller");
        this.navigation = Objects.requireNonNull(navigation, "navigation");
        this.runtime = Objects.requireNonNull(runtime, "runtime");

        this.expression.setPlaceholder("Evaluate Expression (Enter)");
        this.expression.setExpandable(true);
        this.expression.addPropertyChangeListener("multiline", event -> {
            boolean expanded = this.expression.isMultiline();
            this.expression.setPlaceholder(expanded ? "Evaluate Code (Ctrl+Enter)" : "Evaluate Expression (Enter)");
            this.expression.setToolTipText(expanded
                    ? "Evaluate (Ctrl+Enter); Add Watch (Ctrl+Shift+Enter)"
                    : "Evaluate Expression (Enter); Add Watch (Shift+Enter)");
        });
        this.expression.setToolTipText("Evaluate Expression (Enter); Add Watch (Shift+Enter)");
        this.expression.addActionListener(event -> evaluateExpression(false));
        this.expression.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK),
                ADD_WATCH_ACTION
        );
        this.expression.getActionMap().put(ADD_WATCH_ACTION, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                if (expression.isMultiline() && (event.getModifiers() & ActionEvent.CTRL_MASK) == 0) {
                    expression.replaceSelection("\n");
                } else evaluateExpression(true);
            }
        });
        this.expression.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK),
                "addFragmentWatch");
        this.expression.getActionMap().put("addFragmentWatch", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { evaluateExpression(true); }
        });
        this.addWatch.addActionListener(event -> evaluateExpression(true));

        this.tree.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "removeWatch");
        this.tree.getActionMap().put("removeWatch", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                removeSelectedExpression();
            }
        });
        installExpansion();
        installContextMenu();

        JPanel input = new JPanel(new BorderLayout(6, 0));
        input.setBorder(BorderFactory.createCompoundBorder(
                DynamicMatteBorder.separatorRule(0, 0, 1, 0),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)
        ));
        input.add(this.expression.component(), BorderLayout.CENTER);
        input.add(this.addWatch, BorderLayout.EAST);
        JScrollPane scroll = new JScrollPane(this.tree);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        javax.swing.JSplitPane editorSplit = new javax.swing.JSplitPane(javax.swing.JSplitPane.VERTICAL_SPLIT, input, scroll);
        editorSplit.setBorder(BorderFactory.createEmptyBorder());
        editorSplit.setDividerSize(0);
        editorSplit.setResizeWeight(0);
        this.expression.addPropertyChangeListener("multiline", event -> {
            editorSplit.setDividerSize(this.expression.isMultiline() ? 5 : 0);
            SwingUtilities.invokeLater(editorSplit::resetToPreferredSizes);
        });
        add(editorSplit, BorderLayout.CENTER);
        GlobalConfig.getInstance().addAutomaticDebuggerPreviewsListener(this.previewSettingsListener);
    }

    void setEvaluationBusy(boolean busy) {
        boolean enabled = !busy && this.controller.status().phase() == DebuggerSessionController.Phase.PAUSED;
        this.expression.setEnabled(enabled);
        this.addWatch.setEnabled(enabled);
    }

    void setPaused(boolean paused) {
        this.expression.setEnabled(paused);
        this.addWatch.setEnabled(paused);
        if (!paused) {
            this.expressionCompletion.setCompletionProvider(null);
            this.expression.setSemanticTokenProvider(null);
            this.revision++;
            this.previewRevision++;
            this.frameReady = false;
            DebuggerEditorPresentation.clear();
        }
    }

    void beginFrame(DebugEngine.StackFrame frame) {
        this.frame = Objects.requireNonNull(frame, "frame");
        this.currentVariables = List.of();
        this.variableStatus = LOADING;
        this.frameReady = false;
        this.revision++;
        this.previewRevision++;
        this.expressions.nextFrame(this.controller.snapshot().pauseId(), frame.id());
        this.watchSequenceStopped = false;
        this.expressionNodes.clear();
        this.expressionCompletion.setCompletionProvider((text, caret, explicit) ->
                this.controller.completions(text, caret, frame));
        this.expression.setSemanticTokenProvider(text -> this.controller.expressionTokens(text, frame));
        rebuild();
    }

    void showVariables(DebugEngine.StackFrame frame, List<DebugEngine.Variable> values) {
        if (!Objects.equals(frame, this.frame)) {
            return;
        }
        this.currentVariables = List.copyOf(values);
        this.variableStatus = null;
        this.frameReady = true;
        rebuild();
    }

    void showStatus(DebugEngine.StackFrame frame, String status) {
        if (frame != null && !Objects.equals(frame, this.frame)) {
            return;
        }
        this.variableStatus = Objects.requireNonNull(status, "status");
        this.frameReady = false;
        rebuild();
    }

    void clear() {
        this.revision++;
        this.previewRevision++;
        this.frame = null;
        this.currentVariables = List.of();
        this.variableStatus = "Variables are available while paused";
        this.frameReady = false;
        this.expressions.clearSession();
        this.expressionNodes.clear();
        rebuild();
    }

    void focusVariable(DebugEngine.StackFrame frame, DebugEngine.Variable variable) {
        if (!Objects.equals(frame, this.frame)) {
            return;
        }
        for (int index = 0; index < this.root.getChildCount(); index++) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) this.root.getChildAt(index);
            DebugValue value = debugValue(node.getUserObject());
            if (value == null || !sameVariable(value, variable)) {
                continue;
            }
            TreePath path = new TreePath(node.getPath());
            this.tree.setSelectionPath(path);
            this.tree.scrollPathToVisible(path);
            this.tree.requestFocusInWindow();
            return;
        }
    }

    JPopupMenu createContextMenu(TreePath path) {
        JPopupMenu menu = new JPopupMenu();
        if (path == null || !(path.getLastPathComponent() instanceof DefaultMutableTreeNode selected)) {
            return menu;
        }

        Object selectedValue = selected.getUserObject();
        DebugValue value = debugValue(selectedValue);
        DebugEngine.Variable variable = value == null ? null : value.sourceVariable();
        DebugEngine.StackFrame currentFrame = this.frame;
        DebugEngine.Variable parent = parentVariable(selected);

        if (variable != null && currentFrame != null && supportsDeclarationNavigation(variable)) {
            menu.add(menuItem("Jump to Source", Icons.JAVA_VARIABLE,
                    () -> navigateToDeclaration(currentFrame, variable, parent)));
        }
        if (value != null && currentFrame != null) {
            Optional<NavigationTarget.RuntimeClass> typeTarget = variable == null
                    ? DebuggerVariableNavigation.typeTarget(value.type())
                    : DebuggerVariableNavigation.typeTarget(currentFrame, variable);
            typeTarget.ifPresent(target -> menu.add(menuItem(
                    "Jump to Type Source",
                    Icons.JAVA_CLASS,
                    () -> this.navigation.accept(target)
            )));
        }
        if (menu.getComponentCount() > 0) {
            menu.addSeparator();
        }

        if (variable != null && isAssignable(variable)) {
            menu.add(menuItem("Set Value…", Icons.VALUE, () -> setValue(variable)));
        }
        if (value != null) {
            menu.add(menuItem("Copy Value", Icons.COPY, () -> copy(value.value())));
            if (!value.evaluateName().isBlank()) {
                menu.add(menuItem("Copy Expression", Icons.COPY, () -> copy(value.evaluateName())));
            }
        } else {
            String expression = expressionOf(selectedValue);
            if (!expression.isBlank()) {
                menu.add(menuItem("Copy Expression", Icons.COPY, () -> copy(expression)));
            }
        }

        String selectedExpression = expressionOf(selectedValue);
        if (!selectedExpression.isBlank()) {
            menu.addSeparator();
            if (isWatch(selectedValue)) {
                menu.add(menuItem("Remove Watch", Icons.DELETE,
                        () -> removeExpression(selectedExpression, true)));
            } else {
                menu.add(menuItem("Add to Watches", Icons.ADD_TO_WATCH,
                        () -> addWatch(selectedExpression)));
            }
        }
        return menu;
    }

    private void rebuild() {
        this.root.removeAllChildren();
        this.expressionNodes.clear();
        for (Key key : this.expressions.rows()) {
            addExpressionNode(key);
        }

        List<DefaultMutableTreeNode> variableNodes = new ArrayList<>();
        if (this.variableStatus != null) {
            this.root.add(new DefaultMutableTreeNode(new StatusValue(this.variableStatus, false)));
        } else {
            List<DebugEngine.Variable> displayedValues = new ArrayList<>(this.currentVariables);
            displayedValues.sort(Comparator.comparingInt(variable ->
                    variable.kind() == DebugEngine.VariableKind.THIS ? 0 : 1));
            for (DebugEngine.Variable variable : displayedValues) {
                DefaultMutableTreeNode node = valueNode(DebugValue.from(variable));
                this.root.add(node);
                variableNodes.add(node);
            }
            if (displayedValues.isEmpty()) {
                this.root.add(new DefaultMutableTreeNode(new StatusValue("No variables available", false)));
            }
        }
        this.model.reload();

        if (!this.frameReady || this.frame == null) {
            return;
        }
        inspectNextWatch();
        if (!this.expressionPending) startRootPreviewBatch(variableNodes);
    }

    private void addExpressionNode(Key key) {
        Outcome outcome = this.expressions.outcome(key);
        Object userObject;
        if (outcome == null) {
            String status = this.frameReady ? (key.watch() ? "Watch  " + LOADING : LOADING)
                    : "Not available while running";
            userObject = new ExpressionStatus(key.expression(), status, false, key.watch());
        } else if (outcome.failure() != null) {
            userObject = new ExpressionStatus(key.expression(), outcome.failure(), true, key.watch());
        } else {
            DebugValue value = GlobalConfig.getInstance().automaticDebuggerPreviews()
                    ? outcome.value()
                    : outcome.value().withPreview(DebugEngine.ValuePreview.NONE);
            userObject = new ExpressionValue(value, key.watch());
        }
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(userObject);
        DebugValue value = debugValue(userObject);
        if (value != null) {
            addPlaceholder(node, value);
        }
        this.root.add(node);
        this.expressionNodes.put(key, node);
    }

    private void inspectNextWatch() {
        if (this.expressionPending || this.watchSequenceStopped || this.frame == null) return;
        for (Key key : this.expressions.rows()) {
            if (key.watch() && this.expressions.submitOnce(key)) {
                this.expressionPending = true;
                submitExpression(key, this.runtime.inspect(key.expression(), this.frame));
                return;
            }
        }
    }

    private void submitExplicitExpression(Key key) {
        if (this.frame == null) {
            return;
        }
        this.expressionPending = true;
        rebuild();
        submitExpression(key, this.runtime.evaluate(key.expression(), this.frame));
    }

    private void submitExpression(
            Key key,
            CompletableFuture<DebugEngine.EvaluationResult> future
    ) {
        long requestRevision = this.revision;
        DebugEngine.StackFrame requestFrame = this.frame;
        long started = System.nanoTime();
        var complete = this.expressions.completionFor(key);
        Object request = new Object();
        this.pendingExpression = request;
        String pauseId = this.controller.snapshot().pauseId();
        future.thenCompose(result -> this.runtime.retainValue(pauseId, result.variablesReference())
                .thenApply(lease -> new RetainedResult(result, lease))).whenComplete((retained, failure) -> onEventThread(() -> {
            if (this.pendingExpression == request) this.expressionPending = false;
            Outcome outcome = failure == null
                    ? Outcome.success(DebugValue.from(key.expression(), retained.result()))
                    : Outcome.failure(failureMessage(failure, "Evaluation failed"));
            if (!complete.apply(outcome, retained == null ? DebuggerValueLease.NONE : retained.lease())) {
                if (this.frameReady && !this.disposed) inspectNextWatch();
                return;
            }
            this.watchSequenceStopped |= failure != null || System.nanoTime() - started >= 5_000_000_000L;
            if (isStale(requestFrame, requestRevision)) {
                if (this.frameReady) inspectNextWatch();
                return;
            }
            DefaultMutableTreeNode node = this.expressionNodes.get(key);
            if (node == null || node.getParent() != this.root) {
                return;
            }
            node.removeAllChildren();
            if (outcome.failure() != null) {
                node.setUserObject(new ExpressionStatus(
                        key.expression(),
                        outcome.failure(),
                        true,
                        key.watch()
                ));
            } else {
                node.setUserObject(new ExpressionValue(outcome.value(), key.watch()));
                addPlaceholder(node, outcome.value());
            }
            this.model.nodeStructureChanged(node);
            inspectNextWatch();
            if (outcome.value() != null && !this.expressionPending) {
                requestTreePreview(node, requestRevision);
            }
        }));
    }

    private record RetainedResult(DebugEngine.EvaluationResult result, DebuggerValueLease lease) { }

    private void startRootPreviewBatch(List<DefaultMutableTreeNode> nodes) {
        DebugEngine.StackFrame requestFrame = this.frame;
        if (requestFrame == null) {
            return;
        }
        long requestRevision = this.revision;
        long requestPreviewRevision = ++this.previewRevision;
        boolean enabled = GlobalConfig.getInstance().automaticDebuggerPreviews();
        List<DefaultMutableTreeNode> previewNodes = enabled
                ? nodes.stream().filter(node -> {
                    DebugValue value = debugValue(node.getUserObject());
                    return value != null && value.variablesReference() > 0;
                }).toList()
                : List.of();
        if (previewNodes.isEmpty()) {
            DebuggerEditorPresentation.select(requestFrame, this.currentVariables);
            return;
        }

        previewRoot(previewNodes, 0, requestFrame, requestRevision, requestPreviewRevision, new LinkedHashMap<>());
    }

    private void previewRoot(List<DefaultMutableTreeNode> nodes, int index, DebugEngine.StackFrame requestFrame,
                             long requestRevision, long requestPreviewRevision,
                             Map<Integer, DebugEngine.ValuePreview> previews) {
        if (isStale(requestFrame, requestRevision) || requestPreviewRevision != this.previewRevision) return;
        if (index >= nodes.size() || this.expressionPending) {
            DebuggerEditorPresentation.select(requestFrame, this.currentVariables, previews);
            return;
        }
        DefaultMutableTreeNode node = nodes.get(index);
        DebugValue value = Objects.requireNonNull(debugValue(node.getUserObject()));
        long started = System.nanoTime();
        this.runtime.preview(requestFrame, value.variablesReference()).whenComplete((preview, failure) ->
                onEventThread(() -> {
                    if (isStale(requestFrame, requestRevision) || requestPreviewRevision != this.previewRevision) return;
                    if (failure == null && preview != null) {
                        applyPreview(node, value, preview);
                        previews.put(value.variablesReference(), preview);
                    }
                    if (failure != null || System.nanoTime() - started >= 5_000_000_000L) {
                        DebuggerEditorPresentation.select(requestFrame, this.currentVariables, previews);
                    } else {
                        previewRoot(nodes, index + 1, requestFrame, requestRevision, requestPreviewRevision, previews);
                    }
                }));
    }

    private void requestTreePreview(DefaultMutableTreeNode node, long requestRevision) {
        DebugValue value = debugValue(node.getUserObject());
        DebugEngine.StackFrame requestFrame = this.frame;
        if (requestFrame == null || value == null || value.variablesReference() <= 0
                || !GlobalConfig.getInstance().automaticDebuggerPreviews()) {
            return;
        }
        this.runtime.preview(requestFrame, value.variablesReference()).whenComplete((preview, failure) ->
                onEventThread(() -> {
                    if (isStale(requestFrame, requestRevision) || node.getParent() == null
                            || failure != null && isCancellation(failure)) {
                        return;
                    }
                    applyPreview(node, value, failure == null && preview != null
                            ? preview
                            : DebugEngine.ValuePreview.NONE);
                })
        );
    }

    private void applyPreview(
            DefaultMutableTreeNode node,
            DebugValue expected,
            DebugEngine.ValuePreview preview
    ) {
        if (!Objects.equals(debugValue(node.getUserObject()), expected) || !preview.available()) {
            return;
        }
        DebugValue replacement = expected.withPreview(preview);
        if (node.getUserObject() instanceof ExpressionValue expressionValue) {
            this.expressions.complete(
                    new Key(replacement.name(), expressionValue.watch()),
                    Outcome.success(replacement)
            );
            node.setUserObject(new ExpressionValue(replacement, expressionValue.watch()));
        } else {
            node.setUserObject(replacement);
        }
        this.model.nodeChanged(node);
    }

    private void refreshPreviewMode() {
        if (this.frame == null || !this.frameReady) {
            return;
        }
        rebuild();
    }

    private void installExpansion() {
        this.tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent event) {
                Object selected = event.getPath().getLastPathComponent();
                if (selected instanceof DefaultMutableTreeNode node) {
                    loadChildren(node);
                }
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent event) {
            }
        });
    }

    private void loadChildren(DefaultMutableTreeNode node) {
        if (node.getUserObject() instanceof MoreChildren more) {
            loadMore(node, more);
            return;
        }
        DebugValue value = debugValue(node.getUserObject());
        if (value == null || value.variablesReference() <= 0 || !hasPlaceholder(node)) {
            return;
        }
        node.removeAllChildren();
        node.add(new DefaultMutableTreeNode(new StatusValue(LOADING, false)));
        this.model.nodeStructureChanged(node);
        requestPage(node, value, 0, null);
    }

    private void loadMore(DefaultMutableTreeNode moreNode, MoreChildren more) {
        if (!(moreNode.getParent() instanceof DefaultMutableTreeNode parent)) {
            return;
        }
        int index = parent.getIndex(moreNode);
        parent.remove(moreNode);
        DefaultMutableTreeNode loading = new DefaultMutableTreeNode(new StatusValue(LOADING, false));
        parent.insert(loading, index);
        this.model.nodeStructureChanged(parent);
        requestPage(parent, more.owner(), more.nextStart(), loading);
    }

    private void requestPage(
            DefaultMutableTreeNode parent,
            DebugValue owner,
            int start,
            DefaultMutableTreeNode loading
    ) {
        DebugEngine.StackFrame requestFrame = this.frame;
        long requestRevision = this.revision;
        if (requestFrame == null) {
            return;
        }
        if (owner.namedVariables() > 0 && owner.indexedVariables() > 0) {
            parent.removeAllChildren();
            parent.add(new DefaultMutableTreeNode(new StatusValue(
                    "Mixed named and indexed debugger values are not supported",
                    true
            )));
            this.model.nodeStructureChanged(parent);
            return;
        }
        int count = owner.indexedVariables() > 0 ? CHILD_PAGE_SIZE : 0;
        this.runtime.variables(requestFrame, owner.variablesReference(), start, count)
                .whenComplete((children, failure) -> onEventThread(() -> {
                    if (isStale(requestFrame, requestRevision)) {
                        return;
                    }
                    if (failure != null && isCancellation(failure)) {
                        return;
                    }
                    if (start == 0) {
                        parent.removeAllChildren();
                    } else if (loading != null && loading.getParent() == parent) {
                        parent.remove(loading);
                    }
                    if (failure != null) {
                        parent.add(new DefaultMutableTreeNode(new StatusValue(
                                failureMessage(failure, "Unable to load value"),
                                true
                        )));
                        this.model.nodeStructureChanged(parent);
                        return;
                    }
                    for (DebugEngine.Variable child : children) {
                        DefaultMutableTreeNode childNode = valueNode(DebugValue.from(child));
                        parent.add(childNode);
                        requestTreePreview(childNode, requestRevision);
                    }
                    int nextStart = start + children.size();
                    if (hasMore(owner, nextStart)) {
                        DefaultMutableTreeNode more = new DefaultMutableTreeNode(
                                new MoreChildren(owner, nextStart, remaining(owner, nextStart))
                        );
                        more.add(new DefaultMutableTreeNode(Placeholder.INSTANCE));
                        parent.add(more);
                    }
                    this.model.nodeStructureChanged(parent);
                }));
    }

    private static boolean hasMore(DebugValue owner, int nextStart) {
        if (owner.indexedVariables() <= 0) {
            return false;
        }
        return nextStart < owner.indexedVariables();
    }

    private static int remaining(DebugValue owner, int nextStart) {
        return owner.indexedVariables() > 0
                ? Math.max(0, owner.indexedVariables() - nextStart)
                : -1;
    }

    private void evaluateExpression(boolean addAsWatch) {
        String requested = this.expression.getText().trim();
        if (requested.isEmpty() || this.frame == null || !this.frameReady) {
            return;
        }
        if (addAsWatch) {
            addWatch(requested);
            this.expression.setText("");
            return;
        }
        submitExplicitExpression(this.expressions.beginExplicit(requested));
    }

    void addWatch(String requested) {
        String normalized = Objects.requireNonNull(requested, "requested").trim();
        if (normalized.isEmpty()) {
            return;
        }
        this.expressions.addWatch(normalized);
        rebuild();
    }

    private void removeSelectedExpression() {
        TreePath selection = this.tree.getSelectionPath();
        if (selection == null) {
            return;
        }
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) selection.getLastPathComponent();
        while (node.getParent() != this.root && node.getParent() instanceof DefaultMutableTreeNode parent) {
            node = parent;
        }
        if (!(node.getUserObject() instanceof ExpressionValue)
                && !(node.getUserObject() instanceof ExpressionStatus)) {
            return;
        }
        removeExpression(expressionOf(node.getUserObject()), isWatch(node.getUserObject()));
    }

    private void removeExpression(String selectedExpression, boolean watch) {
        this.expressions.remove(new Key(selectedExpression, watch));
        rebuild();
    }

    private void navigateToDeclaration(
            DebugEngine.StackFrame frame,
            DebugEngine.Variable variable,
            DebugEngine.Variable parent
    ) {
        DebugEngine.Source source = frame.sourceUri() == null ? null : this.controller.source(frame.sourceUri());
        CompletableFuture.supplyAsync(() ->
                DebuggerVariableNavigation.declarationTarget(source, frame, variable, parent)
        ).whenComplete((target, failure) -> onEventThread(() -> {
            if (failure != null) {
                showOperationFailure("Unable to Jump to Source", failure);
            } else if (target.isEmpty()) {
                JOptionPane.showMessageDialog(
                        this,
                        "No source declaration was found for " + variable.name(),
                        "Unable to Jump to Source",
                        JOptionPane.INFORMATION_MESSAGE
                );
            } else {
                this.navigation.accept(target.get());
            }
        }));
    }

    private void setValue(DebugEngine.Variable variable) {
        DebugEngine.StackFrame currentFrame = this.frame;
        if (currentFrame == null) {
            return;
        }
        Object replacement = JOptionPane.showInputDialog(
                this,
                "New value:",
                "Set Value: " + variable.name(),
                JOptionPane.PLAIN_MESSAGE,
                null,
                null,
                variable.value()
        );
        if (replacement == null) {
            return;
        }
        this.controller.setVariable(variable, replacement.toString(), currentFrame)
                .whenComplete((variables, failure) -> onEventThread(() -> {
                    if (failure != null) {
                        showOperationFailure("Unable to Set Value", failure);
                    } else {
                        showVariables(currentFrame, variables);
                    }
                }));
    }

    private void installContextMenu() {
        this.tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                showPopup(event);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                showPopup(event);
            }

            private void showPopup(MouseEvent event) {
                if (!event.isPopupTrigger()) {
                    return;
                }
                TreePath path = tree.getPathForLocation(event.getX(), event.getY());
                if (path == null) {
                    return;
                }
                tree.setSelectionPath(path);
                JPopupMenu menu = createContextMenu(path);
                if (menu.getComponentCount() > 0) {
                    menu.show(tree, event.getX(), event.getY());
                }
            }
        });
    }

    private boolean isStale(DebugEngine.StackFrame requestedFrame, long requestedRevision) {
        return requestedRevision != this.revision || !Objects.equals(requestedFrame, this.frame);
    }

    private static boolean isCancellation(Throwable failure) {
        Throwable current = unwrap(failure);
        return current instanceof CancellationException;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String failureMessage(Throwable failure, String fallback) {
        String detail = unwrap(failure).getMessage();
        return detail == null || detail.isBlank() ? fallback : detail;
    }

    private void showOperationFailure(String title, Throwable failure) {
        JOptionPane.showMessageDialog(
                this,
                failureMessage(failure, title),
                title,
                JOptionPane.ERROR_MESSAGE
        );
    }

    @Override
    public void close() {
        if (this.disposed) {
            return;
        }
        this.disposed = true;
        this.expressions.clearSession();
        this.revision++;
        this.previewRevision++;
        this.expressionCompletion.close();
        GlobalConfig.getInstance().removeAutomaticDebuggerPreviewsListener(this.previewSettingsListener);
    }

    private static JButton toolbarButton(Icon icon, String tooltip) {
        JButton button = new JButton(icon);
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.setMargin(new Insets(4, 6, 4, 6));
        return button;
    }

    private static JMenuItem menuItem(String text, Icon icon, Runnable action) {
        JMenuItem item = new JMenuItem(text, icon);
        item.addActionListener(event -> action.run());
        return item;
    }

    private static void copy(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    private static void onEventThread(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }

    private static DebugEngine.Variable parentVariable(DefaultMutableTreeNode node) {
        if (!(node.getParent() instanceof DefaultMutableTreeNode parent)) {
            return null;
        }
        DebugValue parentValue = debugValue(parent.getUserObject());
        return parentValue == null ? null : parentValue.sourceVariable();
    }

    private static boolean supportsDeclarationNavigation(DebugEngine.Variable variable) {
        return variable.kind() == DebugEngine.VariableKind.THIS
                || variable.kind() == DebugEngine.VariableKind.PARAMETER
                || variable.kind() == DebugEngine.VariableKind.LOCAL
                || variable.kind() == DebugEngine.VariableKind.FIELD;
    }

    private static boolean isAssignable(DebugEngine.Variable variable) {
        return variable.containerReference() > 0
                && !variable.adapterName().isBlank()
                && variable.kind() != DebugEngine.VariableKind.THIS
                && variable.kind() != DebugEngine.VariableKind.RETURN_VALUE
                && variable.kind() != DebugEngine.VariableKind.EXPRESSION;
    }

    private static boolean sameVariable(DebugValue displayed, DebugEngine.Variable requested) {
        if (!requested.evaluateName().isBlank() && !displayed.evaluateName().isBlank()) {
            return requested.evaluateName().equals(displayed.evaluateName());
        }
        return requested.name().equals(displayed.name());
    }

}
