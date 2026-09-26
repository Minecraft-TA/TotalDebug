package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigValues;
import com.github.minecraft_ta.totalDebugCompanion.ui.Tooltip;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.EditorPalette;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.UIManager;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A configuration file's settings under their sections. Values are colored by kind like code literals, a value that
 * differs from its default is marked at the row's edge with the default beside it, sections collapse from their
 * chevron, and a row's tooltip holds its description.
 */
final class ConfigSettingsTable extends JTable {
    private static final int CHEVRON_WIDTH = 16;
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?");
    private static final Pattern RANGE = Pattern.compile("(\\S+) ~ (\\S+)");

    /** How a value is colored, the way the code editor colors literals of the same kind. */
    enum ValueKind { NUMBER, BOOLEAN, STRING, CHOICE, LIST, TEXT }

    /** One row: a section heading when {@code setting} is null. */
    record Row(int depth, String path, String name, String comment, PackCatalog.ConfigSetting setting, String value) {
        boolean modified() {
            return this.setting != null && !this.setting.defaultValue().isEmpty() && !this.value.isEmpty()
                    && !this.value.equals(this.setting.defaultValue());
        }

        String accepts() {
            if (this.setting == null) return "";
            return this.setting.allowed().isEmpty() ? readableRange(this.setting.range()) : String.join(", ", this.setting.allowed());
        }

        /** What kind of value the setting holds, from its accepted values, its default, or the value itself. */
        ValueKind kind() {
            if (this.setting == null) return ValueKind.TEXT;
            if (!this.setting.allowed().isEmpty()) return ValueKind.CHOICE;
            String sample = this.setting.defaultValue().isEmpty() ? this.value : this.setting.defaultValue();
            if (sample.startsWith("[")) return ValueKind.LIST;
            if (sample.equals("true") || sample.equals("false")) return ValueKind.BOOLEAN;
            if (NUMBER.matcher(sample).matches()) return ValueKind.NUMBER;
            return ValueKind.STRING;
        }

        boolean matches(String query) {
            return query.isEmpty() || this.path.toLowerCase(Locale.ROOT).contains(query)
                    || this.comment.toLowerCase(Locale.ROOT).contains(query)
                    || this.value.toLowerCase(Locale.ROOT).contains(query);
        }
    }

    private final SettingsModel model = new SettingsModel();

    ConfigSettingsTable() {
        setModel(this.model);
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        setShowGrid(false);
        setFillsViewportHeight(true);
        getTableHeader().setReorderingAllowed(false);
        setDefaultRenderer(Object.class, new SettingRenderer());
        ToolTipManager.sharedInstance().registerComponent(this);
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                int row = rowAtPoint(event.getPoint());
                if (row >= 0 && SwingUtilities.isLeftMouseButton(event) && onChevron(row, event.getPoint())) {
                    toggleSection(row, null);
                }
            }
        });
        for (int key : new int[]{KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, KeyEvent.VK_ENTER}) {
            Boolean expand = key == KeyEvent.VK_ENTER ? null : key == KeyEvent.VK_RIGHT;
            String name = "configSection" + key;
            getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key, 0), name);
            getActionMap().put(name, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent event) {
                    int row = getSelectedRow();
                    if (row >= 0) toggleSection(row, expand);
                }
            });
        }
    }

    /** Shows the rows of a file; whether it has a specification decides if Accepts is shown. */
    void show(List<Row> rows, boolean described) {
        this.model.collapsed.clear();
        this.model.setColumns(described);
        this.model.setRows(rows);
    }

    /** Shows the settings matching {@code query}, and with {@code modifiedOnly} only those that differ from their default. */
    void filter(String query, boolean modifiedOnly) {
        String text = query.strip().toLowerCase(Locale.ROOT);
        this.model.filter(row -> row.matches(text) && (!modifiedOnly || row.modified()), !text.isEmpty() || modifiedOnly);
    }

    boolean isEmpty() {
        return this.model.all.isEmpty();
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        int row = rowAtPoint(event.getPoint());
        return row < 0 ? null : tooltip(this.model.shown.get(row));
    }

    /** Clicking a section selects it for reading; only its chevron collapses it. */
    private boolean onChevron(int viewRow, Point point) {
        Row row = this.model.shown.get(viewRow);
        if (row.setting() != null || columnAtPoint(point) != 0) return false;
        Rectangle cell = getCellRect(viewRow, 0, true);
        int left = cell.x + indent(row.depth());
        return point.x >= left && point.x < left + CHEVRON_WIDTH && point.y < cell.y + getRowHeight();
    }

    /** Collapses or expands a section row; {@code expand} null toggles it. Setting rows are left alone. */
    private void toggleSection(int viewRow, Boolean expand) {
        Row row = this.model.shown.get(viewRow);
        if (row.setting() != null || this.model.filtering) return;
        boolean collapse = expand == null ? !this.model.collapsed.contains(row.path()) : !expand;
        if (collapse) this.model.collapsed.add(row.path());
        else this.model.collapsed.remove(row.path());
        this.model.refilter();
        int index = this.model.shown.indexOf(row);
        if (index >= 0) setRowSelectionInterval(index, index);
    }

    /** Where a row's chevron, or a setting's name, starts inside the name column. */
    private static int indent(int depth) {
        return 6 + depth * 16;
    }

    /**
     * Section headings and settings in specification order. Without a specification the file's own tables and
     * comments describe it; values missing from a file are shown empty.
     */
    static List<Row> rows(PackCatalog.ConfigFile file, ConfigValues values) {
        boolean described = !file.settings().isEmpty();
        List<PackCatalog.ConfigSetting> settings = described ? file.settings()
                : values == null ? List.of() : values.settings();
        Map<String, String> sectionComments = new HashMap<>();
        for (PackCatalog.ConfigSection section : described ? file.sections()
                : values == null ? List.<PackCatalog.ConfigSection>of() : values.sections()) {
            sectionComments.put(section.path(), section.comment());
        }
        List<Row> rows = new ArrayList<>();
        Set<String> opened = new LinkedHashSet<>();
        for (PackCatalog.ConfigSetting setting : settings) {
            String[] parts = setting.path().split("\\.");
            StringBuilder prefix = new StringBuilder();
            for (int depth = 0; depth < parts.length - 1; depth++) {
                if (depth > 0) prefix.append('.');
                prefix.append(parts[depth]);
                String section = prefix.toString();
                if (opened.add(section)) {
                    rows.add(new Row(depth, section, parts[depth], sectionComments.getOrDefault(section, ""), null, ""));
                }
            }
            String value = values == null ? setting.defaultValue() : values.values().getOrDefault(setting.path(), "");
            rows.add(new Row(parts.length - 1, setting.path(), setting.name(), setting.comment(), setting, value));
        }
        return rows;
    }

    /**
     * A NeoForge range in words. Its {@code > 1} means at least 1, and a bound at the type's limit, such as
     * {@code 9223372036854775807}, is no bound at all.
     */
    static String readableRange(String range) {
        if (range.startsWith("> ")) return "at least " + number(range.substring(2));
        if (range.startsWith("< ")) return "at most " + number(range.substring(2));
        Matcher bounds = RANGE.matcher(range);
        if (!bounds.matches()) return range;
        boolean noMinimum = unbounded(bounds.group(1), false);
        boolean noMaximum = unbounded(bounds.group(2), true);
        if (noMinimum && noMaximum) return "";
        if (noMaximum) return "at least " + number(bounds.group(1));
        if (noMinimum) return "at most " + number(bounds.group(2));
        return number(bounds.group(1)) + " to " + number(bounds.group(2));
    }

    private static boolean unbounded(String bound, boolean upper) {
        try {
            double value = Double.parseDouble(bound);
            return upper ? value >= Integer.MAX_VALUE : value <= Integer.MIN_VALUE;
        } catch (NumberFormatException notANumber) {
            return false;
        }
    }

    /** Drops a floating-point value's empty fraction, so {@code 4000000.0} reads as {@code 4000000}. */
    private static String number(String value) {
        return value.endsWith(".0") ? value.substring(0, value.length() - 2) : value;
    }

    /**
     * A row's description: its key, its comment wrapped to a readable width, and for a setting its default, what it
     * accepts and what must restart after a change.
     */
    static String tooltip(Row row) {
        Tooltip tooltip = Tooltip.of("").detail(row.path()).text(row.comment());
        PackCatalog.ConfigSetting setting = row.setting();
        if (setting != null) {
            if (!setting.defaultValue().isEmpty()) {
                tooltip.fact("Default", literal(row.kind(), setting.defaultValue()), color(row.kind()));
            }
            tooltip.fact("Accepts", row.accepts());
            switch (setting.restart()) {
                case WORLD -> tooltip.fact("Takes effect", "after rejoining the world");
                case GAME -> tooltip.fact("Takes effect", "after restarting the game");
                case NONE -> {
                }
            }
        }
        return tooltip.html();
    }

    /** A value as it is written in the file: strings in quotes, so an empty one stays visible. */
    private static String literal(ValueKind kind, String value) {
        return kind == ValueKind.STRING ? '"' + value + '"' : value;
    }

    static Color color(ValueKind kind) {
        EditorPalette palette = ThemeManager.palette();
        return switch (kind) {
            case NUMBER -> palette.number();
            case BOOLEAN -> palette.keyword();
            case STRING -> palette.string();
            case CHOICE -> palette.field();
            case LIST, TEXT -> ThemeColors.text();
        };
    }

    private static final class SettingsModel extends AbstractTableModel {
        private List<Row> all = List.of();
        private List<Row> shown = List.of();
        private boolean described = true;
        /** Sections whose settings are hidden; ignored while a filter shows matches from every section. */
        private final Set<String> collapsed = new HashSet<>();
        private Predicate<Row> keep = row -> true;
        private boolean filtering;

        void setColumns(boolean described) {
            if (this.described == described) return;
            this.described = described;
            fireTableStructureChanged();
        }

        void setRows(List<Row> rows) {
            this.all = List.copyOf(rows);
            refilter();
        }

        void filter(Predicate<Row> keep, boolean filtering) {
            this.keep = keep;
            this.filtering = filtering;
            refilter();
        }

        /** Keeps matching settings and the sections above them; collapsed sections hide theirs unless filtering. */
        void refilter() {
            Set<String> sections = new LinkedHashSet<>();
            for (Row row : this.all) {
                if (row.setting() == null || !this.keep.test(row)) continue;
                String[] parts = row.path().split("\\.");
                StringBuilder prefix = new StringBuilder();
                for (int depth = 0; depth < parts.length - 1; depth++) {
                    if (depth > 0) prefix.append('.');
                    prefix.append(parts[depth]);
                    sections.add(prefix.toString());
                }
            }
            this.shown = this.all.stream()
                    .filter(row -> row.setting() == null ? sections.contains(row.path()) : this.keep.test(row))
                    .filter(row -> this.filtering || !insideCollapsed(row))
                    .toList();
            fireTableDataChanged();
        }

        private boolean insideCollapsed(Row row) {
            for (String section : this.collapsed) {
                if (row.path().startsWith(section + ".")) return true;
            }
            return false;
        }

        boolean isCollapsed(Row row) {
            return row.setting() == null && !this.filtering && this.collapsed.contains(row.path());
        }

        @Override
        public int getRowCount() {
            return this.shown.size();
        }

        @Override
        public int getColumnCount() {
            return this.described ? 3 : 2;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> "Setting";
                case 1 -> "Value";
                default -> "Accepts";
            };
        }

        @Override
        public Object getValueAt(int row, int column) {
            Row entry = this.shown.get(row);
            if (entry.setting() == null) return column == 0 ? entry.name() : "";
            return switch (column) {
                case 0 -> entry.name();
                case 1 -> entry.value();
                default -> entry.accepts();
            };
        }
    }

    /**
     * Setting names indented under their sections, values colored by kind, and a modified value with its default
     * beside it.
     */
    private final class SettingRenderer extends DefaultTableCellRenderer {
        private final SettingCell name = new SettingCell();
        private final JPanel valueCell = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        private final JLabel value = new JLabel();
        private final JLabel defaultHint = new JLabel();

        private SettingRenderer() {
            this.valueCell.add(this.value);
            this.valueCell.add(this.defaultHint);
            this.defaultHint.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
            this.valueCell.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object cell, boolean selected, boolean focused,
                                                       int rowIndex, int column) {
            Row row = ConfigSettingsTable.this.model.shown.get(rowIndex);
            Color background = selected ? table.getSelectionBackground() : table.getBackground();
            Color foreground = selected ? table.getSelectionForeground() : ThemeColors.text();
            if (column == 0) {
                this.name.configure(row, ConfigSettingsTable.this.model.isCollapsed(row), table.getFont(), foreground, background);
                return this.name;
            }
            if (column == 1 && row.setting() != null) {
                this.value.setText(literal(row.kind(), row.value()));
                this.value.setFont(table.getFont());
                // The themes' selection is a soft tint, so values keep their colors on it like code does.
                this.value.setForeground(row.kind() == ValueKind.LIST || row.kind() == ValueKind.TEXT ? foreground : color(row.kind()));
                this.defaultHint.setText(row.modified() ? "default " + literal(row.kind(), row.setting().defaultValue()) : "");
                this.defaultHint.setFont(table.getFont());
                this.defaultHint.setForeground(ThemeColors.secondaryText());
                this.valueCell.setBackground(background);
                return this.valueCell;
            }
            super.getTableCellRendererComponent(table, cell, selected, false, rowIndex, column);
            setIcon(null);
            setFont(table.getFont());
            setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
            setForeground(selected ? foreground : ThemeColors.secondaryText());
            return this;
        }
    }

    /** The name cell: a chevron for sections, indentation by depth, and the modified bar at the left edge. */
    private static final class SettingCell extends JLabel {
        private static final int BAR_WIDTH = 3;
        private boolean modified;

        void configure(Row row, boolean collapsed, Font font, Color foreground, Color background) {
            this.modified = row.modified();
            setOpaque(true);
            setBackground(background);
            setForeground(foreground);
            setFont(font);
            setText(row.name());
            setIconTextGap(2);
            boolean section = row.setting() == null;
            setIcon(section ? UIManager.getIcon(collapsed ? "Tree.collapsedIcon" : "Tree.expandedIcon") : null);
            setBorder(BorderFactory.createEmptyBorder(0, indent(row.depth()) + (section ? 0 : CHEVRON_WIDTH + 2), 0, 4));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (this.modified) {
                graphics.setColor(ThemeColors.accent());
                graphics.fillRect(0, 1, BAR_WIDTH, getHeight() - 2);
            }
        }
    }
}
