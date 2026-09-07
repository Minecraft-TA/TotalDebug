package com.github.minecraft_ta.totalDebugCompanion.ui.views;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.GlobalConfig;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerSessionController;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaSnippetSource;
import com.github.minecraft_ta.totalDebugCompanion.messages.script.RunScriptMessage;
import com.github.minecraft_ta.totalDebugCompanion.model.ScriptView;
import com.github.minecraft_ta.totalDebugCompanion.script.ExpressionHistory;
import com.github.minecraft_ta.totalDebugCompanion.script.ExecutionResult;
import com.github.minecraft_ta.totalDebugCompanion.script.SnippetExecutionService;
import com.github.minecraft_ta.totalDebugCompanion.script.SnippetExpressionSupport;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.ExpressionCompletionSupport;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconButton;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.JavaExpressionField;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.editors.AbstractTextViewPanel;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.values.ScriptResultTree;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.DynamicMatteBorder;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.EditorPalette;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager;

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Global, history-backed Java expression evaluator for a running Minecraft session. */
public final class EvaluateExpressionWindow extends JDialog {
    private static final String CLASS_NAME = "CompanionExpression";

    private final JavaExpressionField expression = new JavaExpressionField(54);
    private final ExpressionCompletionSupport completion = new ExpressionCompletionSupport(this.expression);
    private final SnippetExpressionSupport expressionSupport = new SnippetExpressionSupport(CLASS_NAME);
    private final SnippetExecutionService executions;
    private final ExpressionHistory history;
    private final JComboBox<EvaluationContext> context = new JComboBox<>();
    private boolean refreshingContexts;
    private final DebuggerSessionController.Listener debuggerListener = new DebuggerSessionController.Listener() {
        @Override public void statusChanged(DebuggerSessionController.Status status) { refreshLater(); }
        @Override public void paused(DebuggerSessionController.PausedState state) { refreshLater(); }
        private void refreshLater() { SwingUtilities.invokeLater(() -> refreshContexts()); }
    };
    private com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerEvaluation<?> pausedExecution;
    private final FlatIconButton evaluate = new FlatIconButton(Icons.EVALUATE_EXPRESSION, false);
    private final FlatIconButton stop = new FlatIconButton(Icons.STOP, false);
    private final ScriptResultTree resultTree = new ScriptResultTree();
    private final JScrollPane resultScroll = new JScrollPane(this.resultTree);
    private final JTextPane output = textPane();
    private final JTextPane problems = textPane();
    private final JTabbedPane results = new JTabbedPane();
    private final JLabel status = new JLabel();
    private final javax.swing.JSplitPane editorSplit = new javax.swing.JSplitPane(javax.swing.JSplitPane.VERTICAL_SPLIT);

    private SnippetExecutionService.Execution activeExecution;
    private JavaSnippetSource.GeneratedSource activeSource;
    private int activeDiagnosticLineOffset;
    private long executionRevision;
    private int historyIndex = -1;
    private boolean evaluationRunning;
    private boolean cancelPending;

    public EvaluateExpressionWindow(Frame owner, SnippetExecutionService executions) {
        super(owner, "Evaluate Expression", false);
        this.executions = executions;
        this.history = CompanionApp.instanceState().expressionHistory();
        configureInput();
        configureResults();
        configureWindow();
        CompanionApp.getDebuggerController().addListener(this.debuggerListener);
        refreshContexts();
    }

    public void showWindow() {
        if (!isVisible()) {
            setLocationRelativeTo(getOwner());
            setVisible(true);
        } else if (!isFocused()) {
            toFront();
            requestFocus();
        }
        SwingUtilities.invokeLater(this.expression::requestFocusInWindow);
    }

    private void configureInput() {
        this.expression.setExpandable(true);
        this.expressionSupport.setAutomaticMode(true);
        this.expression.setPlaceholder("Evaluate Java expression (Enter)");
        this.expression.setSemanticTokenProvider(this.expressionSupport::tokens);
        this.expression.addActionListener(event -> evaluate());
        this.completion.setCompletionProvider(this.expressionSupport::complete);
        this.completion.setAcceptanceListener(this.expressionSupport::accepted);

        this.context.setToolTipText("Evaluation context");
        this.context.addActionListener(event -> {
            if (!this.refreshingContexts) updateContext();
        });
        this.expression.addPropertyChangeListener("multiline", event -> {
            boolean expanded = this.expression.isMultiline();
            this.expression.setPlaceholder(expanded ? "Java expression or statements (Ctrl+Enter)" : "Evaluate Expression (Enter)");
            this.evaluate.setToolTipText(expanded ? "Evaluate (Ctrl+Enter)" : "Evaluate (Enter)");
            this.editorSplit.setDividerSize(expanded ? 5 : 0);
            SwingUtilities.invokeLater(() -> this.editorSplit.resetToPreferredSizes());
        });

        this.evaluate.setToolTipText("Evaluate Expression (Enter)");
        this.evaluate.addActionListener(event -> evaluate());
        this.stop.setToolTipText("Stop evaluation");
        this.stop.addActionListener(event -> stop());
        this.stop.setVisible(false);

        bindHistory(KeyEvent.VK_UP, 1);
        bindHistory(KeyEvent.VK_DOWN, -1);

        JPanel input = new JPanel(new BorderLayout(6, 0));
        input.setBorder(BorderFactory.createCompoundBorder(
                DynamicMatteBorder.separatorRule(0, 0, 1, 0),
                BorderFactory.createEmptyBorder(7, 8, 7, 8)
        ));
        JPanel contextRow = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 4));
        contextRow.add(this.context);
        input.add(contextRow, BorderLayout.NORTH);
        input.add(this.expression.component(), BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        actions.add(this.evaluate);
        actions.add(this.stop);
        FlatIconButton more = new FlatIconButton(Icons.DOWN_ARROW, false);
        more.setToolTipText("History and script actions");
        more.addActionListener(event -> {
            javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
            javax.swing.JMenuItem saveItem = new javax.swing.JMenuItem("Save as Script", Icons.JAVA_FILE);
            saveItem.setEnabled(!this.evaluationRunning && !this.expression.getText().isBlank());
            saveItem.addActionListener(ignored -> saveAsScript());
            menu.add(saveItem);
            if (!this.history.entries().isEmpty()) menu.addSeparator();
            for (ExpressionHistory.Entry entry : this.history.entries()) {
                String label = entry.expression().replace('\n', ' ').replace('\r', ' ');
                javax.swing.JMenuItem item = new javax.swing.JMenuItem(label.length() > 70 ? label.substring(0, 67) + "..." : label);
                item.setEnabled(!this.evaluationRunning);
                item.addActionListener(ignored -> restoreHistory(entry));
                menu.add(item);
            }
            menu.show(more, 0, more.getHeight());
        });
        more.setMargin(new java.awt.Insets(2, 4, 2, 4));
        this.expression.addInlineAction(more);
        input.add(actions, BorderLayout.EAST);
        this.editorSplit.setTopComponent(input);
        this.editorSplit.setBorder(BorderFactory.createEmptyBorder());
        this.editorSplit.setDividerSize(0);
        this.editorSplit.setResizeWeight(0);
        add(this.editorSplit, BorderLayout.CENTER);
    }

    private void configureResults() {
        this.results.putClientProperty("JTabbedPane.hideTabAreaWithOneTab", true);
        this.resultScroll.setBorder(BorderFactory.createEmptyBorder());
        JPanel content = new JPanel(new BorderLayout());
        this.status.setBorder(BorderFactory.createEmptyBorder(6, 9, 6, 9));
        this.status.setVisible(false);
        this.status.addPropertyChangeListener("text", event ->
                this.status.setVisible(this.status.getText() != null && !this.status.getText().isBlank()));
        content.add(this.status, BorderLayout.NORTH);
        content.add(this.results, BorderLayout.CENTER);
        this.editorSplit.setBottomComponent(content);
        applyTheme();
    }

    private void configureWindow() {
        setDefaultCloseOperation(HIDE_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent event) { clearDebuggerResults(); }
        });
        setMinimumSize(new Dimension(720, 360));
        setSize(860, 500);
        setIconImages(Icons.createWindowIconImages(ThemeManager.current()));
        ThemeManager.addThemeChangeListener(theme -> {
            setIconImages(Icons.createWindowIconImages(theme));
            applyTheme();
        });
    }

    private void evaluate() {
        String requested = this.expression.getText().trim();
        if (requested.isEmpty() || this.evaluationRunning) {
            return;
        }
        if (selectedContext() == null || !contextAvailable(selectedContext())) return;
        if (selectedContext().frame() != null) {
            evaluatePaused(requested);
            return;
        }
        SnippetExecutionService.Side selectedSide =
                selectedContext().side();
        JavaSnippetSource.GeneratedSource source;
        try {
            source = this.expressionSupport.source(requested);
            this.activeSource = source;
            this.activeDiagnosticLineOffset = this.expressionSupport.imports().size();
            this.activeExecution = this.executions.execute(
                    source,
                    selectedSide,
                    RunScriptMessage.ExecutionEnvironment.POST_TICK
            );
        } catch (RuntimeException exception) {
            this.activeSource = null;
            this.activeDiagnosticLineOffset = 0;
            showFailure(exception.getMessage());
            return;
        }
        this.history.record(new ExpressionHistory.Entry(
                requested,
                selectedSide,
                this.expressionSupport.imports(), JavaSnippetSource.detectMode(requested)
        ));
        this.historyIndex = -1;
        long revision = ++this.executionRevision;
        setRunning(true);
        this.status.setText("Compiling…");
        clearResults();
        this.activeExecution.completion().whenComplete((outcome, failure) ->
                SwingUtilities.invokeLater(() -> finish(revision, outcome, failure)));
    }

    private void evaluatePaused(String requested) {
        var selected = selectedContext();
        var frame = selected.frame();
        if (frame == null || selected.pauseId() == null) {
            showFailure("Select a frame from a current debugger pause");
            return;
        }
        StringBuilder source = new StringBuilder();
        this.expressionSupport.imports().forEach(imported -> source.append("import ").append(imported).append(";\n"));
        source.append(requested);
        var controller = CompanionApp.getDebuggerController();
        String pauseId = selected.pauseId();
        setRunning(true);
        this.status.setText("Evaluating in " + frame.name());
        clearResults();
        controller.startEvaluation(pauseId, frame.id(), source.toString()).whenComplete((operation, startFailure) ->
                SwingUtilities.invokeLater(() -> {
                    if (startFailure != null) { setRunning(false); showFailure(startFailure.getMessage()); return; }
                    this.pausedExecution = operation;
                    if (this.cancelPending) operation.cancel();
                    this.history.record(new ExpressionHistory.Entry(requested,
                            SnippetExecutionService.Side.CLIENT, this.expressionSupport.imports(),
                            JavaSnippetSource.detectMode(requested)));
                    this.historyIndex = -1;
                    operation.completion().whenComplete((value, failure) -> SwingUtilities.invokeLater(() -> {
                        this.pausedExecution = null;
                        setRunning(false);
                        if (failure != null) { showFailure(failure.getMessage()); return; }
                        if (!java.util.Objects.equals(controller.snapshot().pauseId(), pauseId)) {
                            showFailure("Evaluation result expired when its originating pause ended");
                            return;
                        }
                        if (!isVisible()) return;
                        this.results.addTab("Result", new com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger.DebuggerResultPanel(
                                controller, pauseId, value));
                        this.status.setText("Evaluation completed in " + frame.name());
                    }));
                }));
    }

    private void finish(long revision, ExecutionResult outcome, Throwable failure) {
        if (revision != this.executionRevision) {
            return;
        }
        this.activeExecution = null;
        JavaSnippetSource.GeneratedSource completedSource = this.activeSource;
        int completedLineOffset = this.activeDiagnosticLineOffset;
        this.activeSource = null;
        this.activeDiagnosticLineOffset = 0;
        setRunning(false);
        clearResults();
        StringBuilder failures = new StringBuilder();
        if (failure != null) {
            failures.append(failure.getMessage());
        } else {
            if (outcome.value() != null) {
                this.resultTree.showResult(outcome.value());
                this.results.addTab("Result", Icons.EVALUATE_EXPRESSION, this.resultScroll);
            }
            if (!outcome.logs().text().isEmpty()) {
                this.output.setText(outcome.logs().displayText());
                this.results.addTab("Output", Icons.TEXT_FILE, scrollPane(this.output));
            }
            if (!outcome.error().text().isEmpty()) {
                if (!failures.isEmpty()) {
                    failures.append(System.lineSeparator());
                }
                String error = outcome.error().displayText();
                failures.append(completedSource == null
                        ? error
                        : completedSource.mapDiagnostics(error, completedLineOffset));
            }
            this.status.setText(outcome.status() == ExecutionResult.Status.RUN_COMPLETED
                    ? "Evaluation completed"
                    : "Evaluation failed");
        }
        if (!failures.isEmpty()) {
            this.problems.setText(failures.toString());
            this.results.addTab("Problems", Icons.ERROR, scrollPane(this.problems));
            this.status.setText("Evaluation failed");
        }
    }

    private void showFailure(String message) {
        clearResults();
        this.problems.setText(message == null ? "Unable to evaluate expression" : message);
        this.results.addTab("Problems", Icons.ERROR, scrollPane(this.problems));
        this.status.setText("Evaluation failed");
    }

    private void stop() {
        this.cancelPending = true;
        if (this.pausedExecution != null) {
            this.pausedExecution.cancel();
            this.status.setText("Cancellation requested; waiting for the current target call to return");
        }
        if (this.activeExecution != null) {
            this.activeExecution.cancel().run();
            this.status.setText("Cancellation requested; waiting for the script to finish");
        }
    }

    private void setRunning(boolean running) {
        this.evaluationRunning = running;
        this.cancelPending = false;
        this.expression.setEnabled(!running);
        this.context.setEnabled(!running);
        this.evaluate.setVisible(!running);
        this.evaluate.setEnabled(!running && contextAvailable(selectedContext()));
        this.stop.setVisible(running);
    }

    private void bindHistory(int keyCode, int direction) {
        String action = direction < 0 ? "expressionHistory.previous" : "expressionHistory.next";
        this.expression.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(keyCode, InputEvent.ALT_DOWN_MASK),
                action
        );
        this.expression.getActionMap().put(action, new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                moveHistory(direction);
            }
        });
    }

    private void moveHistory(int direction) {
        List<ExpressionHistory.Entry> entries = this.history.entries();
        if (entries.isEmpty()) {
            return;
        }
        this.historyIndex = Math.clamp(this.historyIndex + direction, -1, entries.size() - 1);
        if (this.historyIndex < 0) {
            this.expression.setText("");
            this.expressionSupport.setImports(List.of());
            return;
        }
        ExpressionHistory.Entry entry = entries.get(this.historyIndex);
        restoreHistory(entry);
    }

    private void restoreHistory(ExpressionHistory.Entry entry) {
        this.expression.setMultiline(entry.mode() == JavaSnippetSource.Mode.BODY || entry.expression().contains("\n"));
        this.expression.setText(entry.expression());
        this.expression.setCaretPosition(entry.expression().length());
        this.expressionSupport.setImports(entry.imports());
    }

    private void saveAsScript() {
        String requested = this.expression.getText().trim();
        if (requested.isEmpty()) {
            return;
        }
        String name = JOptionPane.showInputDialog(this, "Script name:", "Save as Script",
                JOptionPane.PLAIN_MESSAGE);
        if (name == null) {
            return;
        }
        name = name.trim();
        if (!JavaSnippetSource.isValidClassName(name)) {
            JOptionPane.showMessageDialog(this, "Enter a valid Java identifier.",
                    "Invalid script name", JOptionPane.ERROR_MESSAGE);
            return;
        }
        Path path = CompanionApp.instancePaths().scripts().resolve(name + ScriptView.FILE_EXTENSION);
        if (Files.exists(path)) {
            JOptionPane.showMessageDialog(this, "A script with that name already exists.",
                    "Script exists", JOptionPane.ERROR_MESSAGE);
            return;
        }
        StringBuilder source = new StringBuilder();
        for (String imported : this.expressionSupport.imports()) {
            source.append("import ").append(imported).append(";\n");
        }
        if (JavaSnippetSource.detectMode(requested) == JavaSnippetSource.Mode.BODY) source.append(requested).append(System.lineSeparator());
        else source.append("return ").append(requested).append(';').append(System.lineSeparator());
        try {
            Files.createDirectories(path.getParent());
            com.github.minecraft_ta.totaldebug.storage.AtomicFiles.createNewString(path, source.toString());
        } catch (IOException exception) {
            JOptionPane.showMessageDialog(this, exception.getMessage(),
                    "Unable to save script", JOptionPane.ERROR_MESSAGE);
            return;
        }
        String scriptName = name;
        MainWindow.INSTANCE.getEditorTabs().focusOrCreateIfAbsent(
                ScriptView.class,
                view -> view.getTitle().equals(path.getFileName().toString()),
                () -> new ScriptView(scriptName)
        );
        MainWindow.INSTANCE.refreshRuntimeSources();
    }

    private void applyTheme() {
        EditorPalette palette = ThemeManager.palette();
        configureTextPane(this.output, palette, ThemeColors.text());
        configureTextPane(this.problems, palette, ThemeColors.error());
    }

    private record EvaluationContext(SnippetExecutionService.Side side, String pauseId,
                                     DebugEngine.StackFrame frame, String label) {
        @Override public String toString() { return this.label; }
    }

    private EvaluationContext selectedContext() { return (EvaluationContext) this.context.getSelectedItem(); }

    private boolean contextAvailable(EvaluationContext context) {
        if (context == null) return false;
        if (context.frame() == null) return true;
        var snapshot = CompanionApp.getDebuggerController().snapshot();
        return java.util.Objects.equals(context.pauseId(), snapshot.pauseId()) && snapshot.pause() != null
                && snapshot.pause().frames().stream().anyMatch(frame -> frame.id() == context.frame().id());
    }

    private void refreshContexts() {
        EvaluationContext previous = selectedContext();
        this.refreshingContexts = true;
        this.context.removeAllItems();
        this.context.addItem(new EvaluationContext(SnippetExecutionService.Side.CLIENT, null, null, "Client"));
        this.context.addItem(new EvaluationContext(SnippetExecutionService.Side.SERVER, null, null, "Server"));
        var snapshot = CompanionApp.getDebuggerController().snapshot();
        if (snapshot.pause() != null) {
            for (var frame : snapshot.pause().frames()) {
                this.context.addItem(new EvaluationContext(null, snapshot.pauseId(), frame,
                        "Paused · " + frame.name() + ":" + frame.line()));
            }
        }
        if (previous != null) {
            if (previous.frame() != null && !contextAvailable(previous)) {
                previous = new EvaluationContext(null, previous.pauseId(), previous.frame(), "Frame no longer paused");
                this.context.addItem(previous);
            }
            this.context.setSelectedItem(previous);
        }
        this.refreshingContexts = false;
        updateContext();
    }

    private void updateContext() {
        EvaluationContext selected = selectedContext();
        if (selected != null) this.context.setPrototypeDisplayValue(selected);
        this.evaluate.setEnabled(!this.evaluationRunning && contextAvailable(selected));
        if (!this.evaluationRunning && !contextAvailable(selected)) this.status.setText("Frame no longer paused; select an evaluation context");
        else if (!this.evaluationRunning && this.status.getText().startsWith("Frame no longer paused")) {
            this.status.setText("");
        }
        if (selected != null && selected.frame() != null && contextAvailable(selected)) {
            var controller = CompanionApp.getDebuggerController();
            this.completion.setCompletionProvider((text, caret, explicit) -> controller.completions(text, caret, selected.frame()));
            this.expression.setSemanticTokenProvider(text -> controller.expressionTokens(text, selected.frame()));
        } else {
            this.completion.setCompletionProvider(this.expressionSupport::complete);
            this.expression.setSemanticTokenProvider(this.expressionSupport::tokens);
        }
    }

    @Override public void dispose() {
        clearDebuggerResults();
        CompanionApp.getDebuggerController().removeListener(this.debuggerListener);
        super.dispose();
    }

    private void clearDebuggerResults() {
        for (int index = this.results.getTabCount() - 1; index >= 0; index--) {
            if (this.results.getComponentAt(index) instanceof com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger.DebuggerResultPanel panel) {
                panel.close();
                this.results.removeTabAt(index);
            }
        }
    }

    private void clearResults() {
        clearDebuggerResults();
        this.results.removeAll();
    }

    private static JTextPane textPane() {
        JTextPane pane = new JTextPane();
        pane.setEditable(false);
        pane.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        return pane;
    }

    private static JScrollPane scrollPane(JTextPane pane) {
        JScrollPane scrollPane = new JScrollPane(pane);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        return scrollPane;
    }

    private static void configureTextPane(JTextPane pane, EditorPalette palette, java.awt.Color foreground) {
        pane.setBackground(palette.background());
        pane.setForeground(foreground);
        pane.setCaretColor(palette.caret());
        Font font = AbstractTextViewPanel.JETBRAINS_MONO_FONT.deriveFont(
                GlobalConfig.getInstance().editorFontSize()
        );
        pane.setFont(font);
    }
}
