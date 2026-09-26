package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryText;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.GroupedRowCell;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.Tables;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigChanges;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigEdit;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigValues;
import com.github.minecraft_ta.totalDebugCompanion.ui.ContextMenus;
import com.github.minecraft_ta.totalDebugCompanion.ui.Tooltip;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.EditorPalette;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;

import javax.swing.AbstractAction;
import javax.swing.AbstractCellEditor;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellEditor;

import java.awt.Color;
import java.awt.Component;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.EventObject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * A configuration file's settings under their sections. Values are colored by kind like code literals, a value that
 * differs from its default is marked at the row's edge with the default beside it, sections collapse from their
 * chevron, and a row's tooltip holds its description. A value is edited in place: double-click, Enter or F2 opens a
 * field, or a list of the accepted values, and a value the setting does not accept is refused before it is written.
 * Several settings, or a whole section, are reset or reverted together from the menu of the selection.
 */
final class ConfigSettingsTable extends JTable {
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?");

    /** How a value is colored, the way the code editor colors literals of the same kind. */
    enum ValueKind { NUMBER, BOOLEAN, STRING, CHOICE, LIST, TEXT }

    /**
     * One row: a section heading when {@code setting} is null. {@code literal} is the value as the file writes it, null
     * when the file does not set it.
     */
    record Row(int depth, String path, String name, String comment, PackCatalog.ConfigSetting setting, String value,
               String literal) {
        boolean modified() {
            return this.setting != null && !this.setting.defaultValue().isEmpty() && !this.value.isEmpty()
                    && !this.value.equals(this.setting.defaultValue());
        }

        String accepts() {
            if (this.setting == null) return "";
            return this.setting.allowed().isEmpty() ? ConfigEdit.readableRange(this.setting.range()) : String.join(", ", this.setting.allowed());
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
    private final ValueEditor editor = new ValueEditor();
    /** Receives an accepted edit as the setting's row and the value to write. */
    private BiConsumer<Row, String> edited = (row, literal) -> { };
    /** Receives why a typed value was refused, and an empty text once nothing is refused. */
    private Consumer<String> refused = problem -> { };
    private Function<Row, ConfigChanges.Effect> pending = row -> null;
    /** The value a setting had before it was first edited while Companion runs, as the file writes it, or null. */
    private Function<Row, String> original = row -> null;
    /** A section row's own tooltip, or null for its key and comment. */
    private Function<Row, String> sectionTooltip = row -> null;

    ConfigSettingsTable() {
        setModel(this.model);
        setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        Tables.configure(this);
        setDefaultRenderer(Object.class, new SettingRenderer());
        setDefaultEditor(Object.class, this.editor);
        // Typing filters the settings instead of starting an edit, and leaving an edit keeps what was typed.
        putClientProperty("JTable.autoStartsEdit", Boolean.FALSE);
        putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        ToolTipManager.sharedInstance().registerComponent(this);
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                int row = rowAtPoint(event.getPoint());
                if (row < 0 || !SwingUtilities.isLeftMouseButton(event)) return;
                if (onChevron(row, event.getPoint())) {
                    toggleSection(row, null);
                } else if (event.getClickCount() == 2 && columnAtPoint(event.getPoint()) != 1) {
                    // A double-click anywhere on a setting edits its value.
                    edit(row);
                }
            }
        });
        for (int key : new int[]{KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, KeyEvent.VK_ENTER, KeyEvent.VK_F2}) {
            Boolean expand = key == KeyEvent.VK_LEFT ? Boolean.FALSE : key == KeyEvent.VK_RIGHT ? Boolean.TRUE : null;
            boolean edits = key == KeyEvent.VK_ENTER || key == KeyEvent.VK_F2;
            String name = "configSection" + key;
            getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key, 0), name);
            getActionMap().put(name, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent event) {
                    int row = getSelectedRow();
                    if (row < 0) return;
                    if (edits && ConfigSettingsTable.this.model.shown.get(row).setting() != null) edit(row);
                    else if (key != KeyEvent.VK_F2) toggleSection(row, expand);
                }
            });
        }
        ContextMenus.installTable(this, this::rowMenu);
    }

    /**
     * Shows the rows of a file; whether it has a specification decides if Accepts is shown, and {@code editable}
     * whether values can be edited.
     */
    void show(List<Row> rows, boolean described, boolean editable) {
        if (isEditing()) getCellEditor().cancelCellEditing();
        this.model.collapsed.clear();
        this.model.editable = editable;
        this.model.setColumns(described);
        this.model.setRows(rows);
    }

    /**
     * Where edits go: {@code edited} writes an accepted value, {@code refused} shows why a value was refused, and
     * {@code pending} tells what the running game waits for before it uses a setting's edited value, and
     * {@code original} the value a setting had before it was first edited, as the file wrote it.
     */
    void setEditing(BiConsumer<Row, String> edited, Consumer<String> refused, Function<Row, ConfigChanges.Effect> pending,
                    Function<Row, String> original) {
        this.edited = Objects.requireNonNull(edited, "edited");
        this.refused = Objects.requireNonNull(refused, "refused");
        this.pending = Objects.requireNonNull(pending, "pending");
        this.original = Objects.requireNonNull(original, "original");
    }

    void setSectionTooltip(Function<Row, String> sectionTooltip) {
        this.sectionTooltip = Objects.requireNonNull(sectionTooltip, "sectionTooltip");
    }

    /** The shown row at a view index. */
    Row row(int viewRow) {
        return this.model.shown.get(viewRow);
    }

    /** Opens the value of a setting row for editing, when the file allows it. */
    boolean edit(int viewRow) {
        if (!this.model.isCellEditable(viewRow, 1) || !editCellAt(viewRow, 1)) return false;
        setRowSelectionInterval(viewRow, viewRow);
        Component component = getEditorComponent();
        if (component != null) component.requestFocusInWindow();
        return true;
    }

    private JPopupMenu rowMenu(int viewRow) {
        if (viewRow < 0) return null;
        List<Row> selected = selectedSettings();
        if (selected.size() > 1) return bulkMenu(selected);
        Row row = this.model.shown.get(viewRow);
        JPopupMenu menu = new JPopupMenu();
        if (row.setting() != null) {
            Action edit = ContextMenus.action("Edit Value", null, "Enter", () -> edit(viewRow));
            edit.setEnabled(this.model.isCellEditable(viewRow, 1));
            menu.add(edit);
            Action reset = ContextMenus.action("Reset to Default", null, null, () -> reset(row));
            reset.setEnabled(this.model.isCellEditable(viewRow, 1) && row.modified());
            menu.add(reset);
            String original = this.original.apply(row);
            if (original != null) {
                String before = literal(row.kind(), before(row));
                Action revert = ContextMenus.action(before.length() <= 24 ? "Revert to " + before : "Revert", null, null,
                        () -> this.edited.accept(row, original));
                revert.setEnabled(this.model.isCellEditable(viewRow, 1));
                menu.add(revert);
            }
            menu.addSeparator();
            menu.add(ContextMenus.defaultCopy(ContextMenus.copyAction("Copy Value", row.value())));
        }
        menu.add(ContextMenus.copyAction("Copy Key", row.path()));
        return menu;
    }

    /** The selected settings; a selected section stands for its settings that pass the filter. */
    private List<Row> selectedSettings() {
        Set<Row> selected = new LinkedHashSet<>();
        for (int viewRow : getSelectedRows()) {
            Row row = this.model.shown.get(viewRow);
            if (row.setting() != null) {
                selected.add(row);
                continue;
            }
            for (Row member : this.model.all) {
                if (member.setting() != null && member.path().startsWith(row.path() + ".") && this.model.keep.test(member)) {
                    selected.add(member);
                }
            }
        }
        return List.copyOf(selected);
    }

    /** What can be done to several settings at once; each action names how many settings it changes. */
    private JPopupMenu bulkMenu(List<Row> selected) {
        List<Row> resettable = new ArrayList<>();
        Map<Row, String> reverts = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>();
        for (Row row : selected) {
            boolean editable = this.model.editable && ConfigEdit.editable(row.literal());
            if (editable && row.modified()) resettable.add(row);
            String original = this.original.apply(row);
            if (editable && original != null) reverts.put(row, original);
            keys.add(row.setting().path());
        }
        JPopupMenu menu = new JPopupMenu();
        Action reset = ContextMenus.action(resettable.isEmpty() ? "Reset to Default" : "Reset " + resettable.size() + " to Default",
                null, null, () -> resettable.forEach(this::reset));
        reset.setEnabled(!resettable.isEmpty());
        menu.add(reset);
        if (!reverts.isEmpty()) {
            menu.add(ContextMenus.action("Revert " + reverts.size(), null, null, () -> reverts.forEach(this.edited)));
        }
        menu.addSeparator();
        menu.add(ContextMenus.defaultCopy(ContextMenus.copyAction("Copy " + keys.size() + " Keys", String.join("\n", keys))));
        return menu;
    }

    /** Writes the setting's default in place of its value. */
    void reset(Row row) {
        try {
            this.edited.accept(row, ConfigEdit.literal(row.literal(), row.setting(), row.setting().defaultValue()));
        } catch (IllegalArgumentException refusal) {
            this.refused.accept("The default of " + row.name() + " cannot be written: " + refusal.getMessage());
        }
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
        if (row < 0) return null;
        Row entry = this.model.shown.get(row);
        String own = entry.setting() == null ? this.sectionTooltip.apply(entry) : null;
        if (own != null || entry.setting() == null) return own != null ? own : tooltip(entry, null, null);
        return tooltip(entry, this.pending.apply(entry), before(entry));
    }

    /** Clicking a section selects it for reading; only its chevron collapses it. */
    private boolean onChevron(int viewRow, Point point) {
        Row row = this.model.shown.get(viewRow);
        return row.setting() == null && columnAtPoint(point) == 0
                && GroupedRowCell.onChevron(this, viewRow, row.depth(), point.x);
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
                    rows.add(new Row(depth, section, parts[depth], sectionComments.getOrDefault(section, ""), null, "", null));
                }
            }
            String value = values == null ? setting.defaultValue() : values.values().getOrDefault(setting.path(), "");
            String literal = values == null ? null : values.literals().get(setting.path());
            rows.add(new Row(parts.length - 1, setting.path(), setting.name(), setting.comment(), setting, value, literal));
        }
        return rows;
    }

    /** The value a setting had before it was first edited, in the form values are shown in, or null. */
    private String before(Row row) {
        String literal = this.original.apply(row);
        if (literal == null) return null;
        try {
            return PackCatalog.ConfigSetting.display(ConfigValues.value(literal));
        } catch (IllegalArgumentException notAValue) {
            return literal;
        }
    }

    /**
     * A row's description: its key, its comment wrapped to a readable width, and for a setting its default, what it
     * accepts and what must restart after a change. {@code before} is the value the setting had before it was edited,
     * and {@code pending} what the game waits for before using the edit.
     */
    static String tooltip(Row row, ConfigChanges.Effect pending, String before) {
        Tooltip tooltip = Tooltip.of("").detail(row.setting() == null ? row.path() : row.setting().path()).text(row.comment());
        if (before != null) tooltip.fact("Before your edit", literal(row.kind(), before), color(row.kind()));
        if (pending != null) tooltip.fact("Edited value", pending.description());
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
        private boolean editable;

        @Override
        public boolean isCellEditable(int row, int column) {
            Row entry = this.shown.get(row);
            return this.editable && column == 1 && entry.setting() != null && ConfigEdit.editable(entry.literal());
        }

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
        private final GroupedRowCell name = new GroupedRowCell();
        private final JPanel valueCell = new JPanel();
        private final JLabel value = new JLabel();
        private final JLabel defaultHint = new JLabel();
        private final JLabel pendingMark = new JLabel(Icons.REFRESH);

        private SettingRenderer() {
            // Centered in the row like the name beside it.
            this.valueCell.setLayout(new BoxLayout(this.valueCell, BoxLayout.X_AXIS));
            this.valueCell.add(this.value);
            this.valueCell.add(this.defaultHint);
            this.valueCell.add(this.pendingMark);
            this.defaultHint.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
            this.pendingMark.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
            this.valueCell.setBorder(UiMetrics.cellPadding());
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object cell, boolean selected, boolean focused,
                                                       int rowIndex, int column) {
            Row row = ConfigSettingsTable.this.model.shown.get(rowIndex);
            Color background = selected ? table.getSelectionBackground() : table.getBackground();
            Color foreground = selected ? table.getSelectionForeground() : ThemeColors.text();
            if (column == 0) {
                String before = row.setting() == null ? null : before(row);
                // Your edits are marked in the accent color; values that already differed from their default in grey.
                Color bar = before != null ? ThemeColors.accent() : row.modified() ? ThemeColors.mutedText() : null;
                Boolean collapsed = row.setting() == null ? ConfigSettingsTable.this.model.isCollapsed(row) : null;
                return this.name.configure(table, PrimarySecondaryText.primary(row.name()), row.depth(), collapsed, selected, bar);
            }
            if (column == 1 && row.setting() != null) {
                this.value.setText(literal(row.kind(), row.value()));
                this.value.setFont(table.getFont());
                // The themes' selection is a soft tint, so values keep their colors on it like code does.
                this.value.setForeground(row.kind() == ValueKind.LIST || row.kind() == ValueKind.TEXT ? foreground : color(row.kind()));
                String before = before(row);
                this.defaultHint.setText(before != null ? "was " + literal(row.kind(), before)
                        : row.modified() ? "default " + literal(row.kind(), row.setting().defaultValue()) : "");
                this.defaultHint.setFont(table.getFont());
                this.defaultHint.setForeground(ThemeColors.secondaryText());
                this.pendingMark.setVisible(ConfigSettingsTable.this.pending.apply(row) != null);
                this.valueCell.setBackground(background);
                return this.valueCell;
            }
            super.getTableCellRendererComponent(table, cell, selected, false, rowIndex, column);
            setIcon(null);
            setFont(table.getFont());
            setBorder(UiMetrics.cellPadding());
            setForeground(selected ? foreground : ThemeColors.secondaryText());
            return this;
        }
    }

    /**
     * Edits a value: a list of the accepted values for a choice or a boolean, a field otherwise. The typed text is
     * checked as NeoForge checks the file, and an edit that changes nothing writes nothing.
     */
    private final class ValueEditor extends AbstractCellEditor implements TableCellEditor {
        private final JTextField field = new JTextField();
        private final JComboBox<String> choices = new JComboBox<>();
        private JComponent active = this.field;
        private Row row;
        private boolean configuring;

        private ValueEditor() {
            this.field.setBorder(UiMetrics.cellPadding());
            this.field.addActionListener(event -> stopCellEditing());
            this.choices.putClientProperty("JComboBox.isTableCellEditor", Boolean.TRUE);
            // The offered values keep the color of their kind, like the value they replace.
            this.choices.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
                                                              boolean focused) {
                    super.getListCellRendererComponent(list, value, index, selected, focused);
                    if (ValueEditor.this.row != null) setForeground(color(ValueEditor.this.row.kind()));
                    return this;
                }
            });
            this.choices.addActionListener(event -> {
                if (!this.configuring) stopCellEditing();
            });
        }

        @Override
        public boolean isCellEditable(EventObject event) {
            return !(event instanceof MouseEvent mouse) || mouse.getClickCount() >= 2;
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean selected, int viewRow, int column) {
            this.row = ConfigSettingsTable.this.model.shown.get(viewRow);
            this.field.putClientProperty("JComponent.outline", null);
            List<String> options = options(this.row);
            if (options.isEmpty()) {
                this.field.setFont(table.getFont());
                ValueKind kind = this.row.kind();
                // Typed text keeps the color the value is shown in.
                this.field.setForeground(kind == ValueKind.LIST || kind == ValueKind.TEXT ? ThemeColors.text() : color(kind));
                this.field.setText(this.row.value());
                this.field.selectAll();
                this.active = this.field;
            } else {
                this.configuring = true;
                try {
                    this.choices.setFont(table.getFont());
                    // FlatLaf paints the chosen value in the combo box's own foreground.
                    this.choices.setForeground(color(this.row.kind()));
                    this.choices.setModel(new DefaultComboBoxModel<>(options.toArray(String[]::new)));
                    this.choices.setSelectedItem(options.stream().filter(this.row.value()::equalsIgnoreCase).findFirst().orElse(null));
                } finally {
                    this.configuring = false;
                }
                this.active = this.choices;
                SwingUtilities.invokeLater(() -> {
                    if (this.choices.isShowing()) this.choices.showPopup();
                });
            }
            return this.active;
        }

        @Override
        public Object getCellEditorValue() {
            return this.active == this.choices ? Objects.toString(this.choices.getSelectedItem(), "") : this.field.getText();
        }

        @Override
        public boolean stopCellEditing() {
            Row editing = this.row;
            String literal;
            try {
                literal = ConfigEdit.literal(editing.literal(), editing.setting(), (String) getCellEditorValue());
            } catch (IllegalArgumentException refusal) {
                this.field.putClientProperty("JComponent.outline", "error");
                ConfigSettingsTable.this.refused.accept(editing.name() + ": " + refusal.getMessage());
                return false;
            }
            ConfigSettingsTable.this.refused.accept("");
            fireEditingStopped();
            if (!literal.equals(editing.literal())) ConfigSettingsTable.this.edited.accept(editing, literal);
            return true;
        }

        @Override
        public void cancelCellEditing() {
            ConfigSettingsTable.this.refused.accept("");
            super.cancelCellEditing();
        }
    }

    /** The values offered in a list: the accepted values of a choice, or true and false. */
    private static List<String> options(Row row) {
        if (!row.setting().allowed().isEmpty()) return row.setting().allowed();
        return ConfigEdit.kind(row.literal()) == ConfigEdit.Kind.BOOLEAN ? List.of("true", "false") : List.of();
    }
}
