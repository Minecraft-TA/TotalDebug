package com.github.minecraft_ta.totalDebugCompanion.ui.views;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerSessionController;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.ExpandVetoException;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class DebuggerWindow extends JDialog {
    private static final String LOADING = "Loading…";

    private final DebuggerSessionController controller;
    private final JLabel statusLabel = new JLabel("Debugger is unavailable");
    private final FrameTableModel frameModel = new FrameTableModel();
    private final JTable frames = new JTable(this.frameModel);
    private final DefaultMutableTreeNode variableRoot = new DefaultMutableTreeNode("Variables");
    private final DefaultTreeModel variableModel = new DefaultTreeModel(this.variableRoot);
    private final JTree variables = createValueTree(this.variableModel);
    private final DefaultMutableTreeNode watchRoot = new DefaultMutableTreeNode("Watches");
    private final DefaultTreeModel watchModel = new DefaultTreeModel(this.watchRoot);
    private final JTree watchTree = createValueTree(this.watchModel);
    private final JTextField watchExpression = new JTextField();
    private final JButton addWatch = new JButton("Add Watch");
    private final JButton removeWatch = new JButton("Remove");
    private final Set<String> watches = new LinkedHashSet<>();
    private final JButton resume = new JButton("Continue", Icons.DEBUG_RESUME);
    private final JButton stepOver = new JButton("Step Over", Icons.DEBUG_STEP_OVER);
    private final JButton stepInto = new JButton("Step Into", Icons.DEBUG_STEP_INTO);
    private final JButton stepOut = new JButton("Step Out", Icons.DEBUG_STEP_OUT);
    private final JButton detach = new JButton("Detach", Icons.DEBUG_DETACH);
    private long viewRevision;
    private DebugEngine.StackFrame currentFrame;
    private final DebuggerSessionController.Listener listener = new DebuggerSessionController.Listener() {
        @Override
        public void statusChanged(DebuggerSessionController.Status status) {
            SwingUtilities.invokeLater(() -> applyStatus(status));
        }

        @Override
        public void paused(DebuggerSessionController.PausedState state) {
            SwingUtilities.invokeLater(() -> showPausedState(state));
        }
    };

    public DebuggerWindow(Window owner, DebuggerSessionController controller) {
        super(owner, "Minecraft Debugger", ModalityType.MODELESS);
        this.controller = controller;

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        content.add(createToolbar(), BorderLayout.NORTH);

        this.frames.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        this.frames.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                selectFrame(this.frames.getSelectedRow());
            }
        });
        installExpansion(this.variables, this.variableModel);
        installExpansion(this.watchTree, this.watchModel);

        JScrollPane frameScroll = new JScrollPane(this.frames);
        frameScroll.setBorder(BorderFactory.createTitledBorder("Frames"));
        JScrollPane variableScroll = new JScrollPane(this.variables);
        variableScroll.setBorder(BorderFactory.createTitledBorder("Variables"));

        JSplitPane values = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT,
                variableScroll,
                createWatchesPanel()
        );
        values.setResizeWeight(0.62);
        values.setContinuousLayout(true);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, frameScroll, values);
        split.setResizeWeight(0.42);
        split.setContinuousLayout(true);
        content.add(split, BorderLayout.CENTER);
        content.add(this.statusLabel, BorderLayout.SOUTH);

        setContentPane(content);
        setDefaultCloseOperation(HIDE_ON_CLOSE);
        setPreferredSize(new Dimension(980, 560));
        pack();
        values.setDividerLocation(0.62);
        split.setDividerLocation(0.42);
        setLocationRelativeTo(owner);

        this.controller.addListener(this.listener);
    }

    public void showWindow() {
        if (!isVisible()) {
            setLocationRelativeTo(getOwner());
            setVisible(true);
        }
        toFront();
    }

    private JPanel createToolbar() {
        this.resume.addActionListener(event -> this.controller.resume());
        this.stepOver.addActionListener(event -> this.controller.stepOver());
        this.stepInto.addActionListener(event -> this.controller.stepInto());
        this.stepOut.addActionListener(event -> this.controller.stepOut());
        this.detach.addActionListener(event -> this.controller.detach());

        JPanel toolbar = new JPanel();
        toolbar.setLayout(new BoxLayout(toolbar, BoxLayout.LINE_AXIS));
        toolbar.add(this.resume);
        toolbar.add(Box.createHorizontalStrut(6));
        toolbar.add(this.stepOver);
        toolbar.add(Box.createHorizontalStrut(4));
        toolbar.add(this.stepInto);
        toolbar.add(Box.createHorizontalStrut(4));
        toolbar.add(this.stepOut);
        toolbar.add(Box.createHorizontalGlue());
        toolbar.add(this.detach);
        return toolbar;
    }

    private JPanel createWatchesPanel() {
        this.watchExpression.putClientProperty("JTextField.placeholderText", "Expression");
        this.watchExpression.addActionListener(event -> addWatch());
        this.addWatch.addActionListener(event -> addWatch());
        this.removeWatch.addActionListener(event -> removeSelectedWatch());
        this.watchTree.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "removeWatch");
        this.watchTree.getActionMap().put("removeWatch", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                removeSelectedWatch();
            }
        });

        JPanel input = new JPanel(new BorderLayout(4, 0));
        input.add(this.watchExpression, BorderLayout.CENTER);
        JPanel actions = new JPanel();
        actions.setLayout(new BoxLayout(actions, BoxLayout.LINE_AXIS));
        actions.add(this.addWatch);
        actions.add(Box.createHorizontalStrut(4));
        actions.add(this.removeWatch);
        input.add(actions, BorderLayout.EAST);

        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(BorderFactory.createTitledBorder("Watches"));
        panel.add(input, BorderLayout.NORTH);
        panel.add(new JScrollPane(this.watchTree), BorderLayout.CENTER);
        return panel;
    }

    private static JTree createValueTree(DefaultTreeModel model) {
        JTree tree = new JTree(model);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(0);
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

    private void applyStatus(DebuggerSessionController.Status status) {
        this.statusLabel.setText(status.detail());
        boolean paused = status.phase() == DebuggerSessionController.Phase.PAUSED;
        this.resume.setEnabled(paused);
        this.stepOver.setEnabled(paused);
        this.stepInto.setEnabled(paused);
        this.stepOut.setEnabled(paused);
        this.detach.setEnabled(switch (status.phase()) {
            case ATTACHING, RUNNING, PAUSED, DETACHING -> true;
            default -> false;
        });
        if (!paused) {
            this.viewRevision++;
            this.currentFrame = null;
            this.frameModel.setFrames(List.of());
            showVariables(List.of());
            showUnavailableWatches();
        }
    }

    private void showPausedState(DebuggerSessionController.PausedState state) {
        this.viewRevision++;
        this.currentFrame = null;
        this.frameModel.setFrames(state.frames());
        if (!state.frames().isEmpty()) {
            this.frames.setRowSelectionInterval(0, 0);
        } else {
            showVariables(List.of());
            showUnavailableWatches();
        }
        showWindow();
    }

    private void selectFrame(int row) {
        DebugEngine.StackFrame frame = this.frameModel.frame(row);
        if (frame == null) {
            return;
        }
        long revision = ++this.viewRevision;
        this.currentFrame = frame;
        this.statusLabel.setText(frame.name() + ":" + frame.line());

        // Frame navigation is independent of locals. A slow variables request must never delay or redirect the jump.
        CompanionApp.openDebugFrame(frame);
        refreshWatches(frame, revision);

        DebuggerSessionController.PausedState paused = this.controller.pausedState();
        if (paused != null && !paused.frames().isEmpty() && paused.frames().getFirst().equals(frame)) {
            showVariables(paused.variables());
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

    private void showVariables(List<DebugEngine.Variable> values) {
        this.variableRoot.removeAllChildren();
        for (DebugEngine.Variable variable : values) {
            this.variableRoot.add(valueNode(DebugValue.from(variable)));
        }
        this.variableModel.reload();
    }

    private void showVariableStatus(String text) {
        this.variableRoot.removeAllChildren();
        this.variableRoot.add(new DefaultMutableTreeNode(new StatusValue(text, false)));
        this.variableModel.reload();
    }

    private void loadChildren(DefaultMutableTreeNode node, DefaultTreeModel model) {
        if (!(node.getUserObject() instanceof DebugValue value)
                || value.variablesReference() <= 0
                || !hasPlaceholder(node)) {
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

    private void addWatch() {
        String expression = this.watchExpression.getText().trim();
        if (expression.isEmpty()) {
            return;
        }
        this.watches.add(expression);
        this.watchExpression.setText("");
        refreshWatches(this.currentFrame, this.viewRevision);
    }

    private void removeSelectedWatch() {
        TreePath selection = this.watchTree.getSelectionPath();
        if (selection == null) {
            return;
        }
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) selection.getLastPathComponent();
        while (node.getParent() != this.watchRoot && node.getParent() instanceof DefaultMutableTreeNode parent) {
            node = parent;
        }
        String expression = switch (node.getUserObject()) {
            case DebugValue value -> value.name();
            case StatusValue status -> status.context();
            default -> "";
        };
        if (this.watches.remove(expression)) {
            refreshWatches(this.currentFrame, this.viewRevision);
        }
    }

    private void refreshWatches(DebugEngine.StackFrame frame, long revision) {
        this.watchRoot.removeAllChildren();
        List<WatchRequest> requests = new ArrayList<>();
        for (String expression : this.watches) {
            DefaultMutableTreeNode node = new DefaultMutableTreeNode(new StatusValue(expression, LOADING, false));
            this.watchRoot.add(node);
            requests.add(new WatchRequest(expression, node));
        }
        this.watchModel.reload();
        if (frame == null) {
            showUnavailableWatches();
            return;
        }
        for (WatchRequest request : requests) {
            this.controller.evaluate(request.expression(), frame).whenComplete((result, failure) ->
                    SwingUtilities.invokeLater(() -> {
                        if (!isCurrent(frame, revision) || request.node().getParent() != this.watchRoot) {
                            return;
                        }
                        request.node().removeAllChildren();
                        if (failure != null) {
                            request.node().setUserObject(new StatusValue(
                                    request.expression(),
                                    failureMessage(failure, "Evaluation failed"),
                                    true
                            ));
                        } else {
                            DebugValue value = DebugValue.from(request.expression(), result);
                            request.node().setUserObject(value);
                            addPlaceholder(request.node(), value);
                        }
                        this.watchModel.nodeStructureChanged(request.node());
                    })
            );
        }
    }

    private void showUnavailableWatches() {
        this.watchRoot.removeAllChildren();
        for (String expression : this.watches) {
            this.watchRoot.add(new DefaultMutableTreeNode(new StatusValue(
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

    @Override
    public void dispose() {
        this.controller.removeListener(this.listener);
        super.dispose();
    }

    private record WatchRequest(String expression, DefaultMutableTreeNode node) {
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

    private record StatusValue(String context, String text, boolean error) {
        private StatusValue(String text, boolean error) {
            this("", text, error);
        }
    }

    private enum Placeholder {
        INSTANCE
    }

    private static final class DebugValueRenderer extends DefaultTreeCellRenderer {
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
            switch (node.getUserObject()) {
                case DebugValue debugValue -> {
                    setText(debugValue.name() + " = " + debugValue.value()
                            + (debugValue.type().isBlank() ? "" : "    " + debugValue.type()));
                    setToolTipText(debugValue.type().isBlank() ? null : debugValue.type());
                    setIcon(debugValue.indexedVariables() > 0 || debugValue.type().endsWith("[]")
                            ? Icons.ARRAY
                            : debugValue.variablesReference() > 0 ? Icons.VALUE : Icons.PRIMITIVE);
                }
                case StatusValue status -> {
                    setText(status.context().isBlank()
                            ? status.text()
                            : status.context() + " = " + status.text());
                    setToolTipText(status.error() ? status.text() : null);
                    setIcon(status.error() ? Icons.ERROR : Icons.JAVA_VARIABLE);
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
    }

    private static final class FrameTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"Frame", "Source", "Line"};
        private List<DebugEngine.StackFrame> frames = List.of();

        void setFrames(List<DebugEngine.StackFrame> replacement) {
            this.frames = List.copyOf(replacement);
            fireTableDataChanged();
        }

        DebugEngine.StackFrame frame(int row) {
            return row < 0 || row >= this.frames.size() ? null : this.frames.get(row);
        }

        @Override
        public int getRowCount() {
            return this.frames.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            DebugEngine.StackFrame frame = this.frames.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> frame.name();
                case 1 -> sourceName(frame);
                case 2 -> frame.line();
                default -> "";
            };
        }

        private static String sourceName(DebugEngine.StackFrame frame) {
            if (!frame.binaryName().isBlank()) {
                int separator = frame.binaryName().lastIndexOf('.');
                return frame.binaryName().substring(separator + 1).replace('$', '.') + ".java";
            }
            URI uri = frame.sourceUri();
            if (uri == null || uri.getPath() == null) {
                return "";
            }
            String path = uri.getPath();
            int separator = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            return path.substring(separator + 1);
        }
    }
}
