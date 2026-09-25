package com.github.minecraft_ta.totalDebugCompanion.ui.components.inspection;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.resource.FileTypeResolver;
import com.github.minecraft_ta.totalDebugCompanion.ui.ContextMenus;
import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.editors.AbstractTextViewPanel;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.editors.ReadOnlyTextPanel;
import com.github.minecraft_ta.totalDebugCompanion.ui.speedsearch.SpeedSearch;
import com.github.minecraft_ta.totalDebugCompanion.ui.speedsearch.SpeedSearchTarget;
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
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * The exact data of a read, such as block entity NBT, as a tree-table of key, type and value, or as SNBT text. Typing
 * searches every entry, including collapsed ones, and reveals the match. Copying always yields Minecraft's own SNBT
 * for the entry; entries that were not transferred completely cannot be copied as values. A newer read keeps
 * expansion, selection and the scroll position by entry path and marks the values that changed.
 */
final class DataView extends JPanel {
    private static final String TREE_CARD = "tree";
    private static final String TEXT_CARD = "text";
    private static final int INDENT = 16;

    private final JToggleButton treeMode = new JToggleButton("Tree");
    private final JToggleButton textMode = new JToggleButton("SNBT");
    private final RowsModel model = new RowsModel();
    private final JTable table = new JTable(this.model);
    private final JScrollPane tableScroll = new JScrollPane(this.table);
    private final JLabel textHeading = new JLabel();
    private final ReadOnlyTextPanel text = new ReadOnlyTextPanel(FileTypeResolver.SYNTAX_STYLE_SNBT);
    private final JPanel cards = new JPanel(new CardLayout());
    private final Set<String> toggled = new HashSet<>();
    private final Set<String> changed = new HashSet<>();
    private final EntrySearch search = new EntrySearch();
    private List<DataRows.Decoded> roots = List.of();
    private Map<String, String> previousValues = Map.of();
    private List<DataRows.Row> rows = List.of();
    private List<DataRows.Row> entries;
    private final Executor decoder;
    private long shown;

    DataView() {
        this(ForkJoinPool.commonPool());
    }

    /** {@code decoder} decodes the data of each read; a large read must not hold up the Swing thread. */
    DataView(Executor decoder) {
        super(new BorderLayout());
        this.decoder = decoder;
        ButtonGroup modes = new ButtonGroup();
        modes.add(this.treeMode);
        modes.add(this.textMode);
        this.treeMode.setSelected(true);
        for (JToggleButton mode : List.of(this.treeMode, this.textMode)) {
            mode.putClientProperty("JButton.buttonType", "tab");
            mode.addActionListener(event -> showMode());
        }
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        bar.setBorder(BorderFactory.createEmptyBorder(2, 10, 2, 10));
        bar.add(this.treeMode);
        bar.add(this.textMode);
        add(bar, BorderLayout.NORTH);

        configureTable();
        this.tableScroll.setBorder(BorderFactory.createEmptyBorder());
        JPanel textCard = new JPanel(new BorderLayout());
        this.textHeading.setBorder(BorderFactory.createEmptyBorder(4, 10, 6, 10));
        textCard.add(this.textHeading, BorderLayout.NORTH);
        textCard.add(this.text, BorderLayout.CENTER);
        this.cards.add(this.tableScroll, TREE_CARD);
        this.cards.add(textCard, TEXT_CARD);
        add(this.cards, BorderLayout.CENTER);
        SpeedSearch.install(this.search);
    }

    /**
     * Shows the data of a newer read, keeping expansion, selection and scroll position by entry. The data is decoded
     * on the decoder; only the newest read is shown.
     */
    void show(List<DataRows.Root> next) {
        long generation = ++this.shown;
        CompletableFuture.supplyAsync(() -> Prepared.of(next), this.decoder).thenAccept(prepared -> {
            Runnable apply = () -> {
                if (generation == this.shown) apply(prepared.roots(), prepared.values());
            };
            if (SwingUtilities.isEventDispatchThread()) apply.run();
            else SwingUtilities.invokeLater(apply);
        });
    }

    /** A read's data decoded, with the printed value of every leaf for marking changes. */
    private record Prepared(List<DataRows.Decoded> roots, Map<String, String> values) {
        static Prepared of(List<DataRows.Root> next) {
            List<DataRows.Decoded> decoded = new ArrayList<>(next.size());
            for (DataRows.Root root : next) {
                decoded.add(DataRows.Decoded.of(root));
            }
            return new Prepared(decoded, leafValues(decoded));
        }
    }

    private void apply(List<DataRows.Decoded> decoded, Map<String, String> values) {
        this.changed.clear();
        if (!this.previousValues.isEmpty()) {
            values.forEach((key, value) -> {
                String before = this.previousValues.get(key);
                if (before != null && !before.equals(value)) this.changed.add(key);
            });
        }
        this.previousValues = values;
        this.roots = List.copyOf(decoded);
        this.entries = null;
        showRows();
        if (this.textMode.isSelected()) showText();
        this.search.contentChanged();
    }

    /** Shows the tree and selects the root named {@code name}, as reported by {@link DataRows.Root#name()}. */
    void reveal(String name) {
        this.treeMode.doClick();
        for (int index = 0; index < this.rows.size(); index++) {
            if (this.rows.get(index).depth() == 0 && this.rows.get(index).name().equals(name)) {
                select(index);
                return;
            }
        }
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

    SpeedSearchTarget search() {
        return this.search;
    }

    /** Expands or collapses the entry in {@code row}. */
    void toggle(int row) {
        DataRows.Row entry = this.rows.get(row);
        if (!entry.expandable()) return;
        if (!this.toggled.remove(entry.key())) this.toggled.add(entry.key());
        showRows();
    }

    /** The SNBT of every single value, keyed by row key, to find the values a newer read changed. */
    private static Map<String, String> leafValues(List<DataRows.Decoded> roots) {
        Map<String, String> values = new HashMap<>();
        for (DataRows.Row row : DataRows.all(roots)) {
            if (row.kind() == DataRows.Kind.ENTRY && !row.expandable()) values.put(row.key(), row.value());
        }
        return values;
    }

    private void showRows() {
        String selected = selectedKey();
        Anchor anchor = anchor();
        this.rows = DataRows.visible(this.roots, this.toggled);
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

    private void select(int row) {
        this.table.getSelectionModel().setSelectionInterval(row, row);
        this.table.scrollRectToVisible(this.table.getCellRect(row, 0, true));
    }

    private void showMode() {
        ((CardLayout) this.cards.getLayout()).show(this.cards, this.textMode.isSelected() ? TEXT_CARD : TREE_CARD);
        if (this.textMode.isSelected()) showText();
    }

    /** Shows the root holding the selection, or the first root, as indented SNBT. */
    private void showText() {
        int row = this.table.getSelectedRow();
        int root = row >= 0 && row < this.rows.size() ? this.rows.get(row).root() : 0;
        this.textHeading.setIcon(null);
        if (root >= this.roots.size()) {
            this.textHeading.setText("No data");
            this.text.setContent("");
            return;
        }
        DataRows.Decoded decoded = this.roots.get(root);
        if (decoded.tag() == null) {
            this.textHeading.setIcon(Icons.ERROR);
            this.textHeading.setText(decoded.root().name() + ": " + decoded.problem());
            this.text.setContent("");
            return;
        }
        int omitted = DataRows.omittedBelow(decoded.root().data(), "");
        this.textHeading.setText(omitted > 0 ? decoded.root().name() + ": incomplete, " + omitted
                + " entries were not transferred" : decoded.root().name());
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
                    select(parent);
                    return;
                }
            }
        });
        ContextMenus.installTable(this.table, this::menu);
    }

    private void bindKey(int key, String name, Runnable action) {
        this.table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key, 0), name);
        this.table.getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { action.run(); }
        });
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

    /** Typing searches all entries, collapsed ones included; selecting a match expands the entries above it. */
    private final class EntrySearch implements SpeedSearchTarget {
        private final List<Runnable> listeners = new ArrayList<>();

        private List<DataRows.Row> entries() {
            if (DataView.this.entries == null) {
                DataView.this.entries = DataRows.all(DataView.this.roots);
            }
            return DataView.this.entries;
        }

        @Override public JComponent component() { return DataView.this.table; }

        @Override public int size() { return entries().size(); }

        @Override
        public String textAt(int index) {
            DataRows.Row entry = entries().get(index);
            return entry.expandable() ? entry.name() : entry.name() + " " + entry.value();
        }

        @Override
        public int selectedIndex() {
            String key = selectedKey();
            if (key == null) return -1;
            List<DataRows.Row> all = entries();
            for (int index = 0; index < all.size(); index++) {
                if (all.get(index).key().equals(key)) return index;
            }
            return -1;
        }

        @Override
        public void select(int index) {
            DataRows.Row entry = entries().get(index);
            DataRows.Root root = DataView.this.roots.get(entry.root()).root();
            for (String ancestor : DataRows.ancestorKeys(root, entry)) {
                boolean rootEntry = ancestor.equals(DataRows.key(root, List.of()));
                if (rootEntry) DataView.this.toggled.remove(ancestor);
                else DataView.this.toggled.add(ancestor);
            }
            showRows();
            int row = indexOf(entry.key());
            if (row >= 0) DataView.this.select(row);
        }

        @Override public void installContentListener(Runnable listener) { this.listeners.add(listener); }

        @Override public void dispose() { this.listeners.clear(); }

        private void contentChanged() {
            this.listeners.forEach(Runnable::run);
        }
    }

    private final class RowsModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"Key", "Type", "Value"};

        @Override public int getRowCount() { return DataView.this.rows.size(); }

        @Override public int getColumnCount() { return COLUMNS.length; }

        @Override public String getColumnName(int column) { return COLUMNS[column]; }

        @Override public Object getValueAt(int row, int column) { return DataView.this.rows.get(row); }
    }

    /** Cell renderer that marks the text matching the speed search. */
    private abstract class RowRenderer extends DefaultTableCellRenderer {
        private boolean highlight;

        @Override
        protected void paintComponent(Graphics graphics) {
            List<SpeedSearch.MatchRange> matches = this.highlight
                    ? SpeedSearch.matchingRanges(DataView.this.table, getText()) : List.of();
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
                for (SpeedSearch.MatchRange range : matches) {
                    int start = x + metrics.stringWidth(getText().substring(0, range.start()));
                    int width = metrics.stringWidth(getText().substring(range.start(), range.end()));
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
            // Colors set on this renderer become its defaults; reset them so one row's colors never carry over.
            setForeground(null);
            setBackground(null);
            super.getTableCellRendererComponent(table, "", selected, false, row, column);
            DataRows.Row entry = (DataRows.Row) value;
            setIcon(null);
            setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
            this.highlight = column != 1;
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
            Font font = DataView.this.table.getFont();
            setFont(font);
            if (row.kind() != DataRows.Kind.ENTRY) {
                setIcon(row.kind() == DataRows.Kind.OMITTED ? null : Icons.ERROR);
                setForeground(muted(selected));
            } else if (!selected && row.depth() > 0) {
                setForeground(row.name().startsWith("[") ? ThemeColors.mutedText() : ThemeManager.palette().field());
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
