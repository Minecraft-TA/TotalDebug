package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.ui.ContextMenus;
import com.github.minecraft_ta.totalDebugCompanion.ui.Tooltip;
import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.BrowserBody;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.Tables;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;

import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.ToolTipManager;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.text.JTextComponent;
import java.awt.BorderLayout;
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
        if (empty) this.body.showMessage(this.model.all.isEmpty() ? "The world has no game rules saved." : "No game rule matches the filter.");
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
        return this.body.filter();
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
