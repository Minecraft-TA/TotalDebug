package com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerSessionController;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.DynamicMatteBorder;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;

import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Insets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/** Coordinates debugger lifecycle state, stack frames, and the selected-frame inspector. */
public final class DebuggerPanel extends JPanel {
    @FunctionalInterface
    public interface FrameNavigation {
        void open(DebugEngine.StackFrame frame, boolean activateEditor);
    }

    private final DebuggerSessionController controller;
    private final DebuggerActions debuggerActions;
    private final FrameNavigation frameNavigation;
    private final JLabel statusLabel = new JLabel("Debugger is unavailable", Icons.INFORMATION, JLabel.LEADING);
    private final JLabel frameLabel = new MutedLabel();
    private final DebuggerFramesPane frames;
    private final DebuggerInspector inspector;
    private final JButton attach;
    private final JButton resume;
    private final JButton stepOver;
    private final JButton stepInto;
    private final JButton stepOut;
    private final JButton detach;
    private final JButton cancelEvaluation = new JButton("Cancel evaluation");
    private final javax.swing.Timer evaluationTimer = new javax.swing.Timer(250, event -> updateEvaluationStatus());
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

    private long selectionRevision;
    private DebugEngine.StackFrame currentFrame;
    private DebuggerSessionController.PausedState currentPause;
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
            Runnable showBreakpoints,
            Consumer<NavigationTarget> navigation
    ) {
        super(new BorderLayout());
        this.controller = Objects.requireNonNull(controller, "controller");
        this.debuggerActions = Objects.requireNonNull(debuggerActions, "debuggerActions");
        this.frameNavigation = Objects.requireNonNull(frameNavigation, "frameNavigation");
        this.frames = new DebuggerFramesPane(this::selectFrame, frameNavigation);
        this.inspector = new DebuggerInspector(controller, navigation);

        this.attach = toolbarButton(debuggerActions.attach());
        this.resume = toolbarButton(debuggerActions.resume());
        this.stepOver = toolbarButton(debuggerActions.stepOver());
        this.stepInto = toolbarButton(debuggerActions.stepInto());
        this.stepOut = toolbarButton(debuggerActions.stepOut());
        this.detach = toolbarButton(debuggerActions.detach());
        this.cancelEvaluation.addActionListener(event -> this.controller.cancelActiveEvaluation());
        this.cancelEvaluation.setVisible(false);
        this.viewBreakpoints = createViewBreakpointsButton();
        this.viewBreakpoints.addActionListener(event -> showBreakpoints.run());
        this.muteBreakpoints = createMuteBreakpointsButton();
        this.muteBreakpoints.setSelected(controller.breakpointsMuted());
        this.muteBreakpoints.addActionListener(event ->
                controller.setBreakpointsMuted(this.muteBreakpoints.isSelected()));

        setBorder(BorderFactory.createEmptyBorder());
        add(createToolbar(), BorderLayout.NORTH);

        JSplitPane split = new InitialProportionSplitPane(0.42, this.frames, this.inspector);
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
        actions.add(this.cancelEvaluation);
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
        this.inspector.setPaused(paused);
        updateEvaluationStatus();
        if (!paused) {
            this.selectionRevision++;
            if (clearsPausedSnapshot(status.phase())) {
                clearPausedSnapshot();
            }
        }
    }

    private void updateEvaluationStatus() {
        var evaluation = this.controller.evaluationStatus();
        boolean busy = evaluation != null;
        this.cancelEvaluation.setVisible(busy);
        this.cancelEvaluation.setEnabled(busy && !evaluation.cancellationRequested());
        this.inspector.setEvaluationBusy(busy);
        if (busy) {
            this.statusLabel.setText((evaluation.cancellationRequested() ? "Cancellation requested" : "Evaluating")
                    + " (" + evaluation.elapsedMillis() / 1000 + "s)"
                    + (evaluation.slow() ? " - waiting for the target call to return" : ""));
            if (!this.evaluationTimer.isRunning()) this.evaluationTimer.start();
        } else {
            this.statusLabel.setText(this.controller.status().detail());
            this.evaluationTimer.stop();
        }
    }

    private static boolean clearsPausedSnapshot(DebuggerSessionController.Phase phase) {
        return phase == DebuggerSessionController.Phase.UNAVAILABLE
                || phase == DebuggerSessionController.Phase.DETACHED
                || phase == DebuggerSessionController.Phase.FAILED;
    }

    private void clearPausedSnapshot() {
        this.currentFrame = null;
        this.currentPause = null;
        this.frameLabel.setText("");
        this.frames.setFrames(List.of());
        this.inspector.clear();
    }

    void showPausedState(DebuggerSessionController.PausedState state) {
        this.selectionRevision++;
        this.currentFrame = null;
        this.currentPause = Objects.requireNonNull(state, "state");
        this.statusLabel.setText(stopDescription(state.event()));
        this.statusLabel.setIcon(Icons.WARNING);
        this.frames.setFrames(state.frames());
        if (!state.frames().isEmpty()) {
            this.frames.selectFirst();
            if (this.currentFrame == null) {
                selectFrame(0);
            }
        } else {
            this.frameLabel.setText("");
            this.inspector.showStatus(null, "No stack frames available");
        }
    }

    private void selectFrame(int row) {
        DebugEngine.StackFrame frame = this.frames.frame(row);
        if (frame == null) {
            return;
        }
        long revision = ++this.selectionRevision;
        this.currentFrame = frame;
        this.frameLabel.setText(frameLocation(frame));
        this.inspector.beginFrame(frame);
        this.frameNavigation.open(frame, false);

        DebuggerSessionController.PausedState pause = this.currentPause;
        if (pause != null && !pause.frames().isEmpty() && pause.frames().getFirst().equals(frame)) {
            this.inspector.showVariables(frame, pause.variables());
            return;
        }

        this.controller.variablesForFrame(frame).whenComplete((loaded, failure) -> onEventThread(() -> {
            if (!isCurrent(frame, revision)) {
                return;
            }
            if (failure != null) {
                if (!isCancellation(failure)) {
                    this.inspector.showStatus(frame, failureMessage(failure));
                }
                return;
            }
            this.inspector.showVariables(frame, loaded);
        }));
    }

    void focusVariable(DebugEngine.StackFrame frame, DebugEngine.Variable variable) {
        this.inspector.focusVariable(frame, variable);
    }

    private boolean isCurrent(DebugEngine.StackFrame frame, long revision) {
        return revision == this.selectionRevision && Objects.equals(frame, this.currentFrame);
    }

    private static String frameLocation(DebugEngine.StackFrame frame) {
        String owner = frame.binaryName().isBlank() ? "Unknown source" : frame.binaryName();
        return owner + (frame.line() > 0 ? ":" + frame.line() : "");
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

    private static boolean isCancellation(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current instanceof CancellationException;
    }

    private static String failureMessage(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        String detail = current.getMessage();
        return detail == null || detail.isBlank() ? "Unable to load variables" : detail;
    }

    public void dispose() {
        if (this.disposed) {
            return;
        }
        this.disposed = true;
        this.evaluationTimer.stop();
        this.inspector.close();
        this.controller.removeListener(this.listener);
    }

    private static JComponent toolbarSeparator() {
        JPanel separator = new JPanel();
        separator.setBorder(DynamicMatteBorder.separatorRule(0, 1, 0, 0));
        separator.setPreferredSize(new Dimension(7, 20));
        separator.setOpaque(false);
        return separator;
    }

    private static JButton createViewBreakpointsButton() {
        JButton button = new JButton(Icons.VIEW_BREAKPOINTS);
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        button.setToolTipText("View breakpoints");
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

    private static JToggleButton createMuteBreakpointsButton() {
        JToggleButton button = new JToggleButton(Icons.MUTE_BREAKPOINTS);
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        button.setToolTipText("Mute breakpoints");
        button.setFocusable(false);
        button.setMargin(new Insets(4, 6, 4, 6));
        return button;
    }

    private static void onEventThread(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
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
