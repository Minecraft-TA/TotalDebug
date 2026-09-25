package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.ui.components.TypeToFilter;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogIndex;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconTextField;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.subject.SubjectIcons;

import javax.swing.text.JTextComponent;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JLabel;
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
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/** A filterable table of registered blocks, items or entity types; opening a row opens its definition. */
public final class CatalogEntryTable extends JPanel {
    static final int NAME = 1;
    static final int ID = 2;
    static final int MOD = 3;

    private final FlatIconTextField filter = new FlatIconTextField(Icons.SEARCH_ICON);
    private final EntryModel model;
    private final JTable table;
    private final TableRowSorter<EntryModel> sorter;

    /** {@code modName} names the mod of an entry in its own column, or is null where every entry is one mod's. */
    public CatalogEntryTable(String placeholder, CatalogIcons icons,
                             Function<CatalogIndex.Entry, CatalogIndex.ItemIcon> iconOf,
                             Consumer<CatalogIndex.Entry> open, Function<CatalogIndex.Entry, String> modName) {
        super(new BorderLayout());
        this.model = new EntryModel(modName);
        this.table = new JTable(this.model);
        this.sorter = new TableRowSorter<>(this.model);
        Objects.requireNonNull(icons, "icons");
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
        this.table.setShowGrid(false);
        this.table.setFillsViewportHeight(true);
        var headerRenderer = this.table.getTableHeader().getDefaultRenderer();
        this.table.getTableHeader().setDefaultRenderer((table, value, selected, focused, row, column) -> {
            Component header = headerRenderer.getTableCellRendererComponent(table, value, selected, focused, row, column);
            if (header instanceof JLabel label) label.setHorizontalAlignment(JLabel.LEADING);
            return header;
        });
        this.table.setRowHeight(Math.max(this.table.getRowHeight(), icons.size() + 6));
        this.table.getColumnModel().getColumn(0).setMaxWidth(icons.size() + 12);
        this.table.getColumnModel().getColumn(0).setMinWidth(icons.size() + 12);
        this.table.getColumnModel().getColumn(0).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                           boolean focused, int row, int column) {
                super.getTableCellRendererComponent(table, "", selected, false, row, column);
                setHorizontalAlignment(CENTER);
                Icon icon = icons.icon(iconOf.apply((CatalogIndex.Entry) value), table);
                setIcon(icon != null ? icon : SubjectIcons.definition(((CatalogIndex.Entry) value).kind()));
                return this;
            }
        });
        this.sorter.setSortable(0, false);
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
        top.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
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

    public void setEntries(List<CatalogIndex.Entry> entries) {
        this.model.entries = List.copyOf(entries);
        this.model.fireTableDataChanged();
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

        EntryModel(Function<CatalogIndex.Entry, String> modName) {
            this.modName = modName;
        }

        @Override
        public int getRowCount() {
            return this.entries.size();
        }

        @Override
        public int getColumnCount() {
            return this.modName == null ? 3 : 4;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case NAME -> "Name";
                case ID -> "Registry ID";
                case MOD -> "Mod";
                default -> "";
            };
        }

        @Override
        public Object getValueAt(int row, int column) {
            CatalogIndex.Entry entry = this.entries.get(row);
            return switch (column) {
                case NAME -> entry.title();
                case ID -> entry.id();
                case MOD -> this.modName.apply(entry);
                default -> entry;
            };
        }
    }
}
