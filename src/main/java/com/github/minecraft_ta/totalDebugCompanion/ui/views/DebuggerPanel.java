package com.github.minecraft_ta.totalDebugCompanion.ui.views;

import com.github.minecraft_ta.totalDebugCompanion.GlobalConfig;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerSessionController;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.ExpressionCompletionSupport;
import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryLabel;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryText;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.DynamicMatteBorder;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;

import javax.swing.AbstractAction;
import javax.swing.AbstractListModel;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.ExpandVetoException;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Reusable debugger workspace. Its host decides whether it is docked or placed in a window. */
public final class DebuggerPanel extends JPanel {
    private static final String LOADING = "Loading…";

    @FunctionalInterface
    public interface FrameNavigation {
        void open(DebugEngine.StackFrame frame, boolean activateEditor);
    }

    private final DebuggerSessionController controller;
    private final DebuggerActions debuggerActions;
    private final FrameNavigation frameNavigation;
    private final JLabel statusLabel = new JLabel("Debugger is unavailable", Icons.INFORMATION, JLabel.LEADING);
    private final JLabel frameLabel = new MutedLabel();
    private final FrameListModel frameModel = new FrameListModel();
    private final JList<DebugEngine.StackFrame> frames = new JList<>(this.frameModel);
    private final DefaultMutableTreeNode variableRoot = new DefaultMutableTreeNode("Variables");
    private final DefaultTreeModel variableModel = new DefaultTreeModel(this.variableRoot);
    private final JTree variables = createValueTree(this.variableModel);
    private final DefaultMutableTreeNode watchRoot = new DefaultMutableTreeNode("Watches");
    private final DefaultTreeModel watchModel = new DefaultTreeModel(this.watchRoot);
    private final JTree watchTree = createValueTree(this.watchModel);
    private final JTextField expression = new JTextField();
    private final ExpressionCompletionSupport expressionCompletion = new ExpressionCompletionSupport(this.expression);
    private final JButton evaluate = new JButton("Evaluate");
    private final JButton addWatch = new JButton("Add Watch");
    private final JButton removeWatch = toolbarButton(Icons.DELETE, "Remove selected watch (Delete)");
    private final Set<String> watches = new LinkedHashSet<>(GlobalConfig.getInstance().debuggerWatches());
    private final JTabbedPane inspectorTabs = new JTabbedPane();
    private final JButton attach;
    private final JButton resume;
    private final JButton stepOver;
    private final JButton stepInto;
    private final JButton stepOut;
    private final JButton detach;
    private final JPanel watchesPanel;
    private final DebuggerSessionController.Listener listener = new DebuggerSessionController.Listener() {
        @Override
        public void statusChanged(DebuggerSessionController.Status status) {
            onEventThread(() -> applyStatus(status));
        }

        @Override
        public void paused(DebuggerSessionController.PausedState state) {
            onEventThread(() -> showPausedState(state));
        }
    };

    private long viewRevision;
    private DebugEngine.StackFrame currentFrame;
    private List<DebugEngine.Variable> currentVariables = List.of();
    private DebuggerSessionController.PausedState currentPause;
    private String lastEvaluationExpression = "";
    private boolean disposed;

    DebuggerPanel(
            DebuggerSessionController controller,
            DebuggerActions debuggerActions,
            FrameNavigation frameNavigation
    ) {
        super(new BorderLayout());
        this.controller = Objects.requireNonNull(controller, "controller");
        this.debuggerActions = Objects.requireNonNull(debuggerActions, "debuggerActions");
        this.frameNavigation = Objects.requireNonNull(frameNavigation, "frameNavigation");
        this.attach = toolbarButton(debuggerActions.attach());
        this.resume = toolbarButton(debuggerActions.resume());
        this.stepOver = toolbarButton(debuggerActions.stepOver());
        this.stepInto = toolbarButton(debuggerActions.stepInto());
        this.stepOut = toolbarButton(debuggerActions.stepOut());
        this.detach = toolbarButton(debuggerActions.detach());
        this.watchesPanel = createWatchesPanel();

        setBorder(BorderFactory.createEmptyBorder());
        add(createToolbar(), BorderLayout.NORTH);

        configureFrames();
        installExpansion(this.variables, this.variableModel);
        installExpansion(this.watchTree, this.watchModel);

        this.inspectorTabs.setBorder(BorderFactory.createEmptyBorder());
        this.inspectorTabs.addTab("Variables", scroll(this.variables));
        this.inspectorTabs.addTab("Watches", this.watchesPanel);

        JSplitPane split = new InitialProportionSplitPane(
                0.42,
                createFramesPanel(),
                this.inspectorTabs
        );
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setDividerSize(1);
        split.setResizeWeight(0.42);
        split.setContinuousLayout(true);
        add(split, BorderLayout.CENTER);

        this.controller.addListener(this.listener);
    }

    private JComponent createToolbar() {
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEADING, 2, 2));
        actions.setOpaque(false);
        actions.add(this.attach);
        actions.add(this.resume);
        actions.add(this.stepOver);
        actions.add(this.stepInto);
        actions.add(this.stepOut);
        actions.add(toolbarSeparator());
        actions.add(this.detach);

        JPanel state = new JPanel();
        state.setOpaque(false);
        state.setLayout(new BoxLayout(state, BoxLayout.LINE_AXIS));
        state.add(this.statusLabel);
        state.add(Box.createHorizontalStrut(12));
        state.add(this.frameLabel);

        JPanel toolbar = new JPanel(new BorderLayout(12, 0));
        toolbar.setBorder(BorderFactory.createCompoundBorder(
                DynamicMatteBorder.separatorRule(0, 0, 1, 0),
                BorderFactory.createEmptyBorder(2, 4, 2, 8)
        ));
        toolbar.add(actions, BorderLayout.WEST);
        toolbar.add(state, BorderLayout.CENTER);
        return toolbar;
    }

    private void configureFrames() {
        this.frames.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        this.frames.setFixedCellHeight(UiMetrics.TREE_ROW_HEIGHT);
        this.frames.setCellRenderer(new FrameRenderer());
        this.frames.putClientProperty("List.isFileList", false);
        this.frames.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                selectFrame(this.frames.getSelectedIndex());
            }
        });
        this.frames.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)) {
                    DebugEngine.StackFrame frame = frames.getSelectedValue();
                    if (frame != null) {
                        frameNavigation.open(frame, true);
                    }
                }
            }
        });
    }

    private JComponent createFramesPanel() {
        JLabel heading = new JLabel("Frames");
        heading.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(DynamicMatteBorder.separatorRule(0, 0, 0, 1));
        panel.add(heading, BorderLayout.NORTH);
        panel.add(scroll(this.frames), BorderLayout.CENTER);
        return panel;
    }

    private JPanel createWatchesPanel() {
        this.expression.putClientProperty("JTextField.placeholderText", "Evaluate expression or add a watch");
        this.expression.setToolTipText(
                "Supports rich Java expressions, including members, operators, and method calls"
        );
        this.expression.addActionListener(event -> evaluateExpression(false));
        this.evaluate.addActionListener(event -> evaluateExpression(false));
        this.addWatch.addActionListener(event -> evaluateExpression(true));
        this.removeWatch.addActionListener(event -> removeSelectedExpression());
        this.removeWatch.setEnabled(false);
        this.watchTree.addTreeSelectionListener(event ->
                this.removeWatch.setEnabled(this.watchTree.getSelectionPath() != null));
        this.watchTree.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "removeWatch");
        this.watchTree.getActionMap().put("removeWatch", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                removeSelectedExpression();
            }
        });

        JPanel input = new JPanel(new BorderLayout(6, 0));
        input.setBorder(BorderFactory.createCompoundBorder(
                DynamicMatteBorder.separatorRule(0, 0, 1, 0),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)
        ));
        input.add(this.expression, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.TRAILING, 4, 0));
        actions.setOpaque(false);
        actions.add(this.evaluate);
        actions.add(this.addWatch);
        actions.add(this.removeWatch);
        input.add(actions, BorderLayout.EAST);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(input, BorderLayout.NORTH);
        panel.add(scroll(this.watchTree), BorderLayout.CENTER);
        return panel;
    }

    private static JScrollPane scroll(Component component) {
        JScrollPane scroll = new JScrollPane(component);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        return scroll;
    }

    private static void onEventThread(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }

    private static JComponent toolbarSeparator() {
        JPanel separator = new JPanel();
        separator.setBorder(DynamicMatteBorder.separatorRule(0, 1, 0, 0));
        separator.setPreferredSize(new Dimension(7, 20));
        separator.setOpaque(false);
        return separator;
    }

    private static JButton toolbarButton(Icon icon, String tooltip) {
        JButton button = new JButton(icon);
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.setMargin(new Insets(4, 6, 4, 6));
        return button;
    }

    private static JButton toolbarButton(Action action) {
        JButton button = new JButton(action);
        button.setHideActionText(true);
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        button.setFocusable(false);
        button.setMargin(new Insets(4, 6, 4, 6));
        return button;
    }

    private static JTree createValueTree(DefaultTreeModel model) {
        JTree tree = new JTree(model);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(UiMetrics.TREE_ROW_HEIGHT);
        tree.putClientProperty("JTree.wideSelection", true);
        tree.setCellRenderer(new DebugValueRenderer());
        return tree;
    }

    private void installExpansion(JTree tree, DefaultTreeModel model) {
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent event) {
                Object selected = event.getPath().getLastPathComponent();
                if (selected instanceof DefaultMutableTreeNode node) {
                    loadChildren(node, model);
                }
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent event) throws ExpandVetoException {
            }
        });
    }

    void applyStatus(DebuggerSessionController.Status status) {
        this.debuggerActions.applyStatus(status);
        this.statusLabel.setText(status.detail());
        this.statusLabel.setIcon(switch (status.phase()) {
            case RUNNING -> Icons.SUCCESS;
            case PAUSED, ATTACHING, DETACHING -> Icons.WARNING;
            case FAILED -> Icons.ERROR;
            case UNAVAILABLE, DETACHED -> Icons.INFORMATION;
        });

        boolean paused = status.phase() == DebuggerSessionController.Phase.PAUSED;
        this.expression.setEnabled(paused);
        this.evaluate.setEnabled(paused);
        this.addWatch.setEnabled(paused);

        if (!paused) {
            this.expressionCompletion.setCompletionProvider(null);
            this.viewRevision++;
            if (clearsPausedSnapshot(status.phase())) {
                clearPausedSnapshot();
            }
        }
    }

    private static boolean clearsPausedSnapshot(DebuggerSessionController.Phase phase) {
        return phase == DebuggerSessionController.Phase.UNAVAILABLE
                || phase == DebuggerSessionController.Phase.DETACHED
                || phase == DebuggerSessionController.Phase.FAILED;
    }

    private void clearPausedSnapshot() {
        this.currentFrame = null;
        this.currentVariables = List.of();
        this.currentPause = null;
        this.lastEvaluationExpression = "";
        this.frameLabel.setText("");
        this.frameModel.setFrames(List.of());
        showVariableStatus("Variables are available while paused");
        showUnavailableWatches();
    }

    void showPausedState(DebuggerSessionController.PausedState state) {
        this.viewRevision++;
        this.currentFrame = null;
        this.currentVariables = List.of();
        this.currentPause = Objects.requireNonNull(state, "state");
        this.statusLabel.setText(stopDescription(state.event()));
        this.statusLabel.setIcon(Icons.WARNING);
        this.frameModel.setFrames(state.frames());
        if (!state.frames().isEmpty()) {
            this.frames.setSelectedIndex(0);
            if (this.currentFrame == null) {
                selectFrame(0);
            }
        } else {
            this.frameLabel.setText("");
            showVariableStatus("No stack frames available");
            showUnavailableWatches();
        }
    }

    private static String stopDescription(DebugEngine.StoppedEvent event) {
        return switch (event.reason()) {
            case "breakpoint" -> "Paused on breakpoint";
            case "step" -> "Paused after stepping";
            case "pause" -> "Paused by user";
            case "exception" -> "Paused on exception";
            case "entry" -> "Paused at entry";
            default -> event.reason().isBlank() ? "Paused" : "Paused · " + event.reason();
        };
    }

    private void selectFrame(int row) {
        DebugEngine.StackFrame frame = this.frameModel.frame(row);
        if (frame == null) {
            return;
        }
        long revision = ++this.viewRevision;
        this.currentFrame = frame;
        this.currentVariables = List.of();
        this.expressionCompletion.setCompletionProvider((text, caret, explicit) ->
                this.controller.completions(text, caret, frame));
        this.frameLabel.setText(frameLocation(frame));

        this.frameNavigation.open(frame, false);
        refreshExpressions(frame, revision);

        DebuggerSessionController.PausedState pause = this.currentPause;
        if (pause != null && !pause.frames().isEmpty() && pause.frames().getFirst().equals(frame)) {
            showVariables(pause.variables());
            return;
        }

        showVariableStatus(LOADING);
        this.controller.variablesForFrame(frame).whenComplete((loaded, failure) -> SwingUtilities.invokeLater(() -> {
            if (!isCurrent(frame, revision)) {
                return;
            }
            if (failure != null) {
                showVariableStatus(failureMessage(failure, "Unable to load variables"));
                return;
            }
            showVariables(loaded);
        }));
    }

    private static String frameLocation(DebugEngine.StackFrame frame) {
        String owner = frame.binaryName().isBlank() ? "Unknown source" : frame.binaryName();
        return owner + (frame.line() > 0 ? ":" + frame.line() : "");
    }

    private void showVariables(List<DebugEngine.Variable> values) {
        this.currentVariables = List.copyOf(values);
        this.variableRoot.removeAllChildren();
        for (DebugEngine.Variable variable : values) {
            this.variableRoot.add(valueNode(DebugValue.from(variable)));
        }
        if (values.isEmpty()) {
            this.variableRoot.add(new DefaultMutableTreeNode(new StatusValue("No variables available", false)));
        }
        this.variableModel.reload();

    }

    private void showVariableStatus(String text) {
        this.variableRoot.removeAllChildren();
        this.variableRoot.add(new DefaultMutableTreeNode(new StatusValue(text, false)));
        this.variableModel.reload();
    }

    private void loadChildren(DefaultMutableTreeNode node, DefaultTreeModel model) {
        DebugValue value = debugValue(node.getUserObject());
        if (value == null || value.variablesReference() <= 0 || !hasPlaceholder(node)) {
            return;
        }
        long revision = this.viewRevision;
        node.removeAllChildren();
        node.add(new DefaultMutableTreeNode(new StatusValue(LOADING, false)));
        model.nodeStructureChanged(node);
        this.controller.variables(value.variablesReference()).whenComplete((children, failure) ->
                SwingUtilities.invokeLater(() -> {
                    if (revision != this.viewRevision) {
                        return;
                    }
                    node.removeAllChildren();
                    if (failure != null) {
                        node.add(new DefaultMutableTreeNode(new StatusValue(
                                failureMessage(failure, "Unable to load value"),
                                true
                        )));
                    } else {
                        for (DebugEngine.Variable child : children) {
                            node.add(valueNode(DebugValue.from(child)));
                        }
                    }
                    model.nodeStructureChanged(node);
                })
        );
    }

    private void evaluateExpression(boolean addAsWatch) {
        String requested = this.expression.getText().trim();
        if (requested.isEmpty() || this.currentFrame == null) {
            return;
        }
        if (addAsWatch) {
            if (this.watches.add(requested)) {
                GlobalConfig.getInstance().setDebuggerWatches(List.copyOf(this.watches));
            }
            this.expression.setText("");
        } else {
            this.lastEvaluationExpression = requested;
        }
        this.inspectorTabs.setSelectedComponent(this.watchesPanel);
        refreshExpressions(this.currentFrame, this.viewRevision);
    }

    private void removeSelectedExpression() {
        TreePath selection = this.watchTree.getSelectionPath();
        if (selection == null) {
            return;
        }
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) selection.getLastPathComponent();
        while (node.getParent() != this.watchRoot && node.getParent() instanceof DefaultMutableTreeNode parent) {
            node = parent;
        }
        int rootIndex = this.watchRoot.getIndex(node);
        if (!this.lastEvaluationExpression.isBlank() && rootIndex == 0) {
            this.lastEvaluationExpression = "";
        } else {
            String expression = expressionOf(node.getUserObject());
            if (this.watches.remove(expression)) {
                GlobalConfig.getInstance().setDebuggerWatches(List.copyOf(this.watches));
            }
        }
        refreshExpressions(this.currentFrame, this.viewRevision);
    }

    private void refreshExpressions(DebugEngine.StackFrame frame, long revision) {
        this.watchRoot.removeAllChildren();
        List<ExpressionRequest> requests = new ArrayList<>();
        if (!this.lastEvaluationExpression.isBlank()) {
            addExpressionRequest(requests, this.lastEvaluationExpression, false);
        }
        for (String expression : this.watches) {
            addExpressionRequest(requests, expression, true);
        }
        this.watchModel.reload();
        if (frame == null) {
            showUnavailableWatches();
            return;
        }
        for (ExpressionRequest request : requests) {
            this.controller.evaluate(request.expression(), frame).whenComplete((result, failure) ->
                    SwingUtilities.invokeLater(() -> {
                        if (!isCurrent(frame, revision) || request.node().getParent() != this.watchRoot) {
                            return;
                        }
                        request.node().removeAllChildren();
                        if (failure != null) {
                            request.node().setUserObject(new ExpressionStatus(
                                    request.expression(),
                                    failureMessage(failure, "Evaluation failed"),
                                    true
                            ));
                        } else {
                            DebugValue value = DebugValue.from(request.expression(), result);
                            request.node().setUserObject(new ExpressionValue(value));
                            addPlaceholder(request.node(), value);
                        }
                        this.watchModel.nodeStructureChanged(request.node());
                    })
            );
        }
    }

    private void addExpressionRequest(List<ExpressionRequest> requests, String expression, boolean watch) {
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(
                new ExpressionStatus(expression, watch ? "Watch · " + LOADING : LOADING, false)
        );
        this.watchRoot.add(node);
        requests.add(new ExpressionRequest(expression, node));
    }

    private void showUnavailableWatches() {
        this.watchRoot.removeAllChildren();
        if (!this.lastEvaluationExpression.isBlank()) {
            this.watchRoot.add(new DefaultMutableTreeNode(new ExpressionStatus(
                    this.lastEvaluationExpression,
                    "Not available while running",
                    false
            )));
        }
        for (String expression : this.watches) {
            this.watchRoot.add(new DefaultMutableTreeNode(new ExpressionStatus(
                    expression,
                    "Not available while running",
                    false
            )));
        }
        this.watchModel.reload();
    }

    private boolean isCurrent(DebugEngine.StackFrame frame, long revision) {
        return revision == this.viewRevision && Objects.equals(frame, this.currentFrame);
    }

    private static DefaultMutableTreeNode valueNode(DebugValue value) {
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(value);
        addPlaceholder(node, value);
        return node;
    }

    private static void addPlaceholder(DefaultMutableTreeNode node, DebugValue value) {
        if (value.variablesReference() > 0) {
            node.add(new DefaultMutableTreeNode(Placeholder.INSTANCE));
        }
    }

    private static boolean hasPlaceholder(DefaultMutableTreeNode node) {
        return node.getChildCount() == 1
                && ((DefaultMutableTreeNode) node.getFirstChild()).getUserObject() == Placeholder.INSTANCE;
    }

    private static DebugValue debugValue(Object value) {
        return switch (value) {
            case DebugValue debugValue -> debugValue;
            case ExpressionValue expressionValue -> expressionValue.value();
            default -> null;
        };
    }

    private static String expressionOf(Object value) {
        return switch (value) {
            case ExpressionValue expressionValue -> expressionValue.value().name();
            case ExpressionStatus status -> status.expression();
            default -> "";
        };
    }

    private static String failureMessage(Throwable failure, String fallback) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        String detail = current.getMessage();
        return detail == null || detail.isBlank() ? fallback : detail;
    }

    public void dispose() {
        if (this.disposed) {
            return;
        }
        this.disposed = true;
        this.expressionCompletion.close();
        this.controller.removeListener(this.listener);
    }

    void showWatchesForPreview() {
        this.inspectorTabs.setSelectedComponent(this.watchesPanel);
    }

    private record ExpressionRequest(String expression, DefaultMutableTreeNode node) {
    }

    private record ExpressionValue(DebugValue value) {
    }

    private record ExpressionStatus(String expression, String text, boolean error) {
    }

    private record DebugValue(
            String name,
            String value,
            String type,
            int variablesReference,
            int indexedVariables
    ) {
        private static DebugValue from(DebugEngine.Variable variable) {
            return new DebugValue(
                    variable.name(),
                    variable.value(),
                    variable.type(),
                    variable.variablesReference(),
                    variable.indexedVariables()
            );
        }

        private static DebugValue from(String expression, DebugEngine.EvaluationResult result) {
            return new DebugValue(
                    expression,
                    result.value(),
                    result.type(),
                    result.variablesReference(),
                    result.indexedVariables()
            );
        }
    }

    private record StatusValue(String text, boolean error) {
    }

    private enum Placeholder {
        INSTANCE
    }

    private static final class DebugValueRenderer extends DefaultTreeCellRenderer {
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
                    tree,
                    value,
                    selected,
                    expanded,
                    leaf,
                    row,
                    hasFocus
            );
            if (!(value instanceof DefaultMutableTreeNode node)) {
                return component;
            }
            DebugValue debugValue = debugValue(node.getUserObject());
            if (debugValue != null) {
                String visibleValue = withoutObjectIdentity(debugValue.value(), debugValue.type());
                String secondary = visibleValue.equals(debugValue.type()) ? "" : debugValue.type();
                this.valueLabel.configure(
                        new PrimarySecondaryText(
                                debugValue.name() + " = " + visibleValue,
                                secondary
                        ),
                        debugValue.indexedVariables() > 0 || debugValue.type().endsWith("[]")
                                ? Icons.ARRAY
                                : debugValue.variablesReference() > 0 ? Icons.VALUE : Icons.PRIMITIVE,
                        tree.getFont(),
                        selected,
                        getTextSelectionColor(),
                        getBackgroundSelectionColor()
                );
                this.valueLabel.setToolTipText(debugValue.type().isBlank()
                        ? debugValue.value()
                        : debugValue.type() + " · " + debugValue.value());
                return this.valueLabel;
            }
            switch (node.getUserObject()) {
                case ExpressionStatus status -> {
                    setText(status.expression() + " = " + status.text());
                    setToolTipText(status.error() ? status.text() : null);
                    setIcon(status.error() ? Icons.ERROR : Icons.JAVA_VARIABLE);
                }
                case StatusValue status -> {
                    setText(status.text());
                    setToolTipText(status.error() ? status.text() : null);
                    setIcon(status.error() ? Icons.ERROR : Icons.INFORMATION);
                }
                case Placeholder ignored -> {
                    setText(LOADING);
                    setIcon(Icons.JAVA_VARIABLE);
                }
                default -> {
                }
            }
            return component;
        }

        private static String withoutObjectIdentity(String value, String type) {
            if (value.isBlank() || type.isBlank()) {
                return value;
            }
            int separator = value.lastIndexOf('@');
            if (separator <= 0 || separator == value.length() - 1) {
                return value;
            }
            for (int index = separator + 1; index < value.length(); index++) {
                if (!Character.isDigit(value.charAt(index))) {
                    return value;
                }
            }
            String identityOwner = value.substring(0, separator);
            String arrayElementType = type;
            while (arrayElementType.endsWith("[]")) {
                arrayElementType = arrayElementType.substring(0, arrayElementType.length() - 2);
            }
            return identityOwner.equals(type) || identityOwner.startsWith(arrayElementType + "[")
                    ? identityOwner
                    : value;
        }
    }

    private static final class FrameListModel extends AbstractListModel<DebugEngine.StackFrame> {
        private List<DebugEngine.StackFrame> frames = List.of();

        void setFrames(List<DebugEngine.StackFrame> replacement) {
            List<DebugEngine.StackFrame> updated = List.copyOf(replacement);
            int previousSize = this.frames.size();
            int updatedSize = updated.size();
            this.frames = updated;

            int sharedSize = Math.min(previousSize, updatedSize);
            if (sharedSize > 0) {
                fireContentsChanged(this, 0, sharedSize - 1);
            }
            if (updatedSize > previousSize) {
                fireIntervalAdded(this, previousSize, updatedSize - 1);
            } else if (previousSize > updatedSize) {
                fireIntervalRemoved(this, updatedSize, previousSize - 1);
            }
        }

        DebugEngine.StackFrame frame(int row) {
            return row < 0 || row >= this.frames.size() ? null : this.frames.get(row);
        }

        @Override
        public int getSize() {
            return this.frames.size();
        }

        @Override
        public DebugEngine.StackFrame getElementAt(int index) {
            return this.frames.get(index);
        }
    }

    private static final class FrameRenderer implements ListCellRenderer<DebugEngine.StackFrame> {
        private final PrimarySecondaryLabel label = new PrimarySecondaryLabel();

        @Override
        public Component getListCellRendererComponent(
                JList<? extends DebugEngine.StackFrame> list,
                DebugEngine.StackFrame frame,
                int index,
                boolean selected,
                boolean hasFocus
        ) {
            String source = frame.binaryName().isBlank() ? sourceName(frame.sourceUri()) : frame.binaryName();
            if (frame.line() > 0) {
                source += ":" + frame.line();
            }
            this.label.configure(
                    new PrimarySecondaryText(frame.name(), source),
                    Icons.JAVA_METHOD,
                    list.getFont(),
                    selected,
                    list.getSelectionForeground(),
                    list.getSelectionBackground()
            );
            this.label.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
            this.label.setToolTipText(source);
            return this.label;
        }

        private static String sourceName(URI uri) {
            if (uri == null || uri.getPath() == null) {
                return "Unknown source";
            }
            String path = uri.getPath();
            int separator = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            return path.substring(separator + 1);
        }
    }

    private static final class MutedLabel extends JLabel {
        @Override
        protected void paintComponent(Graphics graphics) {
            setForeground(ThemeColors.mutedText());
            super.paintComponent(graphics);
        }
    }

    private static final class InitialProportionSplitPane extends JSplitPane {
        private final double initialProportion;
        private boolean initialized;

        private InitialProportionSplitPane(
                double initialProportion,
                Component leftComponent,
                Component rightComponent
        ) {
            super(JSplitPane.HORIZONTAL_SPLIT, leftComponent, rightComponent);
            this.initialProportion = initialProportion;
        }

        @Override
        public void doLayout() {
            if (!this.initialized && getWidth() > 0) {
                setDividerLocation(this.initialProportion);
                this.initialized = true;
            }
            super.doLayout();
        }
    }
}
