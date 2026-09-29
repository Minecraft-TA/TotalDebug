package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogIndex;
import com.github.minecraft_ta.totalDebugCompanion.catalog.PackCatalogService;
import com.github.minecraft_ta.totalDebugCompanion.change.ChangeLabels;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totalDebugCompanion.ui.ContextMenus;
import com.github.minecraft_ta.totalDebugCompanion.ui.Tooltip;
import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconTextField;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.PageLoader;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.TabTitles;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.Tables;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.TypeToFilter;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * What Companion changed in the pack, as its change record keeps it: a tab for each category that has changes, whose
 * rows the category names ({@link ChangeLabels}), with the value now and before. A row opens where it is changed, and
 * Revert puts back what it replaced, through the category. A change whose target holds its original value again, such
 * as one written back elsewhere, leaves the record when it is read. The page names no category itself.
 */
public final class ChangesPanel extends JPanel {
    private static final String TABS_CARD = "tabs";
    private static final String MESSAGE_CARD = "message";
    private static final String[] COLUMNS = {"Name", "Where", "Now", "Before", "Changed"};

    /** What a read found: each category's rows, and what could not be read. */
    private record Loaded(Map<ChangeLabels, List<ChangeLabels.Row>> rows, List<String> problems, CatalogIndex index) {
    }

    /** One category's tab: its table of rows. */
    private final class Category {
        final ChangeLabels labels;
        final RowsModel model = new RowsModel();
        final JTable table = new JTable(this.model);
        final JScrollPane scroll = new JScrollPane(this.table);

        Category(ChangeLabels labels) {
            this.labels = labels;
            this.scroll.setBorder(BorderFactory.createEmptyBorder());
            this.table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
            Tables.configure(this.table);
            this.table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
                @Override
                public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focused,
                                                               int row, int column) {
                    super.getTableCellRendererComponent(table, value, selected, false, row, column);
                    ChangeLabels.Row shown = Category.this.model.shown.get(row);
                    setBorder(UiMetrics.cellPadding());
                    setForeground(selected ? table.getSelectionForeground() : column == 0 && !shown.notice().isEmpty() ? ThemeColors.warning()
                            : column == 0 ? ThemeColors.text() : ThemeColors.secondaryText());
                    setToolTipText(tooltip(shown));
                    return this;
                }
            });
            ContextMenus.installTable(this.table, this::menu);
            this.table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "revertChanges");
            this.table.getActionMap().put("revertChanges", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent event) {
                    List<ChangeLabels.Row> selected = selected();
                    if (!selected.isEmpty()) revertAsked(selected);
                }
            });
            this.table.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent event) {
                    int row = Category.this.table.rowAtPoint(event.getPoint());
                    if (row >= 0 && event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)) {
                        open(Category.this.model.shown.get(row));
                    }
                }
            });
            TypeToFilter.install(this.table, ChangesPanel.this.filter);
        }

        private JPopupMenu menu(int row) {
            if (row < 0) return null;
            List<ChangeLabels.Row> selected = selected();
            JPopupMenu menu = new JPopupMenu();
            ChangeLabels.Row clicked = this.model.shown.get(row);
            if (selected.size() <= 1 && clicked.opens() != null) {
                menu.add(ContextMenus.action(clicked.actions().open(), null, null, () -> open(clicked)));
            }
            List<ChangeLabels.Row> reverted = selected.isEmpty() ? List.of(clicked) : selected;
            menu.add(ContextMenus.action(reverted.size() > 1 ? "Revert " + reverted.size() : reverted.getFirst().actions().revert(), null,
                    "DELETE", () -> revertAsked(reverted)));
            return menu;
        }

        /** Reverts {@code rows}, after asking where reverting one cannot be undone, such as a file being deleted. */
        private void revertAsked(List<ChangeLabels.Row> rows) {
            List<String> confirms = rows.stream().map(row -> row.actions().confirm()).filter(confirm -> !confirm.isEmpty()).toList();
            if (!confirms.isEmpty()) {
                String question = confirms.size() == 1 ? confirms.getFirst() : confirms.size() + " of these are deleted and cannot be brought back. Revert them?";
                int answer = JOptionPane.showConfirmDialog(ChangesPanel.this, question, rows.size() > 1 ? "Revert " + rows.size() : rows.getFirst().actions().revert(),
                        JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
                if (answer != JOptionPane.OK_OPTION) return;
            }
            report(revert(rows));
        }

        private List<ChangeLabels.Row> selected() {
            List<ChangeLabels.Row> rows = new ArrayList<>();
            for (int row : this.table.getSelectedRows()) rows.add(this.model.shown.get(row));
            return rows;
        }

        CompletableFuture<String> revert(List<ChangeLabels.Row> rows) {
            return this.labels.revert(rows.stream().map(ChangeLabels.Row::change).toList(), ChangesPanel.this.index);
        }
    }

    private final PackCatalogService catalog;
    private final ChangeRecord record;
    private final List<Category> categories = new ArrayList<>();
    private final Consumer<NavigationTarget> navigator;
    private final PageLoader<Loaded> loader;
    private final FlatIconTextField filter = new FlatIconTextField(Icons.SEARCH_ICON);
    private final JButton revertAll = new JButton("Revert All");
    private final JLabel notice = new JLabel();
    private final JTabbedPane tabs = new JTabbedPane();
    private final JLabel message = new JLabel();
    private final JPanel cards = new JPanel(new CardLayout());
    private CatalogIndex index;
    private String problem = "";
    private String status = "";

    /** Lists the changes {@code record} keeps through {@code categories}, which name and revert them. */
    public ChangesPanel(PackCatalogService catalog, ChangeRecord record, List<ChangeLabels> categories, Consumer<NavigationTarget> navigator) {
        super(new BorderLayout());
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.record = Objects.requireNonNull(record, "record");
        this.navigator = Objects.requireNonNull(navigator, "navigator");

        this.filter.putClientProperty("JTextField.placeholderText", "Filter changes");
        this.filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { applyFilter(); }
            @Override public void removeUpdate(DocumentEvent event) { applyFilter(); }
            @Override public void changedUpdate(DocumentEvent event) { applyFilter(); }
        });
        this.revertAll.setToolTipText(Tooltip.of("Revert All").text("Puts back what every change replaced").html());
        this.revertAll.addActionListener(event -> revertAll());
        JPanel bar = new JPanel(new BorderLayout(10, 0));
        bar.setBorder(UiMetrics.barPadding());
        bar.add(this.filter, BorderLayout.CENTER);
        bar.add(this.revertAll, BorderLayout.EAST);
        ThemeColors.keepForeground(this.notice, ThemeColors::secondaryText);
        this.notice.setBorder(UiMetrics.noticePadding());
        this.notice.setVisible(false);
        JPanel top = new JPanel(new BorderLayout());
        top.add(bar, BorderLayout.NORTH);
        top.add(this.notice, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        for (ChangeLabels labels : categories) this.categories.add(new Category(labels));
        this.cards.add(this.tabs, TABS_CARD);
        this.message.setVerticalAlignment(JLabel.TOP);
        this.message.setBorder(UiMetrics.messagePadding());
        this.cards.add(this.message, MESSAGE_CARD);
        add(this.cards, BorderLayout.CENTER);

        this.loader = new PageLoader<>(this::prepareLoad, this::show,
                failure -> setStatus("Could not read the changes: " + failure.getMessage()))
                .whenShown(this).follow(this.record::addListener);
        load();
    }

    public void load() {
        this.loader.load();
    }

    /** Reads what each category's changed targets hold now, naming mods from the catalog. */
    private Callable<Loaded> prepareLoad() {
        CatalogIndex index = this.catalog.index().orElse(null);
        List<ChangeRecord.Change> recorded = this.record.changes();
        List<ChangeLabels> labels = this.categories.stream().map(category -> category.labels).toList();
        return () -> {
            Map<ChangeLabels, List<ChangeLabels.Row>> rows = new LinkedHashMap<>();
            List<String> problems = new ArrayList<>();
            for (ChangeLabels category : labels) {
                List<ChangeRecord.Change> changes = recorded.stream().filter(change -> category.covers(change.target())).toList();
                if (changes.isEmpty()) continue;
                ChangeLabels.Rows read = category.rows(changes, index);
                rows.put(category, read.rows());
                problems.addAll(read.problems());
            }
            return new Loaded(rows, problems, index);
        };
    }

    private void show(Loaded loaded) {
        this.index = loaded.index();
        this.problem = String.join("; ", loaded.problems());
        showNotice();
        this.tabs.removeAll();
        int total = 0;
        for (Category category : this.categories) {
            List<ChangeLabels.Row> rows = loaded.rows().getOrDefault(category.labels, List.of());
            category.model.setRows(rows);
            total += rows.size();
            if (rows.isEmpty()) continue;
            this.tabs.addTab(category.labels.tab(), category.scroll);
            TabTitles.setCounted(this.tabs, this.tabs.getTabCount() - 1, category.labels.tab(), rows.size());
        }
        this.revertAll.setEnabled(total > 0);
        applyFilter();
    }

    private void open(ChangeLabels.Row row) {
        if (row.opens() != null) this.navigator.accept(row.opens());
    }

    /** Puts back what every listed change replaced, after asking. */
    private void revertAll() {
        int total = this.categories.stream().mapToInt(category -> category.model.all.size()).sum();
        if (total == 0) return;
        int answer = JOptionPane.showConfirmDialog(this,
                "Put back the original value of " + total + (total == 1 ? " change?" : " changes?"),
                "Revert All", JOptionPane.OK_CANCEL_OPTION);
        if (answer != JOptionPane.OK_OPTION) return;
        List<CompletableFuture<String>> reverts = new ArrayList<>();
        for (Category category : this.categories) {
            if (!category.model.all.isEmpty()) reverts.add(category.revert(category.model.all));
        }
        // One status once every category is done, so a later success never hides an earlier failure.
        report(CompletableFuture.allOf(reverts.toArray(CompletableFuture[]::new)).thenApply(ignored -> String.join("; ",
                reverts.stream().map(CompletableFuture::join).filter(failure -> !failure.isEmpty()).toList())));
    }

    private void report(CompletableFuture<String> reverted) {
        reverted.whenComplete((failure, error) -> SwingUtilities.invokeLater(() ->
                setStatus(error != null ? "Not reverted: " + error.getMessage() : failure)));
    }

    private static String tooltip(ChangeLabels.Row row) {
        Tooltip tooltip = Tooltip.of(row.name());
        if (!row.where().isEmpty()) tooltip.detail(row.where());
        tooltip.fact("Now", row.now()).fact("Before", row.before()).fact("Changed", ago(row.changed()));
        if (!row.notice().isEmpty()) tooltip.text(row.notice());
        return tooltip.html();
    }

    static String ago(Instant time) {
        Duration elapsed = Duration.between(time, Instant.now());
        if (elapsed.toMinutes() < 1) return "just now";
        if (elapsed.toHours() < 1) return plural(elapsed.toMinutes(), "minute") + " ago";
        if (elapsed.toDays() < 1) return plural(elapsed.toHours(), "hour") + " ago";
        return plural(elapsed.toDays(), "day") + " ago";
    }

    private static String plural(long count, String unit) {
        return count + " " + unit + (count == 1 ? "" : "s");
    }

    private void setStatus(String status) {
        this.status = status;
        showNotice();
    }

    private void showNotice() {
        String text = this.problem.isEmpty() ? this.status : this.problem;
        this.notice.setText(text);
        this.notice.setVisible(!text.isEmpty());
    }

    private void applyFilter() {
        String query = this.filter.getText().strip().toLowerCase(Locale.ROOT);
        for (Category category : this.categories) category.model.filter(query);
        boolean empty = this.tabs.getTabCount() == 0;
        this.message.setText(!query.isBlank() ? "No change matches the filter." : "Companion has not changed anything in this pack.");
        ((CardLayout) this.cards.getLayout()).show(this.cards, empty ? MESSAGE_CARD : TABS_CARD);
    }

    /** The rows shown in the tab named {@code tab}, for tests. */
    List<ChangeLabels.Row> rows(String tab) {
        for (Category category : this.categories) {
            if (category.labels.tab().equals(tab)) return category.model.shown;
        }
        return List.of();
    }

    public void dispose() {
        this.loader.dispose();
    }

    /** A category's rows, those the filter shows. */
    private static final class RowsModel extends AbstractTableModel {
        private List<ChangeLabels.Row> all = List.of();
        private List<ChangeLabels.Row> shown = List.of();
        private String query = "";

        void setRows(List<ChangeLabels.Row> rows) {
            this.all = List.copyOf(rows);
            filter(this.query);
        }

        void filter(String query) {
            this.query = query;
            this.shown = query.isEmpty() ? this.all : this.all.stream().filter(row -> matches(row, query)).toList();
            fireTableDataChanged();
        }

        private static boolean matches(ChangeLabels.Row row, String query) {
            String squashed = squash(query);
            for (String text : List.of(row.name(), row.where(), row.now(), row.before(), row.actions().search())) {
                String lower = text.toLowerCase(Locale.ROOT);
                if (lower.contains(query) || !squashed.isEmpty() && squash(lower).contains(squashed)) return true;
            }
            return false;
        }

        /** {@code text} without spaces and plus signs, so a key typed as {@code ctrl+g} finds "Ctrl + G". */
        private static String squash(String text) {
            return text.replace(" ", "").replace("+", "").replace("control", "ctrl");
        }

        @Override
        public int getRowCount() {
            return this.shown.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            ChangeLabels.Row change = this.shown.get(row);
            return switch (column) {
                case 0 -> change.name();
                case 1 -> change.where();
                case 2 -> change.now();
                case 3 -> change.before();
                default -> ago(change.changed());
            };
        }
    }
}
