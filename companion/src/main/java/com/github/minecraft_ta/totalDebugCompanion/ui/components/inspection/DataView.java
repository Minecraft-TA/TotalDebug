package com.github.minecraft_ta.totalDebugCompanion.ui.components.inspection;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.ui.ContextMenus;
import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconTextField;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.editors.AbstractTextViewPanel;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.editors.ReadOnlyTextPanel;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.EditorPalette;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager;
import com.github.minecraft_ta.totaldebug.protocol.nbt.NbtData;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JToggleButton;
import javax.swing.JViewport;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The exact data of a read, such as block entity NBT, as a tree-table of key, type and value, or as SNBT text. The
 * filter keeps matching entries and the entries leading to them. Copying always yields Minecraft's own SNBT for the
 * entry; entries that were not transferred completely cannot be copied as values. A newer read keeps expansion,
 * selection and the scroll position by entry path and marks the values that changed.
 */
final class DataView extends JPanel {
    private static final String TREE_CARD = "tree";
    private static final String TEXT_CARD = "text";
    private static final int INDENT = 16;

    private final FlatIconTextField filter = new FlatIconTextField(Icons.SEARCH_ICON);
    private final JToggleButton treeMode = new JToggleButton("Tree");
    private final JToggleButton textMode = new JToggleButton("SNBT");
    private final RowsModel model = new RowsModel();
    private final JTable table = new JTable(this.model);
    private final JScrollPane tableScroll = new JScrollPane(this.table);
    private final JLabel textHeading = new JLabel();
    private final ReadOnlyTextPanel text = new ReadOnlyTextPanel();
    private final JPanel cards = new JPanel(new CardLayout());
    private final Set<String> toggled = new HashSet<>();
    private final Set<String> changed = new HashSet<>();
    private List<DataRows.Decoded> roots = List.of();
    private Map<String, String> previousValues = Map.of();
    private List<DataRows.Row> rows = List.of();

    DataView() {
        super(new BorderLayout());
        this.filter.putClientProperty("JTextField.placeholderText", "Filter keys and values");
        this.filter.putClientProperty("JTextField.showClearButton", true);
        this.filter.setColumns(28);
        this.filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { showRows(); }
            @Override public void removeUpdate(DocumentEvent event) { showRows(); }
            @Override public void changedUpdate(DocumentEvent event) { showRows(); }
        });
        this.filter.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "clearFilter");
        this.filter.getActionMap().put("clearFilter", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { DataView.this.filter.setText(""); }
        });

        ButtonGroup modes = new ButtonGroup();
        modes.add(this.treeMode);
        modes.add(this.textMode);
        this.treeMode.setSelected(true);
        this.treeMode.putClientProperty("JButton.buttonType", "tab");
        this.textMode.putClientProperty("JButton.buttonType", "tab");
        this.treeMode.setToolTipText("Entries as a tree with their types");
        this.textMode.setToolTipText("The selected entry's root as indented SNBT");
        this.treeMode.addActionListener(event -> showMode());
        this.textMode.addActionListener(event -> showMode());

        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        bar.add(this.filter, BorderLayout.WEST);
        JPanel modeButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        modeButtons.add(this.treeMode);
        modeButtons.add(this.textMode);
        bar.add(modeButtons, BorderLayout.EAST);
        add(bar, BorderLayout.NORTH);

        configureTable();
        this.tableScroll.setBorder(BorderFactory.createEmptyBorder());
        JPanel textCard = new JPanel(new BorderLayout());
        this.textHeading.setBorder(BorderFactory.createEmptyBorder(0, 10, 6, 10));
        textCard.add(this.textHeading, BorderLayout.NORTH);
        textCard.add(this.text, BorderLayout.CENTER);
        this.cards.add(this.tableScroll, TREE_CARD);
        this.cards.add(textCard, TEXT_CARD);
        add(this.cards, BorderLayout.CENTER);
    }

    /** Shows the data of a newer read, keeping expansion, selection and scroll position by entry. */
    void show(List<DataRows.Root> next) {
        List<DataRows.Decoded> decoded = new ArrayList<>(next.size());
        for (DataRows.Root root : next) {
            decoded.add(DataRows.Decoded.of(root));
        }
        Map<String, String> values = allValues(decoded);
        this.changed.clear();
        if (!this.previousValues.isEmpty()) {
            values.forEach((key, value) -> {
                String before = this.previousValues.get(key);
                if (before != null && !before.equals(value)) this.changed.add(key);
            });
        }
        this.previousValues = values;
        this.roots = List.copyOf(decoded);
        showRows();
        if (this.textMode.isSelected()) showText();
    }

    /** Whether the read reported any data. */
    boolean hasData() {
        return !this.roots.isEmpty();
    }

    void dispose() {
        this.text.dispose();
    }

    List<DataRows.Row> rows() {
        return this.rows;
    }

    JTable table() {
        return this.table;
    }

    void setFilter(String text) {
        this.filter.setText(text);
    }

    /** Expands or collapses the entry in {@code row}. */
    void toggle(int row) {
        DataRows.Row entry = this.rows.get(row);
        if (!entry.expandable() || !this.filter.getText().isBlank()) return;
        if (!this.toggled.remove(entry.key())) this.toggled.add(entry.key());
        showRows();
    }

    /** The complete SNBT of every leaf and entry, keyed by row key, to find the values a newer read changed. */
    private static Map<String, String> allValues(List<DataRows.Decoded> roots) {
        Map<String, String> values = new HashMap<>();
        for (DataRows.Decoded root : roots) {
            if (root.tag() != null) collect(root, root.tag(), new ArrayList<>(), values);
        }
        return values;
    }

    private static void collect(DataRows.Decoded root, NbtData.Tag tag, List<Object> path, Map<String, String> values) {
        switch (tag) {
            case NbtData.CompoundTag compound -> compound.entries().forEach((key, child) -> {
                path.add(key);
                collect(root, child, path, values);
                path.removeLast();
            });
            case NbtData.ListTag list -> {
                for (int index = 0; index < list.items().size(); index++) {
                    path.add(index);
                    collect(root, list.items().get(index), path, values);
                    path.removeLast();
                }
            }
            default -> values.put(DataRows.key(root.root(), path), NbtData.snbt(tag));
        }
    }

    private void showRows() {
        String selected = selectedKey();
        Anchor anchor = anchor();
        this.rows = DataRows.visible(this.roots, this.toggled, this.filter.getText());
        this.model.fireTableDataChanged();
        restore(selected, anchor);
    }

    private String selectedKey() {
        int row = this.table.getSelectedRow();
        return row < 0 || row >= this.rows.size() ? null : this.rows.get(row).key();
    }

    /** The first visible row and how far it is scrolled past, to keep the same entry in place. */
    private record Anchor(String key, int offset) {
    }

    private Anchor anchor() {
        JViewport viewport = this.tableScroll.getViewport();
        Point position = viewport.getViewPosition();
        int row = this.table.rowAtPoint(position);
        if (row < 0 || row >= this.rows.size()) return null;
        return new Anchor(this.rows.get(row).key(), position.y - this.table.getCellRect(row, 0, true).y);
    }

    private void restore(String selected, Anchor anchor) {
        int selectedRow = indexOf(selected);
        if (selectedRow >= 0) {
            this.table.getSelectionModel().setSelectionInterval(selectedRow, selectedRow);
        }
        int anchorRow = anchor == null ? -1 : indexOf(anchor.key());
        if (anchorRow >= 0) {
            Rectangle cell = this.table.getCellRect(anchorRow, 0, true);
            this.tableScroll.getViewport().setViewPosition(new Point(0, Math.max(0, cell.y + anchor.offset())));
        }
    }

    private int indexOf(String key) {
        if (key == null) return -1;
        for (int index = 0; index < this.rows.size(); index++) {
            if (this.rows.get(index).key().equals(key)) return index;
        }
        return -1;
    }

    private void showMode() {
        ((CardLayout) this.cards.getLayout()).show(this.cards, this.textMode.isSelected() ? TEXT_CARD : TREE_CARD);
        if (this.textMode.isSelected()) showText();
    }

    /** Shows the root holding the selection, or the first root, as indented SNBT. */
    private void showText() {
        int row = this.table.getSelectedRow();
        int root = row >= 0 && row < this.rows.size() ? this.rows.get(row).root() : 0;
        if (root >= this.roots.size()) {
            this.textHeading.setText("No data");
            this.text.setContent("");
            return;
        }
        DataRows.Decoded decoded = this.roots.get(root);
        if (decoded.tag() == null) {
            this.textHeading.setText(decoded.root().name() + "  ·  " + decoded.problem());
            this.text.setContent("");
            return;
        }
        int omitted = DataRows.omittedBelow(decoded.root().data(), "");
        this.textHeading.setText(decoded.root().name() + (omitted > 0
                ? "  ·  Incomplete: " + omitted + " entries were not transferred and are missing below" : ""));
        this.textHeading.setIcon(omitted > 0 ? Icons.WARNING : null);
        this.text.setContent(NbtData.prettySnbt(decoded.tag()));
    }

    private void configureTable() {
        this.table.setShowGrid(false);
        this.table.setIntercellSpacing(new Dimension(0, 0));
        this.table.setRowHeight(UiMetrics.TREE_ROW_HEIGHT);
        this.table.setFillsViewportHeight(true);
        this.table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        this.table.getTableHeader().setReorderingAllowed(false);
        if (this.table.getTableHeader().getDefaultRenderer() instanceof JLabel header) {
            header.setHorizontalAlignment(SwingConstants.LEADING);
        }
        this.table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        TableColumn key = this.table.getColumnModel().getColumn(0);
        key.setPreferredWidth(260);
        key.setCellRenderer(new KeyRenderer());
        TableColumn type = this.table.getColumnModel().getColumn(1);
        type.setPreferredWidth(110);
        type.setMaxWidth(160);
        type.setCellRenderer(new TypeRenderer());
        TableColumn value = this.table.getColumnModel().getColumn(2);
        value.setPreferredWidth(520);
        value.setCellRenderer(new ValueRenderer());
        this.table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                int row = DataView.this.table.rowAtPoint(event.getPoint());
                if (row < 0 || event.getButton() != MouseEvent.BUTTON1) return;
                boolean onChevron = DataView.this.table.columnAtPoint(event.getPoint()) == 0
                        && event.getX() < chevronRight(DataView.this.rows.get(row));
                if (onChevron || event.getClickCount() == 2) toggle(row);
            }
        });
        bindKey(KeyEvent.VK_RIGHT, "expandEntry", () -> {
            int row = this.table.getSelectedRow();
            if (row >= 0 && this.rows.get(row).expandable() && !this.rows.get(row).expanded()) toggle(row);
        });
        bindKey(KeyEvent.VK_LEFT, "collapseEntry", () -> {
            int row = this.table.getSelectedRow();
            if (row < 0) return;
            DataRows.Row entry = this.rows.get(row);
            if (entry.expandable() && entry.expanded()) {
                toggle(row);
                return;
            }
            for (int parent = row - 1; parent >= 0; parent--) {
                if (this.rows.get(parent).depth() < entry.depth()) {
                    this.table.getSelectionModel().setSelectionInterval(parent, parent);
                    this.table.scrollRectToVisible(this.table.getCellRect(parent, 0, true));
                    return;
                }
            }
        });
        bindKey(KeyEvent.VK_ENTER, "toggleEntry", () -> {
            int row = this.table.getSelectedRow();
            if (row >= 0) toggle(row);
        });
        ContextMenus.installTable(this.table, this::menu);
    }

    private void bindKey(int key, String name, Runnable action) {
        this.table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key, 0), name);
        this.table.getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { action.run(); }
        });
    }

    /** Where each filter term occurs in {@code text}, ignoring case. */
    static List<int[]> matchRanges(String text, String filter) {
        List<int[]> ranges = new ArrayList<>();
        String lower = text.toLowerCase(Locale.ROOT);
        for (String term : filter.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (term.isEmpty()) continue;
            for (int at = lower.indexOf(term); at >= 0; at = lower.indexOf(term, at + term.length())) {
                ranges.add(new int[]{at, at + term.length()});
            }
        }
        return ranges;
    }

    private static int chevronRight(DataRows.Row row) {
        return 4 + (row.depth() + 1) * INDENT;
    }

    /** Copy actions for a row. Entries that were not transferred completely offer no value to copy. */
    JPopupMenu menu(int row) {
        JPopupMenu menu = new JPopupMenu();
        if (row < 0 || row >= this.rows.size()) return menu;
        DataRows.Row entry = this.rows.get(row);
        if (entry.kind() != DataRows.Kind.ENTRY) return menu;
        if (entry.complete()) {
            menu.add(ContextMenus.defaultCopy(ContextMenus.copyAction("Copy value", NbtData.snbt(entry.tag()))));
            if (entry.expandable()) {
                menu.add(ContextMenus.copyAction("Copy as indented SNBT", NbtData.prettySnbt(entry.tag())));
            }
        } else {
            JMenuItem incomplete = new JMenuItem("Copy value: " + entry.omitted()
                    + (entry.omitted() == 1 ? " entry was" : " entries were") + " not transferred", Icons.COPY);
            incomplete.setEnabled(false);
            menu.add(incomplete);
        }
        if (!entry.path().isEmpty()) {
            menu.add(ContextMenus.copyAction("Copy path", entry.pathText()));
            menu.add(ContextMenus.copyAction("Copy key", entry.name()));
        }
        return menu;
    }

    private final class RowsModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"Key", "Type", "Value"};

        @Override public int getRowCount() { return DataView.this.rows.size(); }

        @Override public int getColumnCount() { return COLUMNS.length; }

        @Override public String getColumnName(int column) { return COLUMNS[column]; }

        @Override
        public Object getValueAt(int row, int column) {
            return DataView.this.rows.get(row);
        }
    }

    private abstract class RowRenderer extends DefaultTableCellRenderer {
        private boolean highlight;

        @Override
        protected void paintComponent(Graphics graphics) {
            List<int[]> matches = this.highlight ? matchRanges(getText(), DataView.this.filter.getText()) : List.of();
            if (matches.isEmpty()) {
                super.paintComponent(graphics);
                return;
            }
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                if (isOpaque()) {
                    g.setColor(getBackground());
                    g.fillRect(0, 0, getWidth(), getHeight());
                }
                FontMetrics metrics = g.getFontMetrics(getFont());
                Insets insets = getInsets();
                int x = insets.left + (getIcon() == null ? 0 : getIcon().getIconWidth() + getIconTextGap());
                int y = (getHeight() - metrics.getHeight()) / 2;
                Color match = ThemeColors.searchMatch();
                // Values keep their syntax colors, so their matches are marked more lightly.
                g.setColor(this instanceof ValueRenderer
                        ? new Color(match.getRed(), match.getGreen(), match.getBlue(), 110) : match);
                for (int[] range : matches) {
                    int start = x + metrics.stringWidth(getText().substring(0, range[0]));
                    int width = metrics.stringWidth(getText().substring(range[0], range[1]));
                    g.fillRoundRect(start - 1, y, width + 2, metrics.getHeight(), 4, 4);
                }
            } finally {
                g.dispose();
            }
            setOpaque(false);
            super.paintComponent(graphics);
            setOpaque(true);
        }

        @Override
        public Component getTableCellRendererComponent(
                JTable table, Object value, boolean selected, boolean focused, int row, int column) {
            super.getTableCellRendererComponent(table, "", selected, false, row, column);
            DataRows.Row entry = (DataRows.Row) value;
            setIcon(null);
            setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
            this.highlight = column != 1 && !selected;
            configure(entry, selected);
            return this;
        }

        abstract void configure(DataRows.Row row, boolean selected);

        Color muted(boolean selected) {
            return selected ? DataView.this.table.getSelectionForeground() : ThemeColors.mutedText();
        }
    }

    private final class KeyRenderer extends RowRenderer {
        @Override
        void configure(DataRows.Row row, boolean selected) {
            Icon chevron = row.expandable()
                    ? UIManager.getIcon(row.expanded() ? "Tree.expandedIcon" : "Tree.collapsedIcon") : null;
            setIcon(chevron);
            setIconTextGap(4);
            int indent = 4 + row.depth() * INDENT + (chevron == null ? INDENT : 0);
            setBorder(BorderFactory.createEmptyBorder(0, indent, 0, 6));
            setText(row.name());
            setFont(DataView.this.table.getFont().deriveFont(row.depth() == 0 ? Font.BOLD : Font.PLAIN));
            if (row.kind() != DataRows.Kind.ENTRY) {
                setIcon(row.kind() == DataRows.Kind.OMITTED ? null : Icons.ERROR);
                setForeground(muted(selected));
            }
        }
    }

    private final class TypeRenderer extends RowRenderer {
        @Override
        void configure(DataRows.Row row, boolean selected) {
            setText(row.type());
            setForeground(muted(selected));
            setFont(DataView.this.table.getFont().deriveFont(DataView.this.table.getFont().getSize2D() - 1f));
        }
    }

    private final class ValueRenderer extends RowRenderer {
        @Override
        void configure(DataRows.Row row, boolean selected) {
            setText(row.value());
            setHorizontalAlignment(SwingConstants.LEADING);
            Font mono = AbstractTextViewPanel.JETBRAINS_MONO_FONT.deriveFont(DataView.this.table.getFont().getSize2D());
            setFont(row.expanded() || row.kind() != DataRows.Kind.ENTRY ? DataView.this.table.getFont() : mono);
            EditorPalette palette = ThemeManager.palette();
            if (selected) {
                setForeground(DataView.this.table.getSelectionForeground());
            } else if (row.kind() == DataRows.Kind.UNREADABLE) {
                setForeground(ThemeColors.error());
            } else if (row.expanded() || row.kind() == DataRows.Kind.OMITTED) {
                setForeground(ThemeColors.mutedText());
            } else if (row.tag() instanceof NbtData.StringTag) {
                setForeground(palette.string());
            } else if (!row.expandable() && row.tag() != null && !(row.tag() instanceof NbtData.ByteArrayTag
                    || row.tag() instanceof NbtData.IntArrayTag || row.tag() instanceof NbtData.LongArrayTag)) {
                setForeground(palette.number());
            } else {
                setForeground(ThemeColors.text());
            }
            if (!selected && DataView.this.changed.contains(row.key())) {
                setBackground(ChangeMarks.tint());
            }
            setToolTipText(row.expandable() || row.value().length() < 60 ? null : row.value());
        }
    }
}
