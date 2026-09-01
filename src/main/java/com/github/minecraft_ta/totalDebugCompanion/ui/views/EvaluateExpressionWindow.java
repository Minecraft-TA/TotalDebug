package com.github.minecraft_ta.totalDebugCompanion.ui.views;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.GlobalConfig;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaSnippetSource;
import com.github.minecraft_ta.totalDebugCompanion.messages.script.RunScriptMessage;
import com.github.minecraft_ta.totalDebugCompanion.model.ScriptView;
import com.github.minecraft_ta.totalDebugCompanion.script.ExpressionHistory;
import com.github.minecraft_ta.totalDebugCompanion.script.ExecutionResult;
import com.github.minecraft_ta.totalDebugCompanion.script.ExecutionValue;
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
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/** Global, history-backed Java expression evaluator for a running Minecraft session. */
public final class EvaluateExpressionWindow extends JDialog {
    private static final String CLASS_NAME = "CompanionExpression";

    private final JavaExpressionField expression = new JavaExpressionField(54);
    private final ExpressionCompletionSupport completion = new ExpressionCompletionSupport(this.expression);
    private final SnippetExpressionSupport expressionSupport = new SnippetExpressionSupport(CLASS_NAME);
    private final SnippetExecutionService executions;
    private final ExpressionHistory history;
    private final JComboBox<SnippetExecutionService.Side> side =
            new JComboBox<>(SnippetExecutionService.Side.values());
    private final FlatIconButton evaluate = new FlatIconButton(Icons.EVALUATE_EXPRESSION, false);
    private final FlatIconButton stop = new FlatIconButton(Icons.STOP, false);
    private final FlatIconButton save = new FlatIconButton(Icons.JAVA_FILE, false);
    private final ScriptResultTree resultTree = new ScriptResultTree();
    private final JScrollPane resultScroll = new JScrollPane(this.resultTree);
    private final JTextPane output = textPane();
    private final JTextPane problems = textPane();
    private final JTabbedPane results = new JTabbedPane();
    private final JLabel status = new JLabel("Enter a Java expression");

    private SnippetExecutionService.Execution activeExecution;
    private JavaSnippetSource.GeneratedSource activeSource;
    private int activeDiagnosticLineOffset;
    private long executionRevision;
    private int historyIndex = -1;

    public EvaluateExpressionWindow(Frame owner, SnippetExecutionService executions) {
        super(owner, "Evaluate Expression", false);
        this.executions = executions;
        this.history = new ExpressionHistory(
                CompanionApp.getRootPath().resolve("history").resolve("evaluate-expression.json")
        );
        configureInput();
        configureResults();
        configureWindow();
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
        this.expression.setPlaceholder("Evaluate Java expression (Enter)");
        this.expression.setSemanticTokenProvider(this.expressionSupport::tokens);
        this.expression.addActionListener(event -> evaluate());
        this.completion.setCompletionProvider(this.expressionSupport::complete);
        this.completion.setAcceptanceListener(this.expressionSupport::accepted);

        this.side.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list,
                    Object value,
                    int index,
                    boolean selected,
                    boolean focused
            ) {
                String label = value == SnippetExecutionService.Side.SERVER ? "Server" : "Client";
                return super.getListCellRendererComponent(list, label, index, selected, focused);
            }
        });
        this.side.setToolTipText("Minecraft side used to evaluate the expression");

        this.evaluate.setToolTipText("Evaluate Expression (Enter)");
        this.evaluate.addActionListener(event -> evaluate());
        this.stop.setToolTipText("Stop evaluation");
        this.stop.addActionListener(event -> stop());
        this.stop.setVisible(false);
        this.save.setToolTipText("Save as Script");
        this.save.addActionListener(event -> saveAsScript());

        bindHistory(KeyEvent.VK_UP, 1);
        bindHistory(KeyEvent.VK_DOWN, -1);

        JPanel input = new JPanel(new BorderLayout(6, 0));
        input.setBorder(BorderFactory.createCompoundBorder(
                DynamicMatteBorder.separatorRule(0, 0, 1, 0),
                BorderFactory.createEmptyBorder(7, 8, 7, 8)
        ));
        input.add(this.side, BorderLayout.WEST);
        input.add(this.expression.component(), BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        actions.add(this.evaluate);
        actions.add(this.stop);
        actions.add(this.save);
        input.add(actions, BorderLayout.EAST);
        add(input, BorderLayout.NORTH);
    }

    private void configureResults() {
        this.resultScroll.setBorder(BorderFactory.createEmptyBorder());
        JPanel content = new JPanel(new BorderLayout());
        this.status.setBorder(BorderFactory.createEmptyBorder(6, 9, 6, 9));
        content.add(this.status, BorderLayout.NORTH);
        content.add(this.results, BorderLayout.CENTER);
        add(content, BorderLayout.CENTER);
        applyTheme();
    }

    private void configureWindow() {
        setDefaultCloseOperation(HIDE_ON_CLOSE);
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
        if (requested.isEmpty() || this.activeExecution != null) {
            return;
        }
        SnippetExecutionService.Side selectedSide =
                (SnippetExecutionService.Side) this.side.getSelectedItem();
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
                this.expressionSupport.imports()
        ));
        this.historyIndex = -1;
        long revision = ++this.executionRevision;
        setRunning(true);
        this.status.setText("Compiling…");
        this.results.removeAll();
        this.activeExecution.completion().whenComplete((outcome, failure) ->
                SwingUtilities.invokeLater(() -> finish(revision, outcome, failure)));
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
        this.results.removeAll();
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
        this.results.removeAll();
        this.problems.setText(message == null ? "Unable to evaluate expression" : message);
        this.results.addTab("Problems", Icons.ERROR, scrollPane(this.problems));
        this.status.setText("Evaluation failed");
    }

    private void stop() {
        if (this.activeExecution != null) {
            this.activeExecution.cancel().run();
            this.status.setText("Stopping…");
        }
    }

    private void setRunning(boolean running) {
        this.expression.setEnabled(!running);
        this.side.setEnabled(!running);
        this.evaluate.setVisible(!running);
        this.stop.setVisible(running);
        this.save.setEnabled(!running);
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
        this.historyIndex = Math.max(-1, Math.min(entries.size() - 1, this.historyIndex + direction));
        if (this.historyIndex < 0) {
            this.expression.setText("");
            this.expressionSupport.setImports(List.of());
            return;
        }
        ExpressionHistory.Entry entry = entries.get(this.historyIndex);
        this.expression.setText(entry.expression());
        this.expression.setCaretPosition(entry.expression().length());
        this.expressionSupport.setImports(entry.imports());
        this.side.setSelectedItem(entry.side());
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
        Path path = CompanionApp.getRootPath().resolve("scripts").resolve(name + ScriptView.FILE_EXTENSION);
        if (Files.exists(path)) {
            JOptionPane.showMessageDialog(this, "A script with that name already exists.",
                    "Script exists", JOptionPane.ERROR_MESSAGE);
            return;
        }
        StringBuilder source = new StringBuilder();
        for (String imported : this.expressionSupport.imports()) {
            source.append("import ").append(imported).append(";\n");
        }
        source.append("return ").append(requested).append(';').append(System.lineSeparator());
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(
                    path,
                    source,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
            );
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
