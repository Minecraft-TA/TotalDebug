package com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger;

import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerSessionController;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.ASTCache;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaAst;
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

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
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
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.KeyEvent;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Modeless editor for every breakpoint in the active runtime. */
public final class BreakpointsWindow extends JDialog {
    private static final Dimension DEFAULT_SIZE = new Dimension(940, 680);

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
    private final javax.swing.JComboBox<String> actionKind = new javax.swing.JComboBox<>(new String[]{"None", "Java", "Saved script"});
    private final JavaExpressionField actionSource = new JavaExpressionField();
    private final JCheckBox continueOnSuccess = new JCheckBox("Continue after a successful scalar result");
    private final JButton navigate = new JButton("Navigate");
    private final JButton remove = new JButton("Remove");
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
    };
    private boolean loading;
    private BreakpointKey editingBreakpoint;

    public BreakpointsWindow(
            Window owner,
            DebuggerSessionController controller,
            Consumer<NavigationTarget> navigation
    ) {
        super(owner, "Breakpoints", ModalityType.MODELESS);
        this.controller = Objects.requireNonNull(controller, "controller");
        this.navigation = Objects.requireNonNull(navigation, "navigation");

        this.list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        this.list.setFixedCellHeight(UiMetrics.TREE_ROW_HEIGHT);
        this.list.setCellRenderer(new BreakpointRenderer());
        SpeedSearch.install(this.list, entry -> entry.binaryName() + ' '
                + simpleName(entry.binaryName()) + ' ' + entry.breakpoint().line() + ' '
                + entry.breakpoint().request().condition());
        this.list.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !this.loading) {
                saveEditingBreakpoint();
                showSelection();
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
                if (SwingUtilities.isLeftMouseButton(event) && event.getX() < 28) {
                    toggleSelected();
                } else if (SwingUtilities.isLeftMouseButton(event) && event.getClickCount() == 2) {
                    navigateSelected();
                }
            }
        });
        this.list.getInputMap(javax.swing.JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "navigateBreakpoint");
        this.list.getActionMap().put("navigateBreakpoint", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                navigateSelected();
            }
        });

        this.title.setFont(this.title.getFont().deriveFont(Font.BOLD));
        this.condition.setPlaceholder("Optional Java condition");
        this.hitCount.putClientProperty("JTextField.placeholderText", "Optional positive integer");
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
        this.actionKind.addActionListener(event -> {
            this.actionSource.setMultiline(this.actionKind.getSelectedIndex() == 1);
            this.actionSource.setPlaceholder(this.actionKind.getSelectedIndex() == 2 ? "Script path relative to scripts directory" : "Java expression or statements");
        });
        this.continueOnSuccess.addActionListener(event -> saveEditingBreakpoint());
        this.navigate.addActionListener(event -> navigateSelected());
        this.remove.addActionListener(event -> removeSelected());

        buildDetails();
        JScrollPane breakpointList = new JScrollPane(this.list);
        breakpointList.setBorder(DynamicMatteBorder.separatorRule(0, 0, 0, 1));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, breakpointList, this.details);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setDividerSize(1);
        split.setResizeWeight(0.34);
        setContentPane(split);
        setDefaultCloseOperation(HIDE_ON_CLOSE);
        setMinimumSize(new Dimension(680, 400));
        setSize(DEFAULT_SIZE);
        setLocationRelativeTo(owner);

        this.controller.addListener(this.listener);
        reload();
    }

    public void showWindow() {
        if (!isVisible()) {
            setVisible(true);
        }
        if (!isActive()) {
            toFront();
        }
    }

    private void buildDetails() {
        this.details.setBorder(BorderFactory.createEmptyBorder(18, 18, 12, 18));
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

        addField(3, "Condition:", this.condition.component());
        addField(4, "Hit count:", this.hitCount);
        addField(5, "Action:", this.actionKind);
        addField(6, "Source or script:", this.actionSource.component());
        addField(7, "On success:", this.continueOnSuccess);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING, 8, 0));
        buttons.setOpaque(false);
        buttons.add(this.navigate);
        buttons.add(this.remove);
        JButton done = new JButton("Done");
        done.addActionListener(event -> {
            if (saveEditingBreakpoint()) setVisible(false);
        });
        buttons.add(done);
        constraints.gridy = 8;
        constraints.weighty = 1;
        constraints.anchor = GridBagConstraints.SOUTH;
        constraints.insets = new Insets(20, 0, 0, 0);
        this.details.add(buttons, constraints);
        showSelection();
    }

    private void addField(int row, String label, Component field) {
        GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.gridx = 0;
        labelConstraints.gridy = row;
        labelConstraints.anchor = GridBagConstraints.NORTHWEST;
        labelConstraints.insets = new Insets(4, 0, 12, 12);
        this.details.add(new JLabel(label), labelConstraints);

        GridBagConstraints fieldConstraints = new GridBagConstraints();
        fieldConstraints.gridx = 1;
        fieldConstraints.gridy = row;
        fieldConstraints.weightx = 1;
        fieldConstraints.fill = GridBagConstraints.HORIZONTAL;
        fieldConstraints.insets = new Insets(0, 0, 12, 0);
        this.details.add(field, fieldConstraints);
    }

    private void reload() {
        DebuggerSessionController.BreakpointEntry selected = this.list.getSelectedValue();
        URI sourceUri = selected == null ? null : selected.sourceUri();
        int line = selected == null ? -1 : selected.breakpoint().line();
        this.loading = true;
        try {
            this.model.clear();
            for (DebuggerSessionController.BreakpointEntry entry : this.controller.breakpointEntries()) {
                this.model.addElement(entry);
            }
            for (int index = 0; index < this.model.size(); index++) {
                DebuggerSessionController.BreakpointEntry entry = this.model.get(index);
                if (entry.sourceUri().equals(sourceUri) && entry.breakpoint().line() == line) {
                    this.list.setSelectedIndex(index);
                    break;
                }
            }
            if (this.list.getSelectedIndex() < 0 && !this.model.isEmpty()) {
                this.list.setSelectedIndex(0);
            }
        } finally {
            this.loading = false;
        }
        showSelection();
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
            this.continueOnSuccess.setEnabled(present);
            this.navigate.setEnabled(present);
            this.remove.setEnabled(present);
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
            this.actionKind.setSelectedIndex(action == null ? 0 : action.script() == null ? 1 : 2);
            this.actionSource.setText(action == null ? "" : action.script() == null ? action.source() : action.script());
            this.continueOnSuccess.setSelected(action != null && action.continueOnSuccess());
            ExpressionCompletionSupport.CompletionProvider provider = completionProvider(entry);
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
                    this.state.setText("Hit count must be a positive integer");
                    return false;
                }
            } catch (NumberFormatException ignored) {
                this.state.setText("Hit count must be a positive integer");
                return false;
            }
        }
        int actionKind = this.actionKind.getSelectedIndex();
        String source = this.actionSource.getText().trim();
        if (actionKind != 0 && source.isBlank()) {
            this.state.setText("Enter action source or a saved script path");
            return false;
        }
        DebugEngine.BreakpointAction action = actionKind == 0 ? null : new DebugEngine.BreakpointAction(
                actionKind == 1 ? source : null, actionKind == 2 ? source : null, this.continueOnSuccess.isSelected());
        this.controller.configureBreakpoint(key.sourceUri(),
                breakpoint.request().withConditions(this.condition.getText(), hitCondition).withAction(action));
        return true;
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
            DebuggerSessionController.BreakpointEntry entry
    ) {
        return (text, caret, explicit) -> java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            DebugEngine.Source source = this.controller.source(entry.sourceUri());
            if (source == null) {
                return List.of();
            }
            String key = entry.sourceUri().getScheme().equalsIgnoreCase("file")
                    ? Path.of(entry.sourceUri()).toString()
                    : entry.sourceUri().toString();
            var unit = ASTCache.getFromCache(key);
            if (unit == null) {
                unit = JavaAst.parse(entry.binaryName(), source.contents());
            }
            int contextOffset = sourceOffset(source.contents(), entry.breakpoint().line());
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
        this.conditionCompletion.close();
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
