package com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger;

import com.github.minecraft_ta.totalDebugCompanion.ui.components.ThinSplitPane;

import com.formdev.flatlaf.util.UIScale;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconButton;
import com.github.minecraft_ta.totalDebugCompanion.ui.ContextMenus;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerSessionController;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.ASTCache;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaAst;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.NodeFinder;
import com.github.minecraft_ta.totalDebugCompanion.jdt.insight.ExpressionScopeAnalyzer;
import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.ExpressionCompletionSemantics;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.ExpressionCompletionSupport;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.JavaExpressionField;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.editors.BreakpointGutterMarkers;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryLabel;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryText;
import com.github.minecraft_ta.totalDebugCompanion.ui.speedsearch.SpeedSearch;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.DynamicMatteBorder;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import com.github.minecraft_ta.totalDebugCompanion.util.DocumentChangeListener;
import com.github.minecraft_ta.totalDebugCompanion.util.UIUtils;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JPopupMenu;
import javax.swing.JToolBar;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.KeyStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.URI;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import com.github.minecraft_ta.totalDebugCompanion.script.ScriptFiles;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import javax.swing.Box;
import javax.swing.JComboBox;

/** Modeless editor for every breakpoint in the active runtime. */
public final class BreakpointsWindow extends JDialog {
    private static final Dimension DEFAULT_SIZE = new Dimension(900, 540);

    private final ASTCache cache;
    private final ScriptFiles scripts;
    private final DebuggerSessionController controller;
    private final Consumer<NavigationTarget> navigation;
    private final DefaultListModel<DebuggerSessionController.BreakpointEntry> model = new DefaultListModel<>();
    private final JList<DebuggerSessionController.BreakpointEntry> list = new JList<>(this.model);
    private final JPanel details = new JPanel(new GridBagLayout());
    private final JLabel title = new JLabel("Select a breakpoint");
    private final JLabel state = new JLabel();
    private final JCheckBox enabled = new JCheckBox("Enabled");
    private final JavaExpressionField condition = new JavaExpressionField();
    private final ExpressionCompletionSupport conditionCompletion = new ExpressionCompletionSupport(this.condition);
    private final JTextField hitCount = new JTextField();
    private final JComboBox<String> actionKind = new JComboBox<>(new String[]{"Nothing", "Inline Java", "Saved script"});
    private final JavaExpressionField actionSource = new JavaExpressionField();
    private final ExpressionCompletionSupport actionCompletion = new ExpressionCompletionSupport(actionSource);
    private ExpressionCompletionSupport.CompletionProvider currentCompletion;
    private ExpressionCompletionSupport.CompletionProvider currentActionCompletion;
    private final JComboBox<String> actionScript = new JComboBox<>();
    private final FlatIconButton refreshScripts = new FlatIconButton(Icons.REFRESH, false);
    private final JPanel sourceEditors = new JPanel(new CardLayout());
    private List<String> scriptNames = List.of();
    private boolean scriptsLoading;
    private String scriptsError;
    private long scriptLoadRevision;
    private boolean disposed;
    private final JCheckBox continueOnSuccess = new JCheckBox("Resume after successful action");
    private final JPanel completionField = new JPanel(new BorderLayout(0, 4));
    private final JLabel hitCountError = new JLabel();
    private final JLabel actionError = new JLabel();
    private final JLabel sourceLabel = new JLabel("Java");
    private final JPanel sourceField = fieldWithError(this.sourceEditors, this.actionError);
    private final Action navigate = ContextMenus.action("Open Source", Icons.JUMP_TO_SOURCE, "ENTER", this::navigateSelected);
    private final Action remove = ContextMenus.action("Remove", Icons.DELETE, "DELETE", this::removeSelected);
    private final Action toggle = ContextMenus.action("Disable", Icons.BREAKPOINT_DISABLED, "SPACE", this::toggleSelected);
    private final Action copy = ContextMenus.action("Copy Location", Icons.COPY, "ctrl C", this::copyLocation);
    private final DebuggerSessionController.Listener listener = new DebuggerSessionController.Listener() {
        @Override
        public void breakpointsChanged(
                URI sourceUri,
                List<DebuggerSessionController.Breakpoint> breakpoints
        ) {
            SwingUtilities.invokeLater(BreakpointsWindow.this::reload);
        }

        @Override
        public void breakpointsMutedChanged(boolean muted) {
            SwingUtilities.invokeLater(() -> list.repaint());
        }

        @Override
        public void scriptActionsRemapped(UnaryOperator<String> remap) {
            UIUtils.onEdt(() -> remapScriptSelection(remap));
        }
    };
    private boolean loading;
    private boolean dirty;
    private BreakpointKey editingBreakpoint;

    public BreakpointsWindow(
            ASTCache cache, Window owner,
            DebuggerSessionController controller, ScriptFiles scripts,
            Consumer<NavigationTarget> navigation
    ) {
        super(owner, "Breakpoints", ModalityType.MODELESS);
        this.cache = cache;
        this.scripts = Objects.requireNonNull(scripts, "scripts");
        this.controller = Objects.requireNonNull(controller, "controller");
        this.navigation = Objects.requireNonNull(navigation, "navigation");

        this.list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        this.list.setFixedCellHeight(UiMetrics.TREE_ROW_HEIGHT);
        this.list.setCellRenderer(new BreakpointRenderer());
        for (Action action : List.of(this.navigate, this.remove, this.toggle, this.copy)) ContextMenus.bindAction(this.list, action);
        ContextMenus.installList(this.list, row -> createContextMenu());
        SpeedSearch search = SpeedSearch.install(this.list, entry -> entry.binaryName() + ' '
                + simpleName(entry.binaryName()) + ' ' + entry.breakpoint().line() + ' '
                + entry.breakpoint().request().condition());
        this.list.getInputMap().put(KeyStroke.getKeyStroke("SPACE"), "toggleBreakpoint");
        this.list.getActionMap().put("toggleBreakpoint", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) {
                // A space typed into an active search must not toggle its current match.
                if (!search.isActive()) toggle.actionPerformed(event);
            }
        });
        this.list.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !this.loading) {
                if (saveEditingBreakpoint()) {
                    showSelection();
                } else {
                    this.loading = true;
                    try {
                        selectEditingBreakpoint();
                    } finally {
                        this.loading = false;
                    }
                    focusInvalidField();
                }
            }
        });
        this.list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                int index = list.locationToIndex(event.getPoint());
                if (index < 0 || !list.getCellBounds(index, index).contains(event.getPoint())) {
                    return;
                }
                list.setSelectedIndex(index);
                if (list.getSelectedIndex() != index) return;
                if (SwingUtilities.isLeftMouseButton(event) && event.getX() < 28) {
                    toggleSelected();
                } else if (SwingUtilities.isLeftMouseButton(event) && event.getClickCount() == 2) {
                    navigateSelected();
                }
            }
        });

        this.condition.setPlaceholder("Optional Java condition");
        sourceEditors.setOpaque(false);
        sourceEditors.add(actionSource.component(), "java");
        var scriptPicker = new JPanel(new BorderLayout(4, 0));
        scriptPicker.setOpaque(false);
        actionScript.setMaximumRowCount(12);
        actionScript.setPrototypeDisplayValue("folder/Example.tdscript");
        actionScript.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value == null ? "Select a script" : value, index, selected, focus);
                setIcon(value == null ? null : Icons.SCRIPT_FILE);
                return this;
            }
        });
        scriptPicker.add(actionScript, BorderLayout.CENTER);
        refreshScripts.setToolTipText("Refresh Scripts");
        refreshScripts.getAccessibleContext().setAccessibleName("Refresh Scripts");
        refreshScripts.addActionListener(event -> loadScripts());
        scriptPicker.add(refreshScripts, BorderLayout.EAST);
        sourceEditors.add(scriptPicker, "script");
        actionScript.addActionListener(event -> {
            if (!loading) { dirty = true; saveEditingBreakpoint(); }
        });
        this.hitCount.putClientProperty("JTextField.placeholderText", "Every hit");
        this.hitCount.setToolTipText("Leave empty for every hit. Enter 3 to trigger on the third hit.");
        this.enabled.addActionListener(event -> {
            if (!this.loading) {
                setSelectedEnabled(this.enabled.isSelected());
            }
        });
        this.condition.addActionListener(event -> saveEditingBreakpoint());
        this.hitCount.addActionListener(event -> saveEditingBreakpoint());
        FocusAdapter saveOnBlur = new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent event) {
                saveEditingBreakpoint();
            }
        };
        this.condition.addFocusListener(saveOnBlur);
        this.hitCount.addFocusListener(saveOnBlur);
        this.actionSource.addFocusListener(saveOnBlur);
        this.actionSource.addActionListener(event -> saveEditingBreakpoint());
        DocumentChangeListener edited = event -> {
            if (!this.loading) this.dirty = true;
        };
        this.condition.getDocument().addDocumentListener(edited);
        this.hitCount.getDocument().addDocumentListener(edited);
        this.actionSource.getDocument().addDocumentListener(edited);
        this.actionKind.addActionListener(event -> {
            updateActionFields();
            if (!this.loading) {
                this.dirty = true;
                if (this.actionKind.getSelectedIndex() == 0 || !actionText().isBlank()) {
                    saveEditingBreakpoint();
                }
            }
        });
        this.continueOnSuccess.addActionListener(event -> {
            if (!this.loading) {
                this.dirty = true;
                saveEditingBreakpoint();
            }
        });

        buildDetails();
        JScrollPane breakpointList = new JScrollPane(this.list);
        breakpointList.setBorder(BorderFactory.createEmptyBorder());
        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.setBorder(DynamicMatteBorder.separatorRule(0, 0, 1, 0));
        for (Action action : List.of(this.navigate, this.remove)) {
            JButton button = toolbar.add(action);
            button.setHideActionText(true);
            FlatIconButton.configure(button);
            button.getAccessibleContext().setAccessibleName((String) action.getValue(Action.NAME));
        }
        JPanel listPanel = new JPanel(new BorderLayout());
        listPanel.add(toolbar, BorderLayout.NORTH);
        listPanel.add(breakpointList, BorderLayout.CENTER);
        JSplitPane split = new ThinSplitPane(listPanel, this.details);
        split.setResizeWeight(0.34);
        setContentPane(split);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { closeWindow(); }
        });
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeBreakpoints");
        getRootPane().getActionMap().put("closeBreakpoints", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { closeWindow(); }
        });
        setMinimumSize(new Dimension(800, 500));
        setSize(DEFAULT_SIZE);
        split.setDividerLocation(UIScale.scale(300));
        setLocationRelativeTo(owner);

        this.controller.addListener(this.listener);
        reload();
        loadScripts();
    }

    public void showWindow() {
        if (!isVisible()) {
            loadScripts();
            setVisible(true);
        }
        if (!isActive()) {
            toFront();
        }
    }

    JPopupMenu createContextMenu() {
        JPopupMenu menu = new JPopupMenu();
        menu.add(this.navigate);
        menu.add(this.toggle);
        menu.add(ContextMenus.defaultCopy(this.copy));
        menu.addSeparator();
        menu.add(this.remove);
        return menu;
    }

    private void copyLocation() {
        var entry = this.list.getSelectedValue();
        if (entry != null) {
            ContextMenus.copyText(entry.binaryName() + ":" + entry.breakpoint().line());
        }
    }

    private void closeWindow() {
        if (saveEditingBreakpoint()) setVisible(false);
        else focusInvalidField();
    }

    private void focusInvalidField() {
        if (this.hitCountError.isVisible()) this.hitCount.requestFocusInWindow();
        else if (this.actionError.isVisible()) {
            if (actionKind.getSelectedIndex() == 2) actionScript.requestFocusInWindow();
            else actionSource.requestFocusInWindow();
        }
    }

    private static JPanel fieldWithError(JComponent field, JLabel error) {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setOpaque(false);
        error.setForeground(ThemeColors.error());
        error.setVisible(false);
        panel.add(field, BorderLayout.CENTER);
        panel.add(error, BorderLayout.SOUTH);
        return panel;
    }

    private String actionText() {
        return actionKind.getSelectedIndex() == 2
                ? Objects.toString(actionScript.getSelectedItem(), "") : actionSource.getText().trim();
    }

    private void remapScriptSelection(UnaryOperator<String> remap) {
        if (disposed) return;
        Object selected = actionScript.getSelectedItem();
        scriptNames = scriptNames.stream().map(remap).distinct().sorted().toList();
        boolean previousLoading = loading;
        loading = true;
        try {
            actionScript.setModel(new DefaultComboBoxModel<>(scriptNames.toArray(String[]::new)));
            String mapped = selected instanceof String name ? remap.apply(name) : null;
            if (mapped != null && !scriptNames.contains(mapped)) actionScript.addItem(mapped);
            actionScript.setSelectedItem(mapped);
        } finally { loading = previousLoading; }
        // Invalidates an enumeration started before the move, preserving all other draft fields.
        loadScripts();
    }

    private void loadScripts() {
        long revision = ++scriptLoadRevision;
        scriptsLoading = true;
        actionScript.setEnabled(false);
        refreshScripts.setEnabled(false);
        CompletableFuture.supplyAsync(() -> {
            try { return scripts.listScripts(); }
            catch (IOException failure) { throw new CompletionException(failure); }
        }).whenComplete((names, failure) -> SwingUtilities.invokeLater(() -> {
            if (disposed || revision != scriptLoadRevision) return;
            scriptsLoading = false;
            scriptsError = null;
            if (failure != null) {
                Throwable cause = failure;
                while (cause.getCause() != null) cause = cause.getCause();
                scriptsError = scripts.root() + ": " + cause;
            }
            scriptNames = failure == null ? names : List.of();
            Object selected = actionScript.getSelectedItem();
            loading = true;
            try {
                actionScript.setModel(new DefaultComboBoxModel<>(scriptNames.toArray(String[]::new)));
                if (selected instanceof String name && !scriptNames.contains(name)) actionScript.addItem(name);
                actionScript.setSelectedItem(selected);
            } finally { loading = false; }
            boolean present = editingBreakpoint != null;
            actionScript.setEnabled(present);
            refreshScripts.setEnabled(present);
            if (actionKind.getSelectedIndex() == 2) {
                actionError.setVisible(false);
                if (scriptsError != null) {
                    invalid(actionError, "Could not read scripts. Refresh to retry.");
                    actionError.setToolTipText(scriptsError);
                }
                else if (selected instanceof String name && !scriptNames.contains(name)) invalid(actionError, "Script is unavailable: " + name);
            }
        }));
    }

    private void updateActionFields() {
        int kind = this.actionKind.getSelectedIndex();
        this.actionSource.setMultiline(kind == 1);
        this.actionSource.setPlaceholder("Java expression or statements");
        ((CardLayout) sourceEditors.getLayout()).show(sourceEditors, kind == 2 ? "script" : "java");
        actionCompletion.setCompletionProvider(kind == 1 ? currentActionCompletion : null);
        actionSource.setSemanticTokenProvider(kind == 1 && currentActionCompletion != null
                ? expression -> ExpressionCompletionSemantics.tokens(expression, currentActionCompletion) : null);
        this.sourceLabel.setText(kind == 2 ? "Script" : "Java");
        this.sourceLabel.setVisible(kind != 0);
        this.sourceField.setVisible(kind != 0);
        this.completionField.setVisible(kind != 0);
        this.details.revalidate();
        this.details.repaint();
    }

    private void buildDetails() {
        this.details.setBorder(UiMetrics.pagePadding(12, 12));
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridwidth = 2;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.anchor = GridBagConstraints.NORTHWEST;
        constraints.insets = new Insets(0, 0, 12, 0);
        this.details.add(this.title, constraints);

        constraints.gridy = 1;
        constraints.insets = new Insets(0, 0, 6, 0);
        this.details.add(this.enabled, constraints);
        constraints.gridy = 2;
        constraints.insets = new Insets(0, 4, 18, 0);
        this.details.add(this.state, constraints);

        addField(3, new JLabel("Condition"), this.condition.component());
        addField(4, new JLabel("Trigger on hit"), fieldWithError(this.hitCount, this.hitCountError));
        addField(5, new JLabel("Run on hit"), this.actionKind);
        addField(6, this.sourceLabel, this.sourceField);
        this.completionField.setOpaque(false);
        this.completionField.add(this.continueOnSuccess, BorderLayout.NORTH);
        JLabel completionHint = new JLabel("Errors and object/array results keep execution paused.");
        completionHint.setForeground(ThemeColors.secondaryText());
        this.completionField.add(completionHint, BorderLayout.CENTER);
        this.continueOnSuccess.setToolTipText("Numbers, text, booleans, characters, null and no return value allow execution to resume.");
        addField(7, new JLabel(), this.completionField);

        constraints.gridy = 8;
        constraints.weighty = 1;
        constraints.insets = new Insets(0, 0, 0, 0);
        this.details.add(Box.createVerticalGlue(), constraints);
        showSelection();
    }

    private void addField(int row, JLabel label, Component field) {
        GridBagLayout layout = (GridBagLayout) this.details.getLayout();
        if (layout.columnWidths == null) layout.columnWidths = new int[2];
        layout.columnWidths[0] = Math.max(layout.columnWidths[0], label.getPreferredSize().width + UiMetrics.FORM_LABEL_GAP);
        GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.gridx = 0;
        labelConstraints.gridy = row;
        labelConstraints.anchor = GridBagConstraints.NORTHWEST;
        labelConstraints.insets = new Insets(4, 0, UiMetrics.FORM_ROW_GAP, UiMetrics.FORM_LABEL_GAP);
        this.details.add(label, labelConstraints);

        GridBagConstraints fieldConstraints = new GridBagConstraints();
        fieldConstraints.gridx = 1;
        fieldConstraints.gridy = row;
        fieldConstraints.weightx = 1;
        fieldConstraints.fill = GridBagConstraints.HORIZONTAL;
        fieldConstraints.insets = new Insets(0, 0, UiMetrics.FORM_ROW_GAP, 0);
        this.details.add(field, fieldConstraints);
    }

    private void reload() {
        if (disposed) return;
        this.loading = true;
        try {
            this.model.clear();
            for (DebuggerSessionController.BreakpointEntry entry : this.controller.breakpointEntries()) {
                this.model.addElement(entry);
            }
            selectEditingBreakpoint();
            if (this.list.getSelectedIndex() < 0 && !this.model.isEmpty()) {
                this.list.setSelectedIndex(0);
            }
        } finally {
            this.loading = false;
        }
        showSelection();
    }

    private void selectEditingBreakpoint() {
        if (this.editingBreakpoint == null) return;
        for (int index = 0; index < this.model.size(); index++) {
            var entry = this.model.get(index);
            if (this.editingBreakpoint.equals(new BreakpointKey(entry.sourceUri(), entry.breakpoint().line()))) {
                this.list.setSelectedIndex(index);
                return;
            }
        }
    }

    private void showSelection() {
        DebuggerSessionController.BreakpointEntry entry = this.list.getSelectedValue();
        this.loading = true;
        try {
            boolean present = entry != null;
            this.enabled.setEnabled(present);
            this.condition.setEnabled(present);
            this.hitCount.setEnabled(present);
            this.actionKind.setEnabled(present);
            this.actionSource.setEnabled(present);
            this.actionScript.setEnabled(present && !scriptsLoading);
            this.refreshScripts.setEnabled(present && !scriptsLoading);
            this.continueOnSuccess.setEnabled(present);
            this.navigate.setEnabled(present);
            this.remove.setEnabled(present);
            this.toggle.setEnabled(present);
            this.copy.setEnabled(present);
            if (present) {
                this.enabled.setSelected(entry.breakpoint().state() != DebuggerSessionController.BreakpointState.DISABLED);
                this.toggle.putValue(Action.NAME, this.enabled.isSelected() ? "Disable" : "Enable");
                this.toggle.putValue(Action.SMALL_ICON, this.enabled.isSelected() ? Icons.BREAKPOINT_DISABLED : Icons.BREAKPOINT);
                this.state.setText(stateText(entry.breakpoint()));
                if (this.dirty && Objects.equals(this.editingBreakpoint,
                        new BreakpointKey(entry.sourceUri(), entry.breakpoint().line()))) return;
            }
            this.dirty = false;
            clearValidation();
            if (!present) {
                this.editingBreakpoint = null;
                this.title.setText("No breakpoints");
                this.state.setText("Add one by clicking an executable source line number.");
                this.enabled.setSelected(false);
                this.condition.setText("");
                this.hitCount.setText("");
                this.actionKind.setSelectedIndex(0);
                this.actionSource.setText("");
                this.continueOnSuccess.setSelected(false);
                this.currentCompletion = null;
                this.currentActionCompletion = null;
                this.actionCompletion.setCompletionProvider(null);
                this.actionSource.setSemanticTokenProvider(null);
                this.actionScript.setSelectedItem(null);
                this.conditionCompletion.setCompletionProvider(null);
                this.condition.setSemanticTokenProvider(null);
                return;
            }
            DebuggerSessionController.Breakpoint breakpoint = entry.breakpoint();
            this.editingBreakpoint = new BreakpointKey(entry.sourceUri(), breakpoint.line());
            this.title.setText(simpleName(entry.binaryName()) + ":" + breakpoint.line()
                    + (breakpoint.request().isMethodEntry() ? "  Method breakpoint" : "  Line breakpoint"));
            this.state.setText(stateText(breakpoint));
            this.enabled.setSelected(breakpoint.state() != DebuggerSessionController.BreakpointState.DISABLED);
            this.condition.setText(Objects.requireNonNullElse(breakpoint.request().condition(), ""));
            this.hitCount.setText(Objects.requireNonNullElse(breakpoint.request().hitCondition(), ""));
            var action = breakpoint.request().action();
            this.currentCompletion = completionProvider(entry, false);
            this.currentActionCompletion = completionProvider(entry, true);
            this.actionKind.setSelectedIndex(action == null ? 0 : action.script() == null ? 1 : 2);
            this.actionSource.setText(action != null && action.script() == null ? action.source() : "");
            String selectedScript = action == null ? null : action.script();
            if (selectedScript != null && ((DefaultComboBoxModel<String>) actionScript.getModel()).getIndexOf(selectedScript) < 0) actionScript.addItem(selectedScript);
            this.actionScript.setSelectedItem(selectedScript);
            updateActionFields();
            this.continueOnSuccess.setSelected(action != null && action.continueOnSuccess());
            ExpressionCompletionSupport.CompletionProvider provider = currentCompletion;
            this.conditionCompletion.setCompletionProvider(provider);
            this.condition.setSemanticTokenProvider(expression ->
                    ExpressionCompletionSemantics.tokens(expression, provider));
        } finally {
            this.loading = false;
        }
    }

    private void toggleSelected() {
        DebuggerSessionController.BreakpointEntry entry = this.list.getSelectedValue();
        if (entry != null) {
            setSelectedEnabled(entry.breakpoint().state() == DebuggerSessionController.BreakpointState.DISABLED);
        }
    }

    private void setSelectedEnabled(boolean enabled) {
        DebuggerSessionController.BreakpointEntry entry = this.list.getSelectedValue();
        if (entry != null) {
            this.controller.setBreakpointEnabled(entry.sourceUri(), entry.breakpoint().line(), enabled);
        }
    }

    private boolean saveEditingBreakpoint() {
        if (this.loading) {
            return false;
        }
        if (!this.dirty) return true;
        clearValidation();
        BreakpointKey key = this.editingBreakpoint;
        if (key == null) {
            return true;
        }
        DebuggerSessionController.Breakpoint breakpoint = this.controller.breakpoint(key.sourceUri(), key.line());
        if (breakpoint == null) {
            return true;
        }
        String hitCondition = this.hitCount.getText().trim();
        if (!hitCondition.isEmpty()) {
            try {
                if (Integer.parseInt(hitCondition) < 1) {
                    return invalid(this.hitCountError, "Hit number must be a positive integer");
                }
            } catch (NumberFormatException ignored) {
                return invalid(this.hitCountError, "Hit number must be a positive integer");
            }
        }
        int actionKind = this.actionKind.getSelectedIndex();
        String source = actionText();
        if (actionKind != 0 && source.isBlank()) {
            return invalid(this.actionError, actionKind == 1 ? "Enter Java action source" : "Choose a saved script");
        }
        if (actionKind == 2) {
            if (scriptsLoading) return invalid(actionError, "Scripts are still loading");
            if (scriptsError != null) {
                invalid(actionError, "Could not read scripts. Refresh to retry.");
                actionError.setToolTipText(scriptsError);
                return false;
            }
            if (!scriptNames.contains(source)) return invalid(actionError, "Script is unavailable: " + source);
        }
        DebugEngine.BreakpointAction action = actionKind == 0 ? null : new DebugEngine.BreakpointAction(
                actionKind == 1 ? source : null, actionKind == 2 ? source : null, this.continueOnSuccess.isSelected());
        this.dirty = false;
        this.controller.configureBreakpoint(key.sourceUri(),
                breakpoint.request().withConditions(this.condition.getText(), hitCondition).withAction(action));
        return true;
    }

    private void clearValidation() {
        this.hitCountError.setVisible(false);
        this.actionError.setVisible(false);
        this.details.revalidate();
    }

    private boolean invalid(JLabel label, String message) {
        label.setText(message);
        label.setToolTipText(message);
        label.setVisible(true);
        this.details.revalidate();
        return false;
    }

    private void navigateSelected() {
        DebuggerSessionController.BreakpointEntry entry = this.list.getSelectedValue();
        if (entry != null) {
            this.navigation.accept(new NavigationTarget.RuntimeLine(
                    entry.binaryName(),
                    entry.breakpoint().line()
            ));
        }
    }

    private void removeSelected() {
        DebuggerSessionController.BreakpointEntry entry = this.list.getSelectedValue();
        if (entry != null) {
            this.controller.removeBreakpoint(entry.sourceUri(), entry.breakpoint().line());
        }
    }

    private static String simpleName(String binaryName) {
        int separator = Math.max(binaryName.lastIndexOf('.'), binaryName.lastIndexOf('$'));
        return separator < 0 ? binaryName : binaryName.substring(separator + 1);
    }

    private static String stateText(DebuggerSessionController.Breakpoint breakpoint) {
        if (!breakpoint.detail().isBlank()) {
            return breakpoint.detail();
        }
        return switch (breakpoint.state()) {
            case DISABLED -> "Disabled";
            case UNBOUND -> "Not bound to the current debugger session";
            case PENDING -> "Waiting for the runtime to bind this breakpoint";
            case BOUND -> breakpoint.resolvedLine() > 0
                    ? "Bound to runtime line " + breakpoint.resolvedLine()
                    : "Bound";
            case INVALID -> "No executable bytecode is mapped to this source line";
        };
    }

    private ExpressionCompletionSupport.CompletionProvider completionProvider(
            DebuggerSessionController.BreakpointEntry entry, boolean fragment
    ) {
        return (text, caret, explicit) -> CompletableFuture.supplyAsync(() -> {
            DebugEngine.Source source = this.controller.source(entry.sourceUri());
            if (source == null) {
                return List.of();
            }
            String key = entry.sourceUri().getScheme().equalsIgnoreCase("file")
                    ? Path.of(entry.sourceUri()).toString()
                    : entry.sourceUri().toString();
            var snapshot = cache.getSnapshot(key);
            var unit = snapshot != null && snapshot.contents().equals(source.contents())
                    ? snapshot.unit() : JavaAst.parse(entry.binaryName(), source.contents());
            int contextOffset = sourceOffset(source.contents(), entry.breakpoint().line());
            if (fragment && !text.isBlank()) {
                // Analyze the action in a nested block at the breakpoint, retaining surrounding locals
                // and parameters while also exposing declarations earlier in the action itself.
                for (ASTNode node = NodeFinder.perform(unit, contextOffset, 0); node != null; node = node.getParent()) {
                    if (node instanceof MethodDeclaration method && method.getBody() != null) {
                        contextOffset = Math.max(contextOffset, method.getBody().getStartPosition() + 1);
                        break;
                    }
                }
                String inserted = "{\n" + text + "\n;}\n";
                unit = JavaAst.parse(entry.binaryName(), source.contents().substring(0, contextOffset)
                        + inserted + source.contents().substring(contextOffset));
                contextOffset += 2 + caret;
            }
            return ExpressionScopeAnalyzer.complete(unit, contextOffset, text, caret);
        });
    }

    private static int sourceOffset(String source, int displayedLine) {
        int currentLine = 1;
        int offset = 0;
        while (currentLine < displayedLine && offset < source.length()) {
            if (source.charAt(offset++) == '\n') {
                currentLine++;
            }
        }
        while (offset < source.length() && Character.isWhitespace(source.charAt(offset))
                && source.charAt(offset) != '\n') {
            offset++;
        }
        return offset;
    }

    @Override
    public void dispose() {
        this.controller.removeListener(this.listener);
        this.disposed = true;
        this.scriptLoadRevision++;
        this.conditionCompletion.close();
        this.actionCompletion.close();
        this.condition.setSemanticTokenProvider(null);
        this.actionSource.setSemanticTokenProvider(null);
        super.dispose();
    }

    private final class BreakpointRenderer implements ListCellRenderer<DebuggerSessionController.BreakpointEntry> {
        private final JPanel row = new JPanel(new BorderLayout(4, 0));
        private final JCheckBox checkBox = new JCheckBox();
        private final PrimarySecondaryLabel label = new PrimarySecondaryLabel();

        private BreakpointRenderer() {
            this.row.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 6));
            this.checkBox.setOpaque(false);
            this.checkBox.setFocusable(false);
            this.row.add(this.checkBox, BorderLayout.WEST);
            this.row.add(this.label, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(
                JList<? extends DebuggerSessionController.BreakpointEntry> list,
                DebuggerSessionController.BreakpointEntry value,
                int index,
                boolean selected,
                boolean focused
        ) {
            DebuggerSessionController.Breakpoint breakpoint = value.breakpoint();
            this.checkBox.setSelected(breakpoint.state() != DebuggerSessionController.BreakpointState.DISABLED);
            String primary = simpleName(value.binaryName()) + ":" + breakpoint.line();
            String secondary = value.binaryName().contains(".")
                    ? value.binaryName().substring(0, value.binaryName().lastIndexOf('.'))
                    : "";
            this.label.configure(
                    new PrimarySecondaryText(primary, secondary),
                    BreakpointGutterMarkers.iconFor(breakpoint, controller.breakpointsMuted()),
                    list.getFont(),
                    selected,
                    list.getSelectionForeground(),
                    null,
                    list
            );
            this.row.setOpaque(selected);
            if (selected) {
                this.row.setBackground(list.getSelectionBackground());
            }
            this.label.setToolTipText(stateText(breakpoint));
            return this.row;
        }
    }

    private record BreakpointKey(URI sourceUri, int line) {
    }
}
