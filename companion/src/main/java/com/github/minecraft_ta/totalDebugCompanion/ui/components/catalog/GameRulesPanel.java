package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.ui.ContextMenus;
import com.github.minecraft_ta.totalDebugCompanion.ui.Tooltip;
import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconTextField;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.Tables;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.TypeToFilter;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.ToolTipManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.text.JTextComponent;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** The world's game rules with their saved values, filtered by rule or value. */
final class GameRulesPanel extends JPanel {
    private static final String TABLE_CARD = "table";
    private static final String MESSAGE_CARD = "message";
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

    private final FlatIconTextField filter = new FlatIconTextField(Icons.SEARCH_ICON);
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
    private final JLabel message = new JLabel();
    private final JPanel cards = new JPanel(new CardLayout());

    GameRulesPanel() {
        super(new BorderLayout());
        this.filter.putClientProperty("JTextField.placeholderText", "Filter by rule or value");
        this.filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { applyFilter(); }
            @Override public void removeUpdate(DocumentEvent event) { applyFilter(); }
            @Override public void changedUpdate(DocumentEvent event) { applyFilter(); }
        });
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(UiMetrics.barPadding());
        bar.add(this.filter, BorderLayout.CENTER);
        add(bar, BorderLayout.NORTH);

        this.table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        Tables.configure(this.table);
        this.table.setDefaultRenderer(Object.class, new RuleRenderer());
        ToolTipManager.sharedInstance().registerComponent(this.table);
        ContextMenus.installTable(this.table, this::menu);
        this.table.getColumnModel().getColumn(0).setPreferredWidth(600);
        this.table.getColumnModel().getColumn(1).setPreferredWidth(400);
        JScrollPane scroll = new JScrollPane(this.table);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        this.cards.add(scroll, TABLE_CARD);
        this.message.setVerticalAlignment(JLabel.TOP);
        this.message.setBorder(UiMetrics.messagePadding());
        this.cards.add(this.message, MESSAGE_CARD);
        add(this.cards, BorderLayout.CENTER);
        TypeToFilter.install(this.table, this.filter);
    }

    /** Shows the rules, keeping the selected ones selected. */
    void setRules(Map<String, String> rules) {
        Set<String> selected = new HashSet<>();
        for (Rule rule : selectedRules()) selected.add(rule.name());
        List<Rule> all = new ArrayList<>();
        rules.forEach((name, value) -> all.add(Rule.of(name, value)));
        this.model.all = List.copyOf(all);
        applyFilter();
        for (int row = 0; row < this.model.shown.size(); row++) {
            if (selected.contains(this.model.shown.get(row).name())) this.table.addRowSelectionInterval(row, row);
        }
    }

    private void applyFilter() {
        String query = this.filter.getText().strip().toLowerCase(Locale.ROOT);
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
        if (empty) this.message.setText(this.model.all.isEmpty() ? "The world has no game rules saved." : "No game rule matches the filter.");
        ((CardLayout) this.cards.getLayout()).show(this.cards, empty ? MESSAGE_CARD : TABLE_CARD);
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

    int rowCount() {
        return this.model.getRowCount();
    }

    JTextComponent filterField() {
        return this.filter;
    }

    private static final class RulesModel extends AbstractTableModel {
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
