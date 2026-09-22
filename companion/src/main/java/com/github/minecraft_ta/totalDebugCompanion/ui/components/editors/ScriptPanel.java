package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.script.EditorScriptRunService;
import com.github.minecraft_ta.totalDebugCompanion.notification.NotificationCenter.Source;
import com.github.minecraft_ta.totalDebugCompanion.notification.NotificationCenter.Severity;
import com.github.minecraft_ta.totalDebugCompanion.GlobalConfig;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JDTHacks;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaSnippetSource;
import com.github.minecraft_ta.totalDebugCompanion.jdt.completion.SignatureHelp;
import com.github.minecraft_ta.totalDebugCompanion.jdt.completion.CustomTextEdit;
import com.github.minecraft_ta.totalDebugCompanion.jdt.completion.Range;
import com.github.minecraft_ta.totalDebugCompanion.model.ScriptView;
import com.github.minecraft_ta.totalDebugCompanion.script.ExecutionTextDisplay;
import com.github.minecraft_ta.totalDebugCompanion.ui.ContextMenus;
import com.github.minecraft_ta.totalDebugCompanion.ui.EditorContext;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.CloseButton;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconButton;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.values.ScriptResultTree;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.DynamicMatteBorder;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.EditorPalette;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.CodeCompletionPopup;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.SignatureHelpPopup;
import com.github.minecraft_ta.totalDebugCompanion.util.UIUtils;
import com.github.minecraft_ta.totalDebugCompanion.util.DocumentChangeListener;
import com.github.minecraft_ta.totaldebug.evaluation.CompilationDiagnostic;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResult;
import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptExecutionEnvironment;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ExecutionResultMessage;

import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.formatter.CodeFormatter;
import org.eclipse.text.edits.ReplaceEdit;
import org.eclipse.text.edits.TextEdit;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ForkJoinPool;
import javax.swing.*;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.border.CompoundBorder;
import javax.swing.text.*;

public class ScriptPanel extends AbstractCodeViewPanel {
    private static final System.Logger LOGGER = System.getLogger(ScriptPanel.class.getName());

    private EditorScriptRunService.Run activeRun;
    private EditorScriptRunService.State displayedRunState;
    private Runnable unsubscribeRun = () -> {};
    private Long saveFailureNotification;
    private final ScriptView scriptView;

    private final CodeCompletionPopup codeCompletionPopup;
    private final SignatureHelpPopup signatureHelpPopup;

    private final FlatIconButton runButton = new FlatIconButton(Icons.RUN, false);
    private final FlatIconButton runServerButton = new FlatIconButton(Icons.RUN_SERVER, false);
    private final FlatIconButton stopButton = new FlatIconButton(Icons.STOP, false);
    {
        runButton.setToolTipText("Run on client");
        runServerButton.setToolTipText("Run on server");
        stopButton.setToolTipText("Stop execution");
        stopButton.setEnabled(false);
    }
    private final JComboBox<ScriptExecutionEnvironment> executionEnvironmentComboBox = new JComboBox<>(ScriptExecutionEnvironment.values());
    {
        executionEnvironmentComboBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                var c = (JLabel) super.getListCellRendererComponent(list, environmentLabel((ScriptExecutionEnvironment) value), index, isSelected, cellHasFocus);
                c.setIcon((value == ScriptExecutionEnvironment.THREAD ? null : Icons.WARNING));
                return c;
            }
        });
        executionEnvironmentComboBox.setMaximumSize(new Dimension(360, executionEnvironmentComboBox.getPreferredSize().height));
    }

    private final JPanel logPanelHeader = new JPanel(new FlowLayout(FlowLayout.LEFT));
    private final JSplitPane centerSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT) {
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (getBottomComponent() == null)
                return;
            g.setColor(ThemeColors.border());

            var divider = getComponent(0);
            g.fillRect(divider.getX(), divider.getY(), getWidth(), 1);
        }
    };
    private final JTextPane logPanelTextPane = new JTextPane() {
        @Override
        public boolean getScrollableTracksViewportWidth() {
            return getUI().getPreferredSize(this).width <= getParent().getSize().width;
        }

        @Override
        public Dimension getPreferredSize() {
            return getUI().getPreferredSize(this);
        }
    };
    private final JScrollPane logPanelScrollPane = new JScrollPane(logPanelTextPane);
    private final JTextPane errorTextPane = new JTextPane();
    private final JScrollPane errorScrollPane = new JScrollPane(this.errorTextPane);
    private final ScriptProblemsPanel problemsPanel = new ScriptProblemsPanel(this.editorPane);
    private final ScriptResultTree resultTree = new ScriptResultTree();
    private final JScrollPane resultScrollPane = new JScrollPane(this.resultTree);
    private final JTabbedPane runOutputTabs = new JTabbedPane();

    private final ScriptCompletionController completion;
    private long signatureRequest;
    private boolean signatureHelpActive;
    private final Timer signatureTimer = new Timer(120, event -> requestSignatureHelp());
    private boolean disposed;
    private JavaSnippetSource.GeneratedSource lastGeneratedSource;

    public ScriptPanel(EditorContext context, ScriptView scriptView) {
        super(
                context, scriptView.editorKey(),
                scriptView.compilationName(),
                text -> JavaSnippetSource.body(scriptView.compilationName(), text).editorSource(), true, scriptView::getPath
        );
        this.scriptView = scriptView;
        this.codeCompletionPopup = new CodeCompletionPopup(context.owner());
        this.signatureHelpPopup = new SignatureHelpPopup(context.owner());

        var headerBar = Box.createHorizontalBox();
        headerBar.setBackground(ThemeColors.headerBackground());
        headerBar.setBorder(new CompoundBorder(DynamicMatteBorder.rule(0, 0, 1, 0), BorderFactory.createEmptyBorder(5, 0, 5, 0)));

        runButton.addActionListener(e -> runScript(false));
        runServerButton.addActionListener(e -> runScript(true));
        stopButton.addActionListener(e -> { if (activeRun != null) activeRun.stop(); });

        headerBar.add(runButton);
        headerBar.add(runServerButton);
        headerBar.add(stopButton);
        headerBar.add(executionEnvironmentComboBox);
        setHeaderComponent(headerBar);

        this.editorPane.getDocument().addDocumentListener((DocumentChangeListener) event -> {
            if (!disposed && event.getType() != DocumentEvent.EventType.CHANGE) { scriptView.edited(); problemsPanel.refreshSourceState(); }
        });
        this.editorPane.setText(scriptView.getSourceText());
        this.editorPane.getActionMap().put(DefaultEditorKit.deletePrevCharAction, new CustomDeletePrevCharAction());

        setupLogPanel();
        setupSaveBehavior();
        this.completion = new ScriptCompletionController(editorPane, scriptView.compilationName(), codeCompletionPopup,
                ForkJoinPool.commonPool(), analysis::completionAccepted);
        setupSignatureHelp();
        setupFormatting();
        var format = new JButton(this.editorPane.getActionMap().get("formatFile"));
        format.setHideActionText(true);
        format.setToolTipText("Format (Ctrl+Shift+F)");
        format.getAccessibleContext().setAccessibleName("Format");
        FlatIconButton.configure(format);
        headerBar.add(Box.createHorizontalStrut(8));
        headerBar.add(format);

    }

    private Source notificationSource() {
        return Source.capture(context.project(), scriptView.getTitle(), scriptView.getNavigationTarget());
    }

    private void reportNotification(Severity severity, String message) {
        context.notifications().publish(severity, message, "", notificationSource());
    }

    private void acceptRunState(EditorScriptRunService.Run run, EditorScriptRunService.State state,
                                JavaSnippetSource.GeneratedSource submitted) {
        SwingUtilities.invokeLater(() -> {
            if (disposed || activeRun != run) return;
            var latest = run.state();
            if (displayedRunState == latest) return;
            displayedRunState = latest;
            if (latest.terminal()) {
                showRunResult(latest.result(), submitted, latest.diagnostics());
                setRunButtonsState(true);
            }
        });
    }

    private void runScript(boolean server) {
        analysis.finishEditing();
        if (!context.scripts().isConnected()) {
            reportNotification(Severity.ERROR, "Not connected to game client!");
            return;
        }

        JavaSnippetSource.GeneratedSource generated;
        try {
            generated = JavaSnippetSource.body(
                    this.scriptView.compilationName(),
                    UIUtils.getText(this.editorPane)
            );
            generated.requireExecutableSize();
        } catch (IllegalArgumentException exception) {
            reportNotification(Severity.ERROR, exception.getMessage());
            return;
        }

        setRunButtonsState(false);
        this.lastGeneratedSource = generated;
        clearRunOutput();
        unsubscribeRun.run();
        try {
            activeRun = context.editorRuns().start(context.project(), notificationSource(), generated.source(), server,
                    (ScriptExecutionEnvironment) executionEnvironmentComboBox.getSelectedItem());
            EditorScriptRunService.Run run = activeRun;
            unsubscribeRun = run.subscribe(state -> acceptRunState(run, state, generated));
        } catch (RuntimeException failure) {
            setRunButtonsState(true);
            reportNotification(Severity.ERROR, failure.getMessage());
        }
    }

    private void setRunButtonsState(boolean state) {
        this.runButton.setEnabled(state && !scriptView.fileOperation());
        this.runServerButton.setEnabled(state && !scriptView.fileOperation());
        this.stopButton.setEnabled(!state);
    }

    private void setupLogPanel() {
        var closeButton = new CloseButton();
        closeButton.addActionListener(e -> centerSplitPane.setBottomComponent(null));

        logPanelHeader.setMaximumSize(new Dimension(10000, 30));
        logPanelHeader.add(closeButton);
        logPanelHeader.add(new JLabel("Run Result"));

        SimpleAttributeSet spacingAttributeSet = new SimpleAttributeSet();
        StyleConstants.setSpaceAbove(spacingAttributeSet, 2);
        StyleConstants.setSpaceBelow(spacingAttributeSet, 2);
        logPanelTextPane.setParagraphAttributes(spacingAttributeSet, false);
        logPanelTextPane.setEditable(false);
        ContextMenus.installOutput(logPanelTextPane, "Copy output");
        logPanelTextPane.setFont(auxiliaryEditorFont());
        logPanelTextPane.setBorder(BorderFactory.createEmptyBorder(0, 3, 0, 0));
        errorTextPane.setEditable(false);
        ContextMenus.installOutput(errorTextPane, "Copy all");
        errorTextPane.setFont(auxiliaryEditorFont());
        errorTextPane.setBorder(BorderFactory.createEmptyBorder(3, 5, 3, 5));

        centerSplitPane.setTopComponent(((BorderLayout) getLayout()).getLayoutComponent(BorderLayout.CENTER));
        logPanelScrollPane.setBorder(BorderFactory.createEmptyBorder());
        errorScrollPane.setBorder(BorderFactory.createEmptyBorder());
        resultScrollPane.setBorder(BorderFactory.createEmptyBorder());

        add(centerSplitPane, BorderLayout.CENTER);
    }

    private void showRunResult(ExecutionResultMessage message, JavaSnippetSource.GeneratedSource submitted,
                               List<CompilationDiagnostic> diagnostics) {
        this.runOutputTabs.removeAll();
        StringBuilder problems = new StringBuilder();
        ExecutionResult result = message.result();
        if (result.value() != null) {
            this.resultTree.showResult(result.value());
            this.runOutputTabs.addTab("Result", Icons.EVALUATE_EXPRESSION, this.resultScrollPane);
        }
        if (!result.logs().text().isEmpty()) {
            this.logPanelTextPane.setText(ExecutionTextDisplay.format(result.logs()));
            this.runOutputTabs.addTab("Output", Icons.TEXT_FILE, this.logPanelScrollPane);
        }
        if (!diagnostics.isEmpty() && submitted != null) {
            this.problemsPanel.showProblems(submitted, diagnostics);
            this.runOutputTabs.addTab("Problems", Icons.ERROR, this.problemsPanel);
        } else if (!result.error().text().isEmpty()) {
            if (!problems.isEmpty()) {
                problems.append(System.lineSeparator());
            }
            problems.append(mapDiagnostics(ExecutionTextDisplay.format(result.error())));
        }
        if (!problems.isEmpty()) {
            this.errorTextPane.setText(problems.toString());
            this.runOutputTabs.addTab("Problems", Icons.ERROR, this.errorScrollPane);
        }
        if (this.runOutputTabs.getTabCount() == 0) {
            centerSplitPane.setBottomComponent(null);
            return;
        }
        if (centerSplitPane.getBottomComponent() == null) {
            centerSplitPane.setBottomComponent(UIUtils.verticalLayout(logPanelHeader, runOutputTabs));
            centerSplitPane.setDividerLocation(0.5d);
        }
    }

    private void clearRunOutput() {
        this.problemsPanel.clear();
        this.resultTree.clearResult();
        this.logPanelTextPane.setText("");
        this.errorTextPane.setText("");
        this.runOutputTabs.removeAll();
    }

    private String mapDiagnostics(String diagnostic) {
        return this.lastGeneratedSource == null
                ? diagnostic
                : this.lastGeneratedSource.mapDiagnostics(diagnostic);
    }

    @Override
    protected void applyTheme() {
        super.applyTheme();
        // applyTheme() is first called from the AbstractCodeViewPanel constructor, before this
        // subclass's field initialisers have run.
        if (this.logPanelTextPane == null) {
            return;
        }
        EditorPalette palette = ThemeManager.palette();
        this.logPanelTextPane.setBackground(palette.background());
        this.logPanelTextPane.setForeground(palette.foreground());
        this.logPanelTextPane.setCaretColor(palette.caret());
        this.errorTextPane.setBackground(palette.background());
        this.errorTextPane.setForeground(ThemeColors.error());
        this.errorTextPane.setCaretColor(palette.caret());
    }

    @Override
    protected void updateFonts() {
        super.updateFonts();
        SwingUtilities.invokeLater(() -> {
            var newFont = JETBRAINS_MONO_FONT.deriveFont(GlobalConfig.getInstance().editorFontSize());
            codeCompletionPopup.setFont(newFont);
            signatureHelpPopup.setFont(newFont);
            if (logPanelTextPane != null) {
                logPanelTextPane.setFont(auxiliaryEditorFont());
                errorTextPane.setFont(auxiliaryEditorFont());
            }
        });
    }

    private static Font auxiliaryEditorFont() {
        return JETBRAINS_MONO_FONT.deriveFont(Math.max(
                10f,
                GlobalConfig.getInstance().editorFontSize() - 2f
        ));
    }

    private Timer saveTimer;
    private String savedText;
    private CompletableFuture<Void> saveTail = CompletableFuture.completedFuture(null);

    private void setupSaveBehavior() {
        this.savedText = this.scriptView.getSourceText();
        this.saveTimer = new Timer(500, event -> saveScript());
        this.saveTimer.setRepeats(false);

        this.editorPane.getInputMap().put(KeyStroke.getKeyStroke("control S"), "saveScript");
        this.editorPane.getActionMap().put("saveScript", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                saveScript();
            }
        });
        this.editorPane.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                saveTimer.restart();
            }
        });
    }

    private void setupSignatureHelp() {
        signatureTimer.setRepeats(false);
        editorPane.getInputMap().put(KeyStroke.getKeyStroke("ctrl P"), "signatureHelp");
        editorPane.getActionMap().put("signatureHelp", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) {
                signatureHelpActive = true;
                requestSignatureHelp();
            }
        });
        editorPane.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent event) { hideSignatureHelp(); }
        });
        editorPane.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.VK_ESCAPE && signatureHelpActive) {
                    hideSignatureHelp();
                    event.consume();
                }
            }
        });
        editorPane.addCaretListener(event -> {
            if (signatureHelpActive) {
                signatureRequest++;
                signatureTimer.restart();
            }
        });
    }

    private void setupFormatting() {
        this.editorPane.getActionMap().put("formatFile", new AbstractAction("Format", Icons.REFORMAT_CODE) {
            @Override
            public void actionPerformed(ActionEvent e) {
                String editorText = UIUtils.getText(editorPane);
                JavaSnippetSource.GeneratedSource generated = JavaSnippetSource.body(
                        scriptView.compilationName(),
                        editorText
                );
                int bodyOffset = generated.sourceMap().editorBodyOffset();
                String body = editorText.substring(bodyOffset);
                TextEdit edit = ToolFactory.createCodeFormatter(JDTHacks.DUMMY_JAVA_PROJECT.getOptions(false))
                        .format(CodeFormatter.K_STATEMENTS, body, 0, body.length(), 0, "\n");
                if (edit == null) {
                    reportNotification(Severity.ERROR, "Unable to format this script.");
                    return;
                }
                List<ReplaceEdit> replacements = replacementEdits(edit);

                completion.applyEdits(replacements.reversed().stream().map(replacement -> new CustomTextEdit(
                        new Range(bodyOffset + replacement.getOffset(), replacement.getLength()), replacement.getText())).toList());
                reportNotification(Severity.INFORMATION,
                        replacements.isEmpty() ? "Already formatted" : "Applied %d formatting edits".formatted(replacements.size())
                );
            }
        });
        this.editorPane.getInputMap().put(KeyStroke.getKeyStroke("ctrl shift F"), "formatFile");
    }

    private static List<ReplaceEdit> replacementEdits(TextEdit root) {
        List<ReplaceEdit> result = new ArrayList<>();
        collectReplacementEdits(root, result);
        result.sort(Comparator.comparingInt(ReplaceEdit::getOffset));
        return result;
    }

    private static void collectReplacementEdits(TextEdit edit, List<ReplaceEdit> result) {
        if (edit instanceof ReplaceEdit replacement) {
            result.add(replacement);
            return;
        }
        for (TextEdit child : edit.getChildren()) {
            collectReplacementEdits(child, result);
        }
    }

    private void hideSignatureHelp() {
        signatureHelpActive = false;
        signatureRequest++;
        signatureTimer.stop();
        signatureHelpPopup.setVisible(false);
    }

    private void requestSignatureHelp() {
        if (disposed || !signatureHelpActive) return;
        long request = ++signatureRequest;
        var generated = JavaSnippetSource.body(scriptView.compilationName(), UIUtils.getText(editorPane));
        int caret = generated.sourceMap().toGeneratedOffset(editorPane.getCaretPosition());
        if (caret < 0) { hideSignatureHelp(); return; }
        CompletableFuture.supplyAsync(() -> SignatureHelp.find(scriptView.compilationName(), generated.source(), caret))
                .whenComplete((help, failure) -> SwingUtilities.invokeLater(() -> {
                    if (disposed || request != signatureRequest || !signatureHelpActive || !editorPane.isFocusOwner()) return;
                    if (failure != null) LOGGER.log(System.Logger.Level.WARNING, "Unable to load parameter information", failure);
                    if (help == null || failure != null) { hideSignatureHelp(); return; }
                    int opening = generated.sourceMap().toEditorOffset(help.openingOffset());
                    if (opening < 0) { hideSignatureHelp(); return; }
                    signatureHelpPopup.apply(help);
                    try { signatureHelpPopup.showAtCall(editorPane, opening); }
                    catch (BadLocationException exception) { hideSignatureHelp(); }
                }));
    }

    @Override
    public void dispose() {
        if (this.disposed) return;
        this.disposed = true;
        this.unsubscribeRun.run();
        if (activeRun != null) activeRun.stop();
        this.saveTimer.stop();
        this.completion.close();
        hideSignatureHelp();
        this.signatureHelpPopup.dispose();
        super.dispose();
    }

    public String sourceText() { return UIUtils.getText(editorPane); }
    public boolean isRunning() { return stopButton.isEnabled(); }
    public void saved(String contents) { savedText = contents; }
    public CompletableFuture<Void> pendingSave() { return saveTail; }
    public void setFileOperation(boolean value) {
        saveTimer.stop();
        editorPane.setEditable(!value);
        runButton.setEnabled(!value && !isRunning());
        runServerButton.setEnabled(!value && !isRunning());
        if (!value && !sourceText().equals(savedText)) saveTimer.restart();
    }

    public boolean canSave() {
        if (scriptView.fileOperation()) return false;
        saveTimer.stop();
        var saved = saveScript();
        if (saved.isDone()) return saved.getNow(false) || confirmDiscard();
        // Existing window/tab close checks are synchronous. Pump EDT events while disk work finishes.
        var loop = Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
        scriptView.setFileOperation(true);
        saved.whenComplete((ignored, failure) -> SwingUtilities.invokeLater(loop::exit));
        loop.enter();
        scriptView.setFileOperation(false);
        return saved.getNow(false) || confirmDiscard();
    }

    private boolean confirmDiscard() {
        boolean discard = JOptionPane.showOptionDialog(this, "Discard unsaved changes to " + scriptView.getTitle() + "?",
                "Close script", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null,
                new String[]{"Keep editing", "Discard changes and close"}, "Keep editing") == 1;
        if (discard) scriptView.discardOnClose();
        return discard;
    }

    private CompletableFuture<Boolean> saveScript() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Capture script text on the EDT");
        if (scriptView.fileOperation() || disposed) return CompletableFuture.completedFuture(false);
        String text = sourceText();
        if (text.equals(savedText) && saveTail.isDone()) return CompletableFuture.completedFuture(true);
        var result = new CompletableFuture<Boolean>();
        var write = saveTail.handle((ignored, failure) -> null).thenRunAsync(() -> {
            try { scriptView.persist(text); }
            catch (IOException failure) { throw new CompletionException(failure); }
        });
        var published = new CompletableFuture<Void>();
        saveTail = published;
        write.whenComplete((ignored, failure) -> SwingUtilities.invokeLater(() -> {
            if (failure == null) {
                savedText = text;
                saveFailureNotification = null;
            } else {
                String detail = failure.getCause() == null ? failure.toString() : failure.getCause().toString();
                if (saveFailureNotification == null) saveFailureNotification = context.notifications().publish(
                        Severity.ERROR, "Unable to save script", detail, notificationSource());
                else context.notifications().update(saveFailureNotification, detail);
            }
            if (failure == null) published.complete(null); else published.completeExceptionally(failure);
            result.complete(failure == null);
        }));
        return result;
    }

    private static class CustomDeletePrevCharAction extends TextAction {

        public CustomDeletePrevCharAction() {
            super(DefaultEditorKit.deletePrevCharAction);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            var area = (RSyntaxTextArea) getTextComponent(e);

            //Base implementation copied from org.fife.ui.rtextarea.RTextAreaEditorKit$DeletePrevCharAction
            try {
                var document = area.getDocument();
                var caret = area.getCaret();
                int dot = caret.getDot();
                int mark = caret.getMark();
                if (dot != mark) {
                    document.remove(Math.min(dot, mark), Math.abs(dot - mark));
                } else if (dot > 0) {
                    int delChars = 1;
                    if (dot > 1) {
                        delChars = fixDelCharsCount(caret, document);
                    }
                    document.remove(dot - delChars, delChars);
                }
            } catch (BadLocationException ignored) {
            }
        }

        private int fixDelCharsCount(Caret caret, Document document) {
            int dot = caret.getDot();

            var root = document.getDefaultRootElement();
            var line = root.getElement(root.getElementIndex(dot));
            var start = line.getStartOffset();
            var len = line.getEndOffset() - 1 - start;
            try {
                var lineText = document.getText(start, len);

                if (lineText.isEmpty())
                    return 1;

                if (lineText.isBlank())
                    return dot - len < 1 ? len : len + 1;

                if (lineText.length() < 2)
                    return 1;

                char c0 = lineText.charAt(0);
                char c1 = lineText.charAt(1);
                if (c0 >= '\uD800' && c0 <= '\uDBFF' &&
                    c1 >= '\uDC00' && c1 <= '\uDFFF') {
                    return 2;
                }
            } catch (BadLocationException ignored) {}

            return 1;
        }
    }
    private static String environmentLabel(ScriptExecutionEnvironment environment) {
        return switch (environment) {
            case THREAD -> "Thread";
            case PRE_TICK -> "Pre Tick";
            case POST_TICK -> "Post Tick";
        };
    }
}
