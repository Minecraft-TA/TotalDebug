package com.github.minecraft_ta.totalDebugCompanion.ui.views;

import com.github.minecraft_ta.totalDebugCompanion.GlobalConfig;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerSessionController;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerValueText;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.ExpressionCompletionSupport;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.JavaExpressionField;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.editors.DebuggerEditorPresentation;
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
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JToggleButton;
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
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.beans.PropertyChangeListener;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Reusable debugger workspace. Its host decides whether it is docked or placed in a window. */
public final class DebuggerPanel extends JPanel {
    private static final String LOADING = "Loading…";
    private static final String ADD_WATCH_ACTION = "debugger.addWatch";

    @FunctionalInterface
    public interface FrameNavigation {
        void open(DebugEngine.StackFrame frame, boolean activateEditor);
    }

    private final DebuggerSessionController controller;
    private final DebuggerActions debuggerActions;
    private final FrameNavigation frameNavigation;
    private final Consumer<NavigationTarget> navigation;
    private final JLabel statusLabel = new JLabel("Debugger is unavailable", Icons.INFORMATION, JLabel.LEADING);
    private final JLabel frameLabel = new MutedLabel();
    private final FrameListModel frameModel = new FrameListModel();
    private final JList<DebugEngine.StackFrame> frames = new JList<>(this.frameModel);
    private final DefaultMutableTreeNode variableRoot = new DefaultMutableTreeNode("Variables");
    private final DefaultTreeModel variableModel = new DefaultTreeModel(this.variableRoot);
    private final JTree variables = createValueTree(this.variableModel);
    private final JavaExpressionField expression = new JavaExpressionField();
    private final ExpressionCompletionSupport expressionCompletion = new ExpressionCompletionSupport(this.expression);
    private final JButton addWatch = toolbarButton(Icons.ADD_TO_WATCH, "Add Watch (Shift+Enter)");
    private final Set<String> watches = new LinkedHashSet<>(GlobalConfig.getInstance().debuggerWatches());
    private final JButton attach;
    private final JButton resume;
    private final JButton stepOver;
    private final JButton stepInto;
    private final JButton stepOut;
    private final JButton detach;
    private final JButton viewBreakpoints;
    private final JToggleButton muteBreakpoints;
    private final DebuggerSessionController.Listener listener = new DebuggerSessionController.Listener() {
        @Override
        public void statusChanged(DebuggerSessionController.Status status) {
            onEventThread(() -> applyStatus(status));
        }

        @Override
        public void paused(DebuggerSessionController.PausedState state) {
            onEventThread(() -> showPausedState(state));
        }

        @Override
        public void breakpointsMutedChanged(boolean muted) {
            onEventThread(() -> DebuggerPanel.this.muteBreakpoints.setSelected(muted));
        }
    };
    private final PropertyChangeListener previewSettingsListener = event -> onEventThread(() -> {
        if (this.currentFrame != null) {
            showVariables(this.currentVariables);
        }
    });

    private long viewRevision;
    private DebugEngine.StackFrame currentFrame;
    private List<DebugEngine.Variable> currentVariables = List.of();
    private DebuggerSessionController.PausedState currentPause;
    private String lastEvaluationExpression = "";
    private String variableStatus = "Variables are available while paused";
    private boolean disposed;

    DebuggerPanel(
            DebuggerSessionController controller,
            DebuggerActions debuggerActions,
            FrameNavigation frameNavigation
    ) {
        this(controller, debuggerActions, frameNavigation, () -> {
        }, target -> {
        });
    }

    DebuggerPanel(
            DebuggerSessionController controller,
            DebuggerActions debuggerActions,
            FrameNavigation frameNavigation,
            Runnable showBreakpoints
    ) {
        this(controller, debuggerActions, frameNavigation, showBreakpoints, target -> {
        });
    }

    DebuggerPanel(
            DebuggerSessionController controller,
            DebuggerActions debuggerActions,
            FrameNavigation frameNavigation,
            Runnable showBreakpoints,
            Consumer<NavigationTarget> navigation
    ) {
        super(new BorderLayout());
        this.controller = Objects.requireNonNull(controller, "controller");
        this.debuggerActions = Objects.requireNonNull(debuggerActions, "debuggerActions");
        this.frameNavigation = Objects.requireNonNull(frameNavigation, "frameNavigation");
        this.navigation = Objects.requireNonNull(navigation, "navigation");
        this.attach = toolbarButton(debuggerActions.attach());
        this.resume = toolbarButton(debuggerActions.resume());
        this.stepOver = toolbarButton(debuggerActions.stepOver());
        this.stepInto = toolbarButton(debuggerActions.stepInto());
        this.stepOut = toolbarButton(debuggerActions.stepOut());
        this.detach = toolbarButton(debuggerActions.detach());
        this.viewBreakpoints = toolbarButton(Icons.VIEW_BREAKPOINTS, "View breakpoints");
        this.viewBreakpoints.addActionListener(event -> showBreakpoints.run());
        this.muteBreakpoints = toolbarToggle(Icons.MUTE_BREAKPOINTS, "Mute breakpoints");
        this.muteBreakpoints.setSelected(controller.breakpointsMuted());
        this.muteBreakpoints.addActionListener(event ->
                controller.setBreakpointsMuted(this.muteBreakpoints.isSelected()));

        setBorder(BorderFactory.createEmptyBorder());
        add(createToolbar(), BorderLayout.NORTH);

        configureFrames();
        installExpansion(this.variables, this.variableModel);
        installVariableContextMenu();
        JComponent inspector = createInspectorPanel();

        JSplitPane split = new InitialProportionSplitPane(
                0.42,
                createFramesPanel(),
                inspector
        );
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setDividerSize(1);
        split.setResizeWeight(0.42);
        split.setContinuousLayout(true);
        add(split, BorderLayout.CENTER);

        this.controller.addListener(this.listener);
        GlobalConfig.getInstance().addAutomaticDebuggerPreviewsListener(this.previewSettingsListener);
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
        actions.add(toolbarSeparator());
        actions.add(this.viewBreakpoints);
        actions.add(this.muteBreakpoints);

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

    private JPanel createInspectorPanel() {
        this.expression.setPlaceholder("Evaluate Expression (Enter)");
        this.expression.setToolTipText(
                "Evaluate Expression (Enter); Add Watch (Shift+Enter)"
        );
        this.expression.addActionListener(event -> evaluateExpression(false));
        this.expression.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK),
                ADD_WATCH_ACTION
        );
        this.expression.getActionMap().put(ADD_WATCH_ACTION, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                evaluateExpression(true);
            }
        });
        this.addWatch.addActionListener(event -> evaluateExpression(true));
        this.variables.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "removeWatch");
        this.variables.getActionMap().put("removeWatch", new AbstractAction() {
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
        input.add(this.expression.component(), BorderLayout.CENTER);
        input.add(this.addWatch, BorderLayout.EAST);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(input, BorderLayout.NORTH);
        panel.add(scroll(this.variables), BorderLayout.CENTER);
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

    private static JToggleButton toolbarToggle(Icon icon, String tooltip) {
        JToggleButton button = new JToggleButton(icon);
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        button.setToolTipText(tooltip);
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

    private void installVariableContextMenu() {
        this.variables.addMouseListener(new MouseAdapter() {
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
                TreePath path = variables.getPathForLocation(event.getX(), event.getY());
                if (path == null) {
                    return;
                }
                variables.setSelectionPath(path);
                JPopupMenu menu = createVariableContextMenu(path);
                if (menu.getComponentCount() > 0) {
                    menu.show(variables, event.getX(), event.getY());
                }
            }
        });
    }

    JPopupMenu createVariableContextMenu(TreePath path) {
        JPopupMenu menu = new JPopupMenu();
        if (path == null || !(path.getLastPathComponent() instanceof DefaultMutableTreeNode selected)) {
            return menu;
        }

        Object selectedValue = selected.getUserObject();
        DebugValue value = debugValue(selectedValue);
        DebugEngine.Variable variable = value == null ? null : value.sourceVariable();
        DebugEngine.StackFrame frame = this.currentFrame;
        DebugEngine.Variable parent = parentVariable(selected);

        if (variable != null && frame != null && supportsDeclarationNavigation(variable)) {
            menu.add(menuItem("Jump to Source", Icons.JAVA_VARIABLE,
                    () -> navigateToDeclaration(frame, variable, parent)));
        }
        if (value != null && frame != null) {
            Optional<NavigationTarget.RuntimeClass> typeTarget = variable == null
                    ? DebuggerVariableNavigation.typeTarget(value.type())
                    : DebuggerVariableNavigation.typeTarget(frame, variable);
            typeTarget.ifPresent(target ->
                    menu.add(menuItem("Jump to Type Source", Icons.JAVA_CLASS,
                            () -> this.navigation.accept(target))));
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

        String expression = expressionOf(selectedValue);
        if (!expression.isBlank()) {
            menu.addSeparator();
            if (isWatch(selectedValue)) {
                menu.add(menuItem("Remove Watch", Icons.DELETE, () -> removeExpression(expression, true)));
            } else {
                menu.add(menuItem("Add to Watches", Icons.ADD_TO_WATCH, () -> addWatch(expression)));
            }
        }
        return menu;
    }

    private void navigateToDeclaration(
            DebugEngine.StackFrame frame,
            DebugEngine.Variable variable,
            DebugEngine.Variable parent
    ) {
        DebugEngine.Source source = frame.sourceUri() == null ? null : this.controller.source(frame.sourceUri());
        CompletableFuture.supplyAsync(() ->
                DebuggerVariableNavigation.declarationTarget(source, frame, variable, parent)
        ).whenComplete((target, failure) -> SwingUtilities.invokeLater(() -> {
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
        DebugEngine.StackFrame frame = this.currentFrame;
        if (frame == null) {
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
        this.controller.setVariable(variable, replacement.toString(), frame)
                .whenComplete((variables, failure) -> SwingUtilities.invokeLater(() -> {
                    if (failure != null) {
                        showOperationFailure("Unable to Set Value", failure);
                    } else if (Objects.equals(frame, this.currentFrame)) {
                        showVariables(variables);
                    }
                }));
    }

    private void showOperationFailure(String title, Throwable failure) {
        JOptionPane.showMessageDialog(
                this,
                failureMessage(failure, title),
                title,
                JOptionPane.ERROR_MESSAGE
        );
    }

    private static JMenuItem menuItem(String text, Icon icon, Runnable action) {
        JMenuItem item = new JMenuItem(text, icon);
        item.addActionListener(event -> action.run());
        return item;
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

    private static void copy(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
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
        this.addWatch.setEnabled(paused);

        if (!paused) {
            this.expressionCompletion.setCompletionProvider(null);
            this.expression.setSemanticTokenProvider(null);
            this.viewRevision++;
            DebuggerEditorPresentation.clear();
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
        this.expression.setSemanticTokenProvider(text -> this.controller.expressionTokens(text, frame));
        this.frameLabel.setText(frameLocation(frame));

        this.frameNavigation.open(frame, false);
        DebuggerEditorPresentation.select(frame, List.of());
        rebuildInspector();

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
        this.variableStatus = null;
        if (this.currentFrame != null) {
            DebuggerEditorPresentation.select(
                    this.currentFrame,
                    values,
                    GlobalConfig.getInstance().automaticDebuggerPreviews()
            );
        }
        rebuildInspector();
    }

    void focusVariable(DebugEngine.StackFrame frame, DebugEngine.Variable variable) {
        if (!Objects.equals(frame, this.currentFrame)) {
            return;
        }
        for (int index = 0; index < this.variableRoot.getChildCount(); index++) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) this.variableRoot.getChildAt(index);
            DebugValue value = debugValue(node.getUserObject());
            if (value == null || !sameVariable(value, variable)) {
                continue;
            }
            TreePath path = new TreePath(node.getPath());
            this.variables.setSelectionPath(path);
            this.variables.scrollPathToVisible(path);
            this.variables.requestFocusInWindow();
            return;
        }
    }

    private static boolean sameVariable(DebugValue displayed, DebugEngine.Variable requested) {
        if (!requested.evaluateName().isBlank() && !displayed.evaluateName().isBlank()) {
            return requested.evaluateName().equals(displayed.evaluateName());
        }
        return requested.name().equals(displayed.name());
    }

    private void showVariableStatus(String text) {
        this.variableStatus = text;
        rebuildInspector();
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
                        List<DefaultMutableTreeNode> childNodes = new ArrayList<>();
                        for (DebugEngine.Variable child : children) {
                            DefaultMutableTreeNode childNode = valueNode(DebugValue.from(child));
                            node.add(childNode);
                            childNodes.add(childNode);
                        }
                        model.nodeStructureChanged(node);
                        for (DefaultMutableTreeNode childNode : childNodes) {
                            requestPreview(childNode, model, revision);
                        }
                        return;
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
            addWatch(requested);
            this.expression.setText("");
        } else {
            this.lastEvaluationExpression = requested;
            rebuildInspector();
        }
    }

    private void addWatch(String expression) {
        if (this.watches.add(expression)) {
            GlobalConfig.getInstance().setDebuggerWatches(List.copyOf(this.watches));
        }
        rebuildInspector();
    }

    private void removeSelectedExpression() {
        TreePath selection = this.variables.getSelectionPath();
        if (selection == null) {
            return;
        }
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) selection.getLastPathComponent();
        while (node.getParent() != this.variableRoot && node.getParent() instanceof DefaultMutableTreeNode parent) {
            node = parent;
        }
        if (!(node.getUserObject() instanceof ExpressionValue)
                && !(node.getUserObject() instanceof ExpressionStatus)) {
            return;
        }
        removeExpression(expressionOf(node.getUserObject()), isWatch(node.getUserObject()));
    }

    private void removeExpression(String expression, boolean watch) {
        if (watch) {
            if (this.watches.remove(expression)) {
                GlobalConfig.getInstance().setDebuggerWatches(List.copyOf(this.watches));
            }
        } else if (expression.equals(this.lastEvaluationExpression)) {
            this.lastEvaluationExpression = "";
        }
        rebuildInspector();
    }

    private void rebuildInspector() {
        this.variableRoot.removeAllChildren();
        List<ExpressionRequest> expressionRequests = new ArrayList<>();
        if (!this.lastEvaluationExpression.isBlank()) {
            addExpressionNode(expressionRequests, this.lastEvaluationExpression, false);
        }
        for (String watch : this.watches) {
            addExpressionNode(expressionRequests, watch, true);
        }

        List<DefaultMutableTreeNode> variableNodes = new ArrayList<>();
        if (this.variableStatus != null) {
            this.variableRoot.add(new DefaultMutableTreeNode(new StatusValue(this.variableStatus, false)));
        } else {
            List<DebugEngine.Variable> displayedValues = new ArrayList<>(this.currentVariables);
            displayedValues.sort(Comparator.comparingInt(variable ->
                    variable.kind() == DebugEngine.VariableKind.THIS ? 0 : 1));
            for (DebugEngine.Variable variable : displayedValues) {
                DefaultMutableTreeNode node = valueNode(DebugValue.from(variable));
                this.variableRoot.add(node);
                variableNodes.add(node);
            }
            if (displayedValues.isEmpty()) {
                this.variableRoot.add(new DefaultMutableTreeNode(new StatusValue(
                        "No variables available",
                        false
                )));
            }
        }
        this.variableModel.reload();

        long revision = this.viewRevision;
        for (DefaultMutableTreeNode node : variableNodes) {
            requestPreview(node, this.variableModel, revision);
        }
        DebugEngine.StackFrame frame = this.currentFrame;
        if (frame == null) {
            return;
        }
        for (ExpressionRequest request : expressionRequests) {
            this.controller.evaluate(request.expression(), frame).whenComplete((result, failure) ->
                    SwingUtilities.invokeLater(() -> {
                        if (!isCurrent(frame, revision) || request.node().getParent() != this.variableRoot) {
                            return;
                        }
                        request.node().removeAllChildren();
                        if (failure != null) {
                            request.node().setUserObject(new ExpressionStatus(
                                    request.expression(),
                                    failureMessage(failure, "Evaluation failed"),
                                    true,
                                    request.watch()
                            ));
                        } else {
                            DebugValue value = DebugValue.from(request.expression(), result);
                            request.node().setUserObject(new ExpressionValue(value, request.watch()));
                            addPlaceholder(request.node(), value);
                        }
                        this.variableModel.nodeStructureChanged(request.node());
                        if (failure == null) {
                            requestPreview(request.node(), this.variableModel, revision);
                        }
                    })
            );
        }
    }

    private void addExpressionNode(List<ExpressionRequest> requests, String expression, boolean watch) {
        String status = this.currentFrame == null
                ? "Not available while running"
                : watch ? "Watch  " + LOADING : LOADING;
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(
                new ExpressionStatus(expression, status, false, watch)
        );
        this.variableRoot.add(node);
        requests.add(new ExpressionRequest(expression, node, watch));
    }

    private boolean isCurrent(DebugEngine.StackFrame frame, long revision) {
        return revision == this.viewRevision && Objects.equals(frame, this.currentFrame);
    }

    private void requestPreview(DefaultMutableTreeNode node, DefaultTreeModel model, long revision) {
        DebugValue value = debugValue(node.getUserObject());
        if (value == null || value.variablesReference() <= 0
                || !GlobalConfig.getInstance().automaticDebuggerPreviews()) {
            return;
        }
        this.controller.preview(value.variablesReference()).whenComplete((preview, failure) ->
                SwingUtilities.invokeLater(() -> {
                    if (revision != this.viewRevision || node.getParent() == null
                            || !Objects.equals(debugValue(node.getUserObject()), value)) {
                        return;
                    }
                    DebugEngine.ValuePreview resolvedPreview = failure == null && preview != null
                            ? preview
                            : DebugEngine.ValuePreview.NONE;
                    DebugEngine.StackFrame frame = this.currentFrame;
                    if (frame != null) {
                        DebuggerEditorPresentation.updatePreview(
                                frame,
                                value.variablesReference(),
                                resolvedPreview
                        );
                    }
                    if (!resolvedPreview.available()) {
                        return;
                    }
                    DebugValue replacement = value.withPreview(resolvedPreview);
                    node.setUserObject(node.getUserObject() instanceof ExpressionValue expressionValue
                            ? new ExpressionValue(replacement, expressionValue.watch())
                            : replacement);
                    model.nodeChanged(node);
                })
        );
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

    private static boolean isWatch(Object value) {
        return switch (value) {
            case ExpressionValue expressionValue -> expressionValue.watch();
            case ExpressionStatus status -> status.watch();
            default -> false;
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
        DebuggerEditorPresentation.clear();
        this.expressionCompletion.close();
        this.controller.removeListener(this.listener);
        GlobalConfig.getInstance().removeAutomaticDebuggerPreviewsListener(this.previewSettingsListener);
    }

    private record ExpressionRequest(String expression, DefaultMutableTreeNode node, boolean watch) {
    }

    private record ExpressionValue(DebugValue value, boolean watch) {
    }

    private record ExpressionStatus(String expression, String text, boolean error, boolean watch) {
    }

    private record DebugValue(
            String name,
            String evaluateName,
            String value,
            String type,
            DebugEngine.VariableKind kind,
            int variablesReference,
            int indexedVariables,
            DebugEngine.ValuePreview preview,
            DebugEngine.Variable sourceVariable
    ) {
        private static DebugValue from(DebugEngine.Variable variable) {
            return new DebugValue(
                    variable.name(),
                    variable.evaluateName(),
                    variable.value(),
                    variable.type(),
                    variable.kind(),
                    variable.variablesReference(),
                    variable.indexedVariables(),
                    DebugEngine.ValuePreview.NONE,
                    variable
            );
        }

        private static DebugValue from(String expression, DebugEngine.EvaluationResult result) {
            return new DebugValue(
                    expression,
                    expression,
                    result.value(),
                    result.type(),
                    DebugEngine.VariableKind.EXPRESSION,
                    result.variablesReference(),
                    result.indexedVariables(),
                    DebugEngine.ValuePreview.NONE,
                    null
            );
        }

        private DebugValue withPreview(DebugEngine.ValuePreview replacement) {
            return new DebugValue(this.name, this.evaluateName, this.value, this.type, this.kind,
                    this.variablesReference, this.indexedVariables, replacement, this.sourceVariable);
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
                Icon rowIcon = switch (node.getUserObject()) {
                    case ExpressionValue expressionValue -> expressionValue.watch()
                            ? Icons.WATCH
                            : Icons.EVALUATE_EXPRESSION;
                    default -> icon(debugValue);
                };
                String visibleValue = DebuggerValueText.visibleValue(debugValue.value(), debugValue.type());
                String simpleType = DebuggerValueText.simpleTypeName(debugValue.type());
                String secondary = debugValue.preview().available()
                        ? debugValue.preview().summary()
                        : visibleValue.equals(simpleType) ? "" : simpleType;
                this.valueLabel.configure(
                        new PrimarySecondaryText(
                                debugValue.name() + " = " + visibleValue,
                                secondary
                        ),
                        rowIcon,
                        tree.getFont(),
                        selected,
                        getTextSelectionColor(),
                        getBackgroundSelectionColor()
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
                    setIcon(status.error()
                            ? Icons.ERROR
                            : status.watch() ? Icons.WATCH : Icons.EVALUATE_EXPRESSION);
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

        private static javax.swing.Icon icon(DebugValue value) {
            return switch (value.kind()) {
                case UNKNOWN -> Icons.JAVA_VARIABLE;
                case PARAMETER -> Icons.JAVA_PARAMETER;
                case FIELD -> Icons.FIELD;
                case LOCAL -> Icons.JAVA_VARIABLE;
                case THIS -> Icons.VALUE;
                case ARRAY_ELEMENT -> value.variablesReference() > 0 ? Icons.VALUE : Icons.PRIMITIVE;
                case RETURN_VALUE -> Icons.JAVA_METHOD;
                case EXPRESSION -> value.indexedVariables() > 0 || value.type().endsWith("[]")
                        ? Icons.ARRAY
                        : value.variablesReference() > 0 ? Icons.VALUE : Icons.PRIMITIVE;
            };
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
