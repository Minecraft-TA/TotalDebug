package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.Tables;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.TypeToFilter;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogIndex;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconTextField;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.subject.ContentKinds;

import javax.swing.text.JTextComponent;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A filterable table of registered content; opening a row opens its definition. A Kind column is shown while entries
 * of several kinds are listed together, and a Mod column where they come from several mods.
 */
public final class CatalogEntryTable extends JPanel {
    /** The columns after the icon. */
    private enum Column {
        NAME("Name"), ID("Registry ID"), KIND("Kind"), MOD("Mod");

        private final String title;

        Column(String title) {
            this.title = title;
        }
    }

    private final FlatIconTextField filter = new FlatIconTextField(Icons.SEARCH_ICON);
    private final CatalogIcons icons;
    private final EntryModel model;
    private final JTable table;
    private final TableRowSorter<EntryModel> sorter;

    /** {@code modName} names the mod of an entry in its own column, or is null where every entry is one mod's. */
    public CatalogEntryTable(String placeholder, CatalogIcons icons,
                             Function<CatalogIndex.Entry, CatalogIndex.ItemIcon> iconOf,
                             Consumer<CatalogIndex.Entry> open, Function<CatalogIndex.Entry, String> modName) {
        super(new BorderLayout());
        this.icons = Objects.requireNonNull(icons, "icons");
        this.model = new EntryModel(modName);
        this.table = new JTable(this.model);
        this.sorter = new TableRowSorter<>(this.model);
        Objects.requireNonNull(iconOf, "iconOf");
        Objects.requireNonNull(open, "open");
        this.filter.putClientProperty("JTextField.placeholderText", placeholder);
        this.filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { applyFilter(); }
            @Override public void removeUpdate(DocumentEvent event) { applyFilter(); }
            @Override public void changedUpdate(DocumentEvent event) { applyFilter(); }
        });
        this.table.setRowSorter(this.sorter);
        this.table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        Tables.configure(this.table);
        this.table.setRowHeight(Math.max(this.table.getRowHeight(), icons.size() + 6));
        this.table.setDefaultRenderer(CatalogIndex.Entry.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                           boolean focused, int row, int column) {
                super.getTableCellRendererComponent(table, "", selected, false, row, column);
                setHorizontalAlignment(CENTER);
                CatalogIndex.Entry entry = (CatalogIndex.Entry) value;
                Icon icon = icons.icon(iconOf.apply(entry), table);
                setIcon(icon != null ? icon : ContentKinds.of(entry.registry()).icon());
                return this;
            }
        });
        configureColumns();
        this.table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                int row = CatalogEntryTable.this.table.rowAtPoint(event.getPoint());
                if (event.getClickCount() == 2 && row >= 0) open.accept(entryAt(row));
            }
        });
        this.table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "openEntry");
        this.table.getActionMap().put("openEntry", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                int row = CatalogEntryTable.this.table.getSelectedRow();
                if (row >= 0) open.accept(entryAt(row));
            }
        });
        JPanel top = new JPanel(new BorderLayout());
        top.setBorder(UiMetrics.barPadding());
        top.add(this.filter, BorderLayout.CENTER);
        add(top, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(this.table);
        // The selected row shows where focus is; a focus frame around the whole table would only add noise.
        scroll.setBorder(BorderFactory.createEmptyBorder());
        add(scroll, BorderLayout.CENTER);
        TypeToFilter.install(this.table, this.filter);
    }

    /** The field that filters the entries, which typing anywhere on the page reaches. */
    public JTextComponent filterField() {
        return this.filter;
    }

    /** Lists {@code entries}; {@code severalKinds} shows the Kind column. */
    public void setEntries(List<CatalogIndex.Entry> entries, boolean severalKinds) {
        this.model.entries = List.copyOf(entries);
        if (this.model.setShowKind(severalKinds)) {
            configureColumns();
        } else {
            this.model.fireTableDataChanged();
        }
    }

    public void setPlaceholder(String placeholder) {
        this.filter.putClientProperty("JTextField.placeholderText", placeholder);
        this.filter.repaint();
    }

    /** The icon column stays narrow and unsorted; rebuilt columns lose both. */
    private void configureColumns() {
        this.table.getColumnModel().getColumn(0).setMaxWidth(this.icons.size() + 12);
        this.table.getColumnModel().getColumn(0).setMinWidth(this.icons.size() + 12);
        this.sorter.setSortable(0, false);
    }

    public int rowCount() {
        return this.table.getRowCount();
    }

    JTable table() {
        return this.table;
    }

    void setFilter(String text) {
        this.filter.setText(text);
    }

    CatalogIndex.Entry entryAt(int viewRow) {
        return this.model.entries.get(this.table.convertRowIndexToModel(viewRow));
    }

    private void applyFilter() {
        String text = this.filter.getText().strip().toLowerCase(Locale.ROOT);
        this.sorter.setRowFilter(text.isEmpty() ? null : new RowFilter<>() {
            @Override
            public boolean include(Entry<? extends EntryModel, ? extends Integer> row) {
                CatalogIndex.Entry entry = CatalogEntryTable.this.model.entries.get(row.getIdentifier());
                return entry.name().toLowerCase(Locale.ROOT).contains(text) || entry.id().contains(text)
                        || CatalogEntryTable.this.model.modName != null
                        && CatalogEntryTable.this.model.modName.apply(entry).toLowerCase(Locale.ROOT).contains(text);
            }
        });
    }

    private static final class EntryModel extends AbstractTableModel {
        private final Function<CatalogIndex.Entry, String> modName;
        private List<CatalogIndex.Entry> entries = List.of();
        private List<Column> columns = List.of();

        EntryModel(Function<CatalogIndex.Entry, String> modName) {
            this.modName = modName;
            this.columns = columns(false);
        }

        /** Shows or hides the Kind column; answers whether the columns changed, which rebuilds the table's columns. */
        boolean setShowKind(boolean showKind) {
            List<Column> columns = columns(showKind);
            if (columns.equals(this.columns)) return false;
            this.columns = columns;
            fireTableStructureChanged();
            return true;
        }

        private List<Column> columns(boolean showKind) {
            List<Column> columns = new ArrayList<>(List.of(Column.NAME, Column.ID));
            if (showKind) columns.add(Column.KIND);
            if (this.modName != null) columns.add(Column.MOD);
            return List.copyOf(columns);
        }

        @Override
        public int getRowCount() {
            return this.entries.size();
        }

        @Override
        public int getColumnCount() {
            return 1 + this.columns.size();
        }

        @Override
        public String getColumnName(int column) {
            return column == 0 ? "" : this.columns.get(column - 1).title;
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == 0 ? CatalogIndex.Entry.class : String.class;
        }

        @Override
        public Object getValueAt(int row, int column) {
            CatalogIndex.Entry entry = this.entries.get(row);
            if (column == 0) return entry;
            return switch (this.columns.get(column - 1)) {
                case NAME -> entry.title();
                case ID -> entry.id();
                case KIND -> ContentKinds.of(entry.registry()).singular();
                case MOD -> this.modName.apply(entry);
            };
        }
    }
}
