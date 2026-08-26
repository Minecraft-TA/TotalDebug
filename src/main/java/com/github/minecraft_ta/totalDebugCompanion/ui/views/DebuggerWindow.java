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
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Window;
import java.util.List;

public final class DebuggerWindow extends JDialog {
    private final DebuggerSessionController controller;
    private final JLabel statusLabel = new JLabel("Debugger is unavailable");
    private final FrameTableModel frameModel = new FrameTableModel();
    private final VariableTableModel variableModel = new VariableTableModel();
    private final JTable frames = new JTable(this.frameModel);
    private final JTable variables = new JTable(this.variableModel);
    private final JButton resume = new JButton("Continue", Icons.DEBUG_RESUME);
    private final JButton stepOver = new JButton("Step Over", Icons.DEBUG_STEP_OVER);
    private final JButton stepInto = new JButton("Step Into", Icons.DEBUG_STEP_INTO);
    private final JButton stepOut = new JButton("Step Out", Icons.DEBUG_STEP_OUT);
    private final JButton detach = new JButton("Detach", Icons.DEBUG_DETACH);
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
        this.variables.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        JScrollPane frameScroll = new JScrollPane(this.frames);
        frameScroll.setBorder(BorderFactory.createTitledBorder("Call stack"));
        JScrollPane variableScroll = new JScrollPane(this.variables);
        variableScroll.setBorder(BorderFactory.createTitledBorder("Local variables"));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, frameScroll, variableScroll);
        split.setResizeWeight(0.48);
        split.setContinuousLayout(true);
        content.add(split, BorderLayout.CENTER);
        content.add(this.statusLabel, BorderLayout.SOUTH);

        setContentPane(content);
        setDefaultCloseOperation(HIDE_ON_CLOSE);
        setPreferredSize(new Dimension(760, 390));
        pack();
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
            this.frameModel.setFrames(List.of());
            this.variableModel.setVariables(List.of());
        }
    }

    private void showPausedState(DebuggerSessionController.PausedState state) {
        this.frameModel.setFrames(state.frames());
        if (!state.frames().isEmpty()) {
            this.frames.setRowSelectionInterval(0, 0);
        } else {
            this.variableModel.setVariables(List.of());
        }
        showWindow();
    }

    private void selectFrame(int row) {
        DebugEngine.StackFrame frame = this.frameModel.frame(row);
        if (frame == null) {
            return;
        }
        DebuggerSessionController.PausedState paused = this.controller.pausedState();
        if (paused != null && !paused.frames().isEmpty() && paused.frames().getFirst().equals(frame)) {
            this.variableModel.setVariables(paused.variables());
            this.statusLabel.setText(frame.name() + ":" + frame.line());
            CompanionApp.openDebugFrame(frame);
            return;
        }
        this.statusLabel.setText("Loading local variables for " + frame.name());
        this.controller.variablesForFrame(frame).whenComplete((loaded, failure) -> SwingUtilities.invokeLater(() -> {
            if (failure != null) {
                String detail = failure.getMessage();
                this.statusLabel.setText(detail == null || detail.isBlank()
                        ? "Unable to load local variables"
                        : detail);
                return;
            }
            this.variableModel.setVariables(loaded);
            this.statusLabel.setText(frame.name() + ":" + frame.line());
            CompanionApp.openDebugFrame(frame);
        }));
    }

    @Override
    public void dispose() {
        this.controller.removeListener(this.listener);
        super.dispose();
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
                case 1 -> frame.sourceUri() == null ? "" : frame.sourceUri().toString();
                case 2 -> frame.line();
                default -> "";
            };
        }
    }

    private static final class VariableTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"Name", "Value", "Type"};
        private List<DebugEngine.Variable> variables = List.of();

        void setVariables(List<DebugEngine.Variable> replacement) {
            this.variables = List.copyOf(replacement);
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return this.variables.size();
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
            DebugEngine.Variable variable = this.variables.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> variable.name();
                case 1 -> variable.value();
                case 2 -> variable.type();
                default -> "";
            };
        }
    }
}
