package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptExecutionEnvironment;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionStatus;
import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.GlobalConfig;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaSnippetSource;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JDTHacks;
import com.github.minecraft_ta.totalDebugCompanion.jdt.completion.*;
import com.github.minecraft_ta.totalDebugCompanion.jdt.completion.jdtLs.CodeFormatterUtil;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.CustomJavaParser;
import com.github.minecraft_ta.totalDebugCompanion.jdt.impls.CompilationUnitImpl;
import com.github.minecraft_ta.totaldebug.protocol.scnet.RunScriptMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ExecutionResultMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.StopScriptMessage;
import com.github.minecraft_ta.totalDebugCompanion.model.ScriptView;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResult;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.CloseButton;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconButton;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.values.ScriptResultTree;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.CodeCompletionPopup;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.MainWindow;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.SignatureHelpPopup;
import com.github.minecraft_ta.totalDebugCompanion.util.UIUtils;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.jdt.core.formatter.CodeFormatter;
import org.eclipse.text.edits.ReplaceEdit;
import org.eclipse.text.edits.TextEdit;
import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import javax.swing.*;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.EditorPalette;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.DynamicMatteBorder;
import javax.swing.border.CompoundBorder;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.HierarchyEvent;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class ScriptPanel extends AbstractCodeViewPanel {
    private static final System.Logger LOGGER = System.getLogger(ScriptPanel.class.getName());

    private static int SCRIPT_ID = 0;
    private final int scriptId = SCRIPT_ID++;
    private final ScriptView scriptView;

    private static final CodeCompletionPopup codeCompletionPopup = new CodeCompletionPopup(MainWindow.INSTANCE);
    private static final SignatureHelpPopup signatureHelpPopup = new SignatureHelpPopup(MainWindow.INSTANCE);

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
                c.setIcon((value == ScriptExecutionEnvironment.THREAD ? null : com.github.minecraft_ta.totalDebugCompanion.Icons.WARNING));
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
    private final ScriptResultTree resultTree = new ScriptResultTree();
    private final JScrollPane resultScrollPane = new JScrollPane(this.resultTree);
    private final JTabbedPane runOutputTabs = new JTabbedPane();

    private final SnippetCompletionAdapter snippetCompletionAdapter = new SnippetCompletionAdapter(this.editorPane);
    private CustomCompletionRequestor completionRequestor;
    private boolean didTypeBeforeCaretMove;
    private int lastCaretPos;
    private JavaSnippetSource.GeneratedSource lastGeneratedSource;

    public ScriptPanel(ScriptView scriptView) {
        super(
                scriptView.getPath().toString(),
                scriptView.getScriptName(),
                text -> JavaSnippetSource.body(scriptView.getScriptName(), text).editorSource()
        );
        this.scriptView = scriptView;

        var headerBar = Box.createHorizontalBox();
        headerBar.setBackground(ThemeColors.headerBackground());
        headerBar.setBorder(new CompoundBorder(DynamicMatteBorder.rule(0, 0, 1, 0), BorderFactory.createEmptyBorder(5, 0, 5, 0)));

        runButton.addActionListener(e -> runScript(false));
        runServerButton.addActionListener(e -> runScript(true));
        stopButton.addActionListener(e -> CompanionApp.send(new StopScriptMessage(this.scriptId)));

        headerBar.add(runButton);
        headerBar.add(runServerButton);
        headerBar.add(stopButton);
        headerBar.add(executionEnvironmentComboBox);
        setHeaderComponent(headerBar);

        this.editorPane.setParserDelay(400);
        this.editorPane.addParser(new CustomJavaParser(scriptView.getPath().toString()));
        this.editorPane.setText(scriptView.getSourceText());
        this.editorPane.getActionMap().put(DefaultEditorKit.deletePrevCharAction, new CustomDeletePrevCharAction());

        setupLogPanel();
        setupSaveBehavior();
        setupAutocompletion();
        setupFormatting();

        CompanionApp.SERVER.getMessageBus().listenAlways(ExecutionResultMessage.class, this, (m) -> {
            if (m.scriptId() != this.scriptId)
                return;

            ExecutionStatus status = m.result().status();
            if (status == ExecutionStatus.RUN_COMPLETED) {
                showRunResult(m);
                this.bottomInformationBar.setSuccessInfoText("Run completed!");
            } else if (status == ExecutionStatus.COMPILATION_FAILED) {
                showRunResult(m);
                this.bottomInformationBar.setFailureInfoText("Compilation failed!");
            } else if (status == ExecutionStatus.RUN_EXCEPTION) {
                showRunResult(m);
                this.bottomInformationBar.setFailureInfoText("Run failed!");
            } else if (status == ExecutionStatus.CANCELLATION_PENDING) {
                this.bottomInformationBar.setProcessInfoText(m.result().error().text());
            } else {
                this.bottomInformationBar.setProcessInfoText("Running...");
            }

            if (status.terminal())
                setRunButtonsState(true);
        });
    }

    private void runScript(boolean server) {
        if (!CompanionApp.SERVER.isClientConnected()) {
            this.bottomInformationBar.setFailureInfoText("Not connected to game client!");
            return;
        }

        JavaSnippetSource.GeneratedSource generated;
        try {
            generated = JavaSnippetSource.body(
                    this.scriptView.getScriptName(),
                    UIUtils.getText(this.editorPane)
            );
            generated.requireExecutableSize();
        } catch (IllegalArgumentException exception) {
            this.bottomInformationBar.setFailureInfoText(exception.getMessage());
            return;
        }

        setRunButtonsState(false);
        this.bottomInformationBar.setProcessInfoText("Compiling...");
        this.lastGeneratedSource = generated;
        clearRunOutput();
        if (!CompanionApp.send(new RunScriptMessage(
                this.scriptId,
                this.lastGeneratedSource.source(),
                server,
                (ScriptExecutionEnvironment) this.executionEnvironmentComboBox.getSelectedItem()
        ))) {
            setRunButtonsState(true);
            this.bottomInformationBar.setFailureInfoText(
                    "Minecraft disconnected before the script was submitted."
            );
        }
    }

    private void setRunButtonsState(boolean state) {
        this.runButton.setEnabled(state);
        this.runServerButton.setEnabled(state);
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
        logPanelTextPane.setFont(auxiliaryEditorFont());
        logPanelTextPane.setBorder(BorderFactory.createEmptyBorder(0, 3, 0, 0));
        errorTextPane.setEditable(false);
        errorTextPane.setFont(auxiliaryEditorFont());
        errorTextPane.setBorder(BorderFactory.createEmptyBorder(3, 5, 3, 5));

        centerSplitPane.setTopComponent(((BorderLayout) getLayout()).getLayoutComponent(BorderLayout.CENTER));
        logPanelScrollPane.setBorder(BorderFactory.createEmptyBorder());
        errorScrollPane.setBorder(BorderFactory.createEmptyBorder());
        resultScrollPane.setBorder(BorderFactory.createEmptyBorder());

        add(centerSplitPane, BorderLayout.CENTER);
    }

    private void showRunResult(ExecutionResultMessage message) {
        this.runOutputTabs.removeAll();
        StringBuilder problems = new StringBuilder();
        ExecutionResult result = message.result();
        if (result.value() != null) {
            this.resultTree.showResult(result.value());
            this.runOutputTabs.addTab("Result", Icons.EVALUATE_EXPRESSION, this.resultScrollPane);
        }
        if (!result.logs().text().isEmpty()) {
            this.logPanelTextPane.setText(com.github.minecraft_ta.totalDebugCompanion.script.ExecutionTextDisplay.format(result.logs()));
            this.runOutputTabs.addTab("Output", Icons.TEXT_FILE, this.logPanelScrollPane);
        }
        if (!result.error().text().isEmpty()) {
            if (!problems.isEmpty()) {
                problems.append(System.lineSeparator());
            }
            problems.append(mapDiagnostics(com.github.minecraft_ta.totalDebugCompanion.script.ExecutionTextDisplay.format(result.error())));
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

    private javax.swing.Timer saveTimer;
    private String savedText;

    private void setupSaveBehavior() {
        this.savedText = this.scriptView.getSourceText();
        this.saveTimer = new javax.swing.Timer(500, event -> saveScript());
        this.saveTimer.setRepeats(false);
        addHierarchyListener(e -> {
            if (e.getChangeFlags() == HierarchyEvent.PARENT_CHANGED && getParent() == null) {
                CompanionApp.send(new StopScriptMessage(this.scriptId));
                this.saveTimer.stop();
            }
        });

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

    private void setupAutocompletion() {
        this.editorPane.getCaret().addChangeListener(e -> {
            var caretPos = this.editorPane.getCaretPosition();
            if (caretPos == this.lastCaretPos)
                return;
            this.lastCaretPos = caretPos;

            if (this.completionRequestor != null)
                this.completionRequestor.setCanceled(true);

            if (!this.didTypeBeforeCaretMove)
                hideCompletionPopup();

            this.didTypeBeforeCaretMove = false;
            signatureHelpPopup.setVisible(false);
        });

        this.editorPane.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ctrl SPACE"), "autoComplete");
        this.editorPane.getActionMap().put("autoComplete", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                requestCompletionProposals();
            }
        });

        //Document synchronization
        ((RSyntaxDocument) this.editorPane.getDocument()).setDocumentFilter(new DocumentFilter() {

            @Override
            public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs) throws BadLocationException {
                if (text == null || text.isEmpty()) {
                    super.replace(fb, offset, length, text, attrs);
                    return;
                }

                didTypeBeforeCaretMove = true;
                super.replace(fb, offset, length, text, attrs);

                //Trigger auto-completion
                var c = text.charAt(text.length() - 1);
                if ((!Character.isLetterOrDigit(c) && c != '.') || text.contains("\n") || text.length() > 1) {
                    hideCompletionPopup();
                    return;
                }
                requestCompletionProposals();
            }
        });

        this.editorPane.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent event) {
                if (completionRequestor != null)
                    completionRequestor.setCanceled(true);
                hideCompletionPopup();
            }
        });
    }

    private void setupFormatting() {
        this.editorPane.getActionMap().put("formatFile", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String editorText = UIUtils.getText(editorPane);
                JavaSnippetSource.GeneratedSource generated = JavaSnippetSource.body(
                        scriptView.getScriptName(),
                        editorText
                );
                int bodyOffset = generated.sourceMap().editorBodyOffset();
                String body = editorText.substring(bodyOffset);
                TextEdit edit = CodeFormatterUtil.format2(
                        CodeFormatter.K_STATEMENTS,
                        body,
                        0,
                        "\n",
                        JDTHacks.DUMMY_JAVA_PROJECT.getOptions(false)
                );
                if (edit == null) {
                    bottomInformationBar.setFailureInfoText("Unable to format this script.");
                    return;
                }
                List<ReplaceEdit> replacements = replacementEdits(edit);

                editorPane.beginAtomicEdit();
                for (int i = replacements.size() - 1; i >= 0; i--) {
                    ReplaceEdit replacement = replacements.get(i);
                    applyTextEdit(new CustomTextEdit(
                            new Range(bodyOffset + replacement.getOffset(), replacement.getLength()),
                            replacement.getText()
                    ));
                }
                editorPane.endAtomicEdit();
                bottomInformationBar.setDefaultInfoText(
                        "Successfully applied %d edit(s).".formatted(replacements.size())
                );
            }
        });
        this.editorPane.getInputMap().put(KeyStroke.getKeyStroke("ctrl shift F"), "formatFile");
    }

    private static List<ReplaceEdit> replacementEdits(TextEdit root) {
        List<ReplaceEdit> result = new java.util.ArrayList<>();
        collectReplacementEdits(root, result);
        result.sort(java.util.Comparator.comparingInt(ReplaceEdit::getOffset));
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

    private void requestCompletionProposals() {
        if (this.completionRequestor != null)
            this.completionRequestor.setCanceled(true);

        int editorOffset = this.editorPane.getCaretPosition();
        JavaSnippetSource.GeneratedSource generated = JavaSnippetSource.body(
                this.scriptView.getScriptName(),
                UIUtils.getText(this.editorPane)
        );
        int completionOffset = generated.sourceMap().toGeneratedOffset(editorOffset);
        if (completionOffset < 0) {
            hideCompletionPopup();
            return;
        }
        var unit = new CompilationUnitImpl(this.scriptView.getScriptName(), generated.source());
        var newRequestor = new CustomCompletionRequestor(
                unit,
                completionOffset,
                (requestor, items) -> acceptCompletionList(requestor, items, generated)
        );
        this.completionRequestor = newRequestor;

        CompletableFuture.runAsync(() -> {
            try {
                unit.codeComplete(completionOffset, newRequestor, newRequestor);
            } catch (OperationCanceledException ignored) {} catch (RuntimeException e) {
                if (e.getCause() instanceof OperationCanceledException)
                    return;

                LOGGER.log(System.Logger.Level.WARNING,
                        "Unable to complete script " + this.scriptView.getScriptName(), e);
            } catch (Throwable e) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "Unable to complete script " + this.scriptView.getScriptName(), e);
            }
        });
    }

    private void doAutoCompletion(CompletionItem item) {
        //The item is outdated
        if (item.getRequestor() != this.completionRequestor || item.getRequestor().isCanceled())
            return;

        this.editorPane.beginAtomicEdit();
        var snippetEdits = item.getTextEdits().stream().filter(CustomTextEdit::isSnippet).toArray(CustomTextEdit[]::new);
        if (snippetEdits.length != 0)
            this.snippetCompletionAdapter.insert(snippetEdits);
        item.getTextEdits().stream().filter(e -> !e.isSnippet()).forEach(this::applyTextEdit);
        this.editorPane.endAtomicEdit();

        hideCompletionPopup();
    }

    private void acceptCompletionList(
            CustomCompletionRequestor requestor,
            List<CompletionItem> completions,
            JavaSnippetSource.GeneratedSource generated
    ) {
        SwingUtilities.invokeLater(() -> {
            if (requestor != this.completionRequestor || requestor.isCanceled())
                return;

            if (!this.editorPane.isFocusOwner()) {
                requestor.setCanceled(true);
                hideCompletionPopup();
                return;
            }

            completions.removeIf(item -> !mapCompletionEdits(item, generated));
            if (completions.isEmpty()) {
                hideCompletionPopup();
                return;
            }

            try {
                codeCompletionPopup.setKeyEnterListener(this::doAutoCompletion);
                codeCompletionPopup.setItems(completions);
                codeCompletionPopup.show(this.editorPane);
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        });
    }

    private static boolean mapCompletionEdits(
            CompletionItem item,
            JavaSnippetSource.GeneratedSource generated
    ) {
        item.getTextEdits().removeIf(edit -> {
            Range range = edit.getRange();
            int generatedStart = range.getOffset();
            int start = generated.sourceMap().toEditorOffset(generatedStart);
            int end = generated.sourceMap().toEditorOffset(range.getEndOffset());
            if (start < 0 || end < start) {
                return true;
            }
            if (range.getLength() == 0) {
                edit.setNewText(generated.sourceMap().mapInsertionText(generatedStart, edit.getNewText()));
            }
            range.setOffset(start);
            range.setLength(end - start);
            return false;
        });
        return !item.getTextEdits().isEmpty();
    }

    private void hideCompletionPopup() {
        if (codeCompletionPopup.isInvokedBy(this.editorPane))
            codeCompletionPopup.setVisible(false);
    }

    @Override
    public void dispose() {
        this.saveTimer.stop();
        if (this.completionRequestor != null)
            this.completionRequestor.setCanceled(true);
        hideCompletionPopup();
        super.dispose();
    }

    public boolean canSave() {
        this.saveTimer.stop();
        return saveScript();
    }

    private boolean saveScript() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Script saves must capture editor text on the EDT");
        }
        String text = UIUtils.getText(this.editorPane);
        if (text.equals(this.savedText)) {
            return true;
        }
        try {
            com.github.minecraft_ta.totaldebug.storage.AtomicFiles.writeString(this.scriptView.getPath(), text);
            this.savedText = text;
            return true;
        } catch (IOException exception) {
            JOptionPane.showMessageDialog(this, "Unable to save " + this.scriptView.getPath()
                    + "\n" + exception.getMessage(), "Script save failed", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    private void applyTextEdit(CustomTextEdit edit) {
        var range = edit.getRange();

        this.snippetCompletionAdapter.beginIgnoredDocumentChange();
        try {
            ((RSyntaxDocument) this.editorPane.getDocument()).replace(range.getOffset(), range.getLength(), edit.getNewText(), null);
        } catch (BadLocationException e) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Unable to apply completion edit to script " + this.scriptView.getScriptName(), e);
        } finally {
            this.snippetCompletionAdapter.endIgnoredDocumentChange();
        }
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
