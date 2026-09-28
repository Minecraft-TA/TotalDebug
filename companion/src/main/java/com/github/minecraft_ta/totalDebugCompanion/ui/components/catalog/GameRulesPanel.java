package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.pack.GameRuleEdits;
import com.github.minecraft_ta.totalDebugCompanion.ui.ContextMenus;
import com.github.minecraft_ta.totalDebugCompanion.ui.Tooltip;
import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.BrowserBody;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.Tables;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;

import javax.swing.AbstractAction;
import javax.swing.AbstractCellEditor;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.JTextField;
import javax.swing.ToolTipManager;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellEditor;
import javax.swing.text.JTextComponent;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.EventObject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.regex.Pattern;

/**
 * The world's game rules with their values, filtered by rule or value. With a {@link Setter}, a value is edited in place
 * (Enter, F2 or a double-click; Space turns a true or false rule over) and set at once, as {@code /gamerule} does; a
 * refused value says why under the bar.
 */
final class GameRulesPanel extends JPanel {
    /** Sets a rule; completes once it is set, or fails saying why. */
    interface Setter {
        CompletableFuture<?> set(String name, String value);
    }

    private static final Pattern NUMBER = Pattern.compile("-?\\d+");

    /** A rule with its value and what kind of literal the value is, which the game's commands take as such. */
    private record Rule(String name, String value, ConfigSettingsTable.ValueKind kind) {
        static Rule of(String name, String value) {
            ConfigSettingsTable.ValueKind kind = value.equals("true") || value.equals("false") ? ConfigSettingsTable.ValueKind.BOOLEAN
                    : NUMBER.matcher(value).matches() ? ConfigSettingsTable.ValueKind.NUMBER : ConfigSettingsTable.ValueKind.STRING;
            return new Rule(name, value, kind);
        }

        /** The value as a literal: a string in quotes, so an empty one stays visible. */
        String literal() {
            return kind() == ConfigSettingsTable.ValueKind.STRING ? '"' + this.value + '"' : this.value;
        }
    }

    private final RulesModel model = new RulesModel();
    private final JTable table = new JTable(this.model) {
        @Override
        public String getToolTipText(MouseEvent event) {
            int row = rowAtPoint(event.getPoint());
            if (row < 0) return null;
            Rule rule = GameRulesPanel.this.model.shown.get(row);
            return Tooltip.of(rule.name()).fact("Value", rule.literal(), ConfigSettingsTable.color(rule.kind())).html();
        }
    };
    private final BrowserBody body;
    private Setter setter;
    /** Rules being set now, whose rows wait for the answer. */
    private final Set<String> setting = new HashSet<>();
    /** The value the game named last for a rule being set, which a refused set shows instead of the one before. */
    private final Map<String, String> named = new HashMap<>();
    /** The world whose rules are shown; the sets of another world's rules no longer concern the rows. */
    private Object world;
    private int worldShown;
    /** Why the world's rules cannot be shown, in place of them, or empty. */
    private String unavailable = "";

    GameRulesPanel() {
        super(new BorderLayout());
        this.table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        Tables.configure(this.table);
        this.table.setDefaultRenderer(Object.class, new RuleRenderer());
        ToolTipManager.sharedInstance().registerComponent(this.table);
        ContextMenus.installTable(this.table, this::menu);
        this.table.getColumnModel().getColumn(0).setPreferredWidth(600);
        this.table.getColumnModel().getColumn(1).setPreferredWidth(400);
        this.body = new BrowserBody("Filter by rule or value", BrowserBody.scroll(this.table), this.table, this::applyFilter);
        add(this.body, BorderLayout.CENTER);
        this.table.setDefaultEditor(Object.class, new ValueEditor());
        // Enter and F2 edit the value of the selected rule, wherever the row has focus.
        for (String key : new String[]{"ENTER", "F2"}) {
            this.table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), "editRule");
        }
        this.table.getActionMap().put("editRule", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                int row = GameRulesPanel.this.table.getSelectedRow();
                if (row >= 0 && GameRulesPanel.this.table.editCellAt(row, 1)) {
                    Component editor = GameRulesPanel.this.table.getEditorComponent();
                    if (editor != null) editor.requestFocusInWindow();
                }
            }
        });
        this.table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("SPACE"), "toggleRule");
        this.table.getActionMap().put("toggleRule", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                List<Rule> selected = selectedRules();
                if (selected.size() == 1 && selected.getFirst().kind() == ConfigSettingsTable.ValueKind.BOOLEAN) toggle(selected.getFirst());
            }
        });
    }

    /** Lets the values be edited, set by {@code setter}. */
    void setSetter(Setter setter) {
        this.setter = setter;
    }

    /** Sets a true or false rule to the other value. */
    private void toggle(Rule rule) {
        set(rule, rule.value().equals("true") ? "false" : "true");
    }

    /** Sets {@code rule} to {@code value}, shown at once and put back when the game refuses it. */
    private void set(Rule rule, String value) {
        if (this.setter == null || this.setting.contains(rule.name()) || value.equals(rule.value())) return;
        String problem = GameRuleEdits.problem(rule.value(), value);
        if (problem != null) {
            this.body.showNotice("Not set: " + rule.name() + ": " + problem);
            return;
        }
        this.body.showNotice("");
        this.setting.add(rule.name());
        replace(rule.name(), value);
        int shown = this.worldShown;
        this.setter.set(rule.name(), value).whenComplete((ignored, failure) -> SwingUtilities.invokeLater(() -> {
            // The rows show another world now, whose rules the set did not touch.
            if (shown != this.worldShown) return;
            this.setting.remove(rule.name());
            String latest = this.named.remove(rule.name());
            if (failure != null) {
                Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
                // The value the game named meanwhile, such as one a command set, or the one before.
                replace(rule.name(), latest != null ? latest : rule.value());
                this.body.showNotice("Not set: " + rule.name() + ": " + cause.getMessage());
            }
        }));
    }

    /** Shows why the rules of {@code world} cannot be shown, in place of them. */
    void showUnavailable(Object world, String reason) {
        showWorld(world);
        this.unavailable = reason;
        this.model.all = List.of();
        applyFilter();
    }

    /** Starts afresh for another world, whatever was being set in the last one. */
    private void showWorld(Object world) {
        if (this.table.isEditing()) this.table.getCellEditor().cancelCellEditing();
        if (Objects.equals(world, this.world)) return;
        this.world = world;
        this.worldShown++;
        this.setting.clear();
        this.named.clear();
        this.body.showNotice("");
    }

    /** Shows {@code value} as {@code name}'s value, keeping the selected rules selected while the filter still shows them. */
    private void replace(String name, String value) {
        List<Rule> all = new ArrayList<>();
        for (Rule rule : this.model.all) all.add(rule.name().equals(name) ? Rule.of(name, value) : rule);
        this.model.all = List.copyOf(all);
        Set<String> selected = new HashSet<>();
        for (Rule rule : selectedRules()) selected.add(rule.name());
        applyFilter();
        for (int row = 0; row < this.model.shown.size(); row++) {
            if (selected.contains(this.model.shown.get(row).name())) this.table.addRowSelectionInterval(row, row);
        }
    }

    /**
     * Shows the rules of {@code world}, keeping the selected ones selected; a rule being set keeps the value it is being
     * set to. Another world's rules start afresh, whatever was being set in the last one.
     */
    void setRules(Object world, Map<String, String> rules) {
        showWorld(world);
        this.unavailable = "";
        Set<String> selected = new HashSet<>();
        for (Rule rule : selectedRules()) selected.add(rule.name());
        List<Rule> all = new ArrayList<>();
        rules.forEach((name, value) -> {
            Rule shown = this.model.all.stream().filter(rule -> rule.name().equals(name)).findFirst().orElse(null);
            boolean waiting = this.setting.contains(name) && shown != null;
            if (waiting) this.named.put(name, value);
            all.add(waiting ? shown : Rule.of(name, value));
        });
        this.model.all = List.copyOf(all);
        applyFilter();
        for (int row = 0; row < this.model.shown.size(); row++) {
            if (selected.contains(this.model.shown.get(row).name())) this.table.addRowSelectionInterval(row, row);
        }
    }

    private void applyFilter() {
        String query = this.body.query().toLowerCase(Locale.ROOT);
        List<Rule> shown = new ArrayList<>();
        for (Rule rule : this.model.all) {
            if (query.isEmpty() || rule.name().toLowerCase(Locale.ROOT).contains(query)
                    || rule.value().toLowerCase(Locale.ROOT).contains(query)) {
                shown.add(rule);
            }
        }
        this.model.shown = List.copyOf(shown);
        this.model.fireTableDataChanged();
        boolean empty = shown.isEmpty();
        if (empty) this.body.showMessage(!this.unavailable.isEmpty() ? this.unavailable
                : this.model.all.isEmpty() ? "The world has no game rules saved." : "No game rule matches the filter.");
        else this.body.showContent();
    }

    private List<Rule> selectedRules() {
        List<Rule> selected = new ArrayList<>();
        for (int row : this.table.getSelectedRows()) selected.add(this.model.shown.get(row));
        return selected;
    }

    private JPopupMenu menu(int viewRow) {
        List<Rule> selected = selectedRules();
        if (viewRow < 0 || selected.isEmpty()) return null;
        JPopupMenu menu = new JPopupMenu();
        if (selected.size() == 1) {
            Rule rule = selected.getFirst();
            if (this.setter != null && rule.kind() == ConfigSettingsTable.ValueKind.BOOLEAN) {
                menu.add(ContextMenus.action("Set to " + (rule.value().equals("true") ? "false" : "true"), null, "SPACE", () -> toggle(rule)));
                menu.addSeparator();
            }
            menu.add(ContextMenus.defaultCopy(ContextMenus.copyAction("Copy Name", rule.name())));
            menu.add(ContextMenus.copyAction("Copy Value", rule.value()));
            menu.add(ContextMenus.copyAction("Copy Command", command(rule)));
            return menu;
        }
        List<String> names = new ArrayList<>();
        List<String> commands = new ArrayList<>();
        for (Rule rule : selected) {
            names.add(rule.name());
            commands.add(command(rule));
        }
        menu.add(ContextMenus.defaultCopy(ContextMenus.copyAction("Copy " + selected.size() + " Names", String.join("\n", names))));
        menu.add(ContextMenus.copyAction("Copy " + selected.size() + " Commands", String.join("\n", commands)));
        return menu;
    }

    /** The command that sets the rule to its saved value, such as {@code /gamerule keepInventory true}. */
    private static String command(Rule rule) {
        return "/gamerule " + rule.name() + " " + rule.value();
    }

    /**
     * Edits a value in place: a text field, or true and false to choose from for such a rule. A value the rule does not
     * take stays in the field, marked, with the reason under the bar.
     */
    private final class ValueEditor extends AbstractCellEditor implements TableCellEditor {
        private final JTextField field = new JTextField();
        private final JComboBox<String> choices = new JComboBox<>(new String[]{"true", "false"});
        private JComponent active = this.field;
        private Rule rule;
        private boolean configuring;

        ValueEditor() {
            this.field.setBorder(UiMetrics.cellPadding());
            this.field.addActionListener(event -> stopCellEditing());
            this.choices.putClientProperty("JComboBox.isTableCellEditor", Boolean.TRUE);
            this.choices.addActionListener(event -> {
                if (!this.configuring) stopCellEditing();
            });
        }

        @Override
        public boolean isCellEditable(EventObject event) {
            return !(event instanceof MouseEvent mouse) || mouse.getClickCount() >= 2;
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean selected, int row, int column) {
            this.rule = GameRulesPanel.this.model.shown.get(row);
            this.field.putClientProperty("JComponent.outline", null);
            if (this.rule.kind() == ConfigSettingsTable.ValueKind.BOOLEAN) {
                this.configuring = true;
                try {
                    this.choices.setFont(table.getFont());
                    this.choices.setForeground(ConfigSettingsTable.color(this.rule.kind()));
                    this.choices.setSelectedItem(this.rule.value());
                } finally {
                    this.configuring = false;
                }
                this.active = this.choices;
                SwingUtilities.invokeLater(() -> {
                    if (this.choices.isShowing()) this.choices.showPopup();
                });
            } else {
                this.field.setFont(table.getFont());
                this.field.setForeground(ConfigSettingsTable.color(this.rule.kind()));
                this.field.setText(this.rule.value());
                this.field.selectAll();
                this.active = this.field;
            }
            return this.active;
        }

        @Override
        public Object getCellEditorValue() {
            return this.active == this.choices ? String.valueOf(this.choices.getSelectedItem()) : this.field.getText().strip();
        }

        @Override
        public boolean stopCellEditing() {
            Rule editing = this.rule;
            String value = (String) getCellEditorValue();
            String problem = value.equals(editing.value()) ? null : GameRuleEdits.problem(editing.value(), value);
            if (problem != null) {
                this.field.putClientProperty("JComponent.outline", "error");
                GameRulesPanel.this.body.showNotice("Not set: " + editing.name() + ": " + problem);
                return false;
            }
            fireEditingStopped();
            set(editing, value);
            return true;
        }

        @Override
        public void cancelCellEditing() {
            GameRulesPanel.this.body.showNotice("");
            super.cancelCellEditing();
        }
    }

    /** Starts editing or sets the value of the rule at {@code row} as typed, for tests. */
    void edit(int row, String value) {
        this.model.setValueAt(value, row, 1);
    }

    JTable table() {
        return this.table;
    }

    List<String> shownValues() {
        return this.model.shown.stream().map(rule -> rule.name() + "=" + rule.value()).toList();
    }

    String notice() {
        return this.body.noticeText();
    }

    int rowCount() {
        return this.model.getRowCount();
    }

    JTextComponent filterField() {
        return this.body.filter();
    }

    private final class RulesModel extends AbstractTableModel {
        private List<Rule> all = List.of();
        private List<Rule> shown = List.of();

        @Override
        public int getRowCount() {
            return this.shown.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public String getColumnName(int column) {
            return column == 0 ? "Rule" : "Value";
        }

        @Override
        public Object getValueAt(int row, int column) {
            Rule rule = this.shown.get(row);
            return column == 0 ? rule.name() : rule.literal();
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column == 1 && GameRulesPanel.this.setter != null && !GameRulesPanel.this.setting.contains(this.shown.get(row).name());
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            // An edit starts from the literal, so a string rule's quotes are taken off again.
            String text = String.valueOf(value).strip();
            Rule rule = this.shown.get(row);
            if (rule.kind() == ConfigSettingsTable.ValueKind.STRING && text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
                text = text.substring(1, text.length() - 1);
            }
            set(rule, text);
        }
    }

    /** Rule names in regular text, values in the editor's color for their kind of literal. */
    private final class RuleRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focused,
                                                       int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, false, row, column);
            setBorder(UiMetrics.cellPadding());
            if (!selected) {
                setForeground(column == 0 ? ThemeColors.text()
                        : ConfigSettingsTable.color(GameRulesPanel.this.model.shown.get(row).kind()));
            }
            return this;
        }
    }
}
