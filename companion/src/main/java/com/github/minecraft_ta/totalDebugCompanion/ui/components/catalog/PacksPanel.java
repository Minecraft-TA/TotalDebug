package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogIndex;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ListedPack;
import com.github.minecraft_ta.totalDebugCompanion.catalog.PackFolders;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.pack.PackSelections;
import com.github.minecraft_ta.totalDebugCompanion.ui.ContextMenus;
import com.github.minecraft_ta.totalDebugCompanion.ui.Tooltip;
import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.BrowserBody;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.Tables;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.DropMode;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.TransferHandler;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import javax.swing.text.JTextComponent;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/**
 * Resource packs or datapacks as the game's pack screen lists them: enabled ones with the highest first, then the rest;
 * for datapacks, the packs in the world's folder the game enables at the top when it loads the world next come first.
 * Opening a pack of its own folder or zip file opens its page; opening a pack a mod brings opens the mod's page.
 *
 * <p>With an {@link Applier}, the check box of a row enables or disables its pack, and enabled rows are dragged, or moved
 * with Alt+Shift+Up and Down, to reorder them. The changes are kept until Apply writes them, as the pack screen's Done
 * does, or Discard drops them; the game's rules hold: a required pack stays enabled, a fixed one in its place, the parts
 * of the mods' pack go with it, and a datapack needing features the world lacks cannot be enabled.
 */
final class PacksPanel extends JPanel {
    /** Packs Minecraft itself brings: vanilla and its optional feature and resource packs. */
    private static final Set<String> MINECRAFT = Set.of("vanilla", "bundle", "trade_rebalance", "programmer_art", "high_contrast");
    private static final int CHECK_COLUMN = 0;

    /** Which packs a list shows, with the folder they are kept in and how its empty list reads. */
    enum Side {
        RESOURCES("Resource packs folder", "The instance has no resource packs.", "No resource pack matches the filter."),
        DATA("World folder", "The world has no datapacks.", "No datapack matches the filter.");

        private final String folder;
        private final String none;
        private final String noMatch;

        Side(String folder, String none, String noMatch) {
            this.folder = folder;
            this.none = none;
            this.noMatch = noMatch;
        }
    }

    /** Writes a selection: the enabled packs, lowest first; completes with where it went, or fails saying why. */
    interface Applier {
        CompletableFuture<PackSelections.Applied> apply(List<String> enabled);
    }

    /** A pack with its name, where it comes from, and the mod it belongs to, or empty. */
    record Row(ListedPack pack, String name, String from, String modId) {
    }

    private final Side side;
    private final Consumer<NavigationTarget> navigator;
    private final PacksModel model = new PacksModel();
    private final JTable table = new JTable(this.model) {
        @Override
        public String getToolTipText(MouseEvent event) {
            int row = rowAtPoint(event.getPoint());
            return row < 0 ? null : tooltip(PacksPanel.this.model.shown.get(row));
        }
    };
    private final BrowserBody body;
    private final JButton apply = new JButton("Apply", Icons.SAVE);
    private final JButton discard = new JButton("Discard", Icons.REVERT);
    private final JPanel changes = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
    private Applier applier;
    /** The packs as last listed, in their order, and the ids enabled among them. */
    private List<Row> listed = List.of();
    private Set<String> listedOn = Set.of();
    /** The order and enabled ids shown, which differ from those listed while there are changes to apply. */
    private List<Row> order = List.of();
    private Set<String> on = new LinkedHashSet<>();
    private boolean applying;

    PacksPanel(Side side, Consumer<NavigationTarget> navigator) {
        super(new BorderLayout());
        this.side = side;
        this.navigator = navigator;
        this.table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        Tables.configure(this.table);
        this.table.setDefaultRenderer(Object.class, new PackRenderer());
        this.table.setDefaultRenderer(Boolean.class, new CheckRenderer());
        ToolTipManager.sharedInstance().registerComponent(this.table);
        ContextMenus.installTable(this.table, this::menu);
        this.table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                int row = PacksPanel.this.table.rowAtPoint(event.getPoint());
                if (row < 0 || !SwingUtilities.isLeftMouseButton(event)) return;
                if (PacksPanel.this.table.columnAtPoint(event.getPoint()) == CHECK_COLUMN && event.getClickCount() == 1) {
                    toggle(List.of(PacksPanel.this.model.shown.get(row)));
                } else if (event.getClickCount() == 2) {
                    open(PacksPanel.this.model.shown.get(row));
                }
            }
        });
        bind("ENTER", "openPack", () -> {
            int row = this.table.getSelectedRow();
            if (row >= 0) open(this.model.shown.get(row));
        });
        bind("SPACE", "togglePacks", () -> toggle(selectedRows()));
        bind("alt shift UP", "movePackUp", () -> move(-1));
        bind("alt shift DOWN", "movePackDown", () -> move(1));
        this.table.setDragEnabled(true);
        this.table.setDropMode(DropMode.INSERT_ROWS);
        this.table.setTransferHandler(new ReorderHandler());
        int[] widths = {3, 45, 35, 20};
        for (int column = 0; column < widths.length; column++) {
            this.table.getColumnModel().getColumn(column).setPreferredWidth(widths[column] * 10);
        }
        int check = UiMetrics.cellPadding().getBorderInsets(this.table).left * 2 + new JCheckBox().getPreferredSize().width;
        this.table.getColumnModel().getColumn(CHECK_COLUMN).setMaxWidth(check);
        this.table.getColumnModel().getColumn(CHECK_COLUMN).setMinWidth(check);
        this.body = new BrowserBody("Filter by pack, mod or state", BrowserBody.scroll(this.table), this.table, this::applyFilter);
        this.apply.addActionListener(event -> applyChanges());
        this.discard.setToolTipText(Tooltip.action("Discard", null).text("Drops the changes to the packs").html());
        this.discard.addActionListener(event -> discardChanges());
        this.changes.setOpaque(false);
        this.changes.add(this.apply);
        this.changes.add(this.discard);
        this.changes.setVisible(false);
        this.body.addOption(this.changes);
        add(this.body, BorderLayout.CENTER);
    }

    private void bind(String keys, String name, Runnable action) {
        this.table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(keys), name);
        this.table.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                action.run();
            }
        });
    }

    /**
     * Lets the packs be enabled and ordered, written by {@code applier}; {@code where} says in the Apply tooltip where
     * the change goes, such as into the running game.
     */
    void setApplier(Applier applier, String where) {
        this.applier = applier;
        this.apply.setToolTipText(Tooltip.action("Apply", null).text(where).html());
        this.model.fireTableDataChanged();
    }

    /**
     * Shows the packs, naming their mods from {@code index} when it is captured; the selected ones stay selected, and
     * changes not applied yet stay, for the packs still listed.
     */
    void setPacks(List<ListedPack> packs, CatalogIndex index) {
        Set<String> selected = new HashSet<>();
        for (Row row : selectedRows()) selected.add(row.pack().id());
        List<Row> rows = new ArrayList<>();
        // A new datapack of the world's folder is enabled at the top when the world loads, so it counts as enabled there.
        for (ListedPack pack : packs) {
            if (pack.state() == ListedPack.State.NEW) rows.add(row(pack, index, this.side));
        }
        for (ListedPack pack : packs) {
            if (pack.state() != ListedPack.State.NEW) rows.add(row(pack, index, this.side));
        }
        boolean changed = changed();
        this.listed = List.copyOf(rows);
        this.listedOn = new LinkedHashSet<>(rows.stream().filter(row -> row.pack().state() != ListedPack.State.DISABLED)
                .map(row -> row.pack().id()).toList());
        if (!changed) {
            this.order = this.listed;
            this.on = new LinkedHashSet<>(this.listedOn);
        } else {
            // The changes keep their order; packs gone since leave it, and new ones join it where they are listed.
            List<Row> kept = new ArrayList<>();
            for (Row row : this.order) {
                rows.stream().filter(fresh -> fresh.pack().id().equals(row.pack().id())).findFirst().ifPresent(kept::add);
            }
            for (Row row : rows) {
                if (kept.stream().noneMatch(old -> old.pack().id().equals(row.pack().id()))) kept.add(row);
            }
            this.order = List.copyOf(kept);
            this.on.retainAll(rows.stream().map(row -> row.pack().id()).toList());
        }
        showChanges();
        applyFilter();
        for (int row = 0; row < this.model.shown.size(); row++) {
            if (selected.contains(this.model.shown.get(row).pack().id())) this.table.addRowSelectionInterval(row, row);
        }
    }

    /**
     * A pack's name and origin from its id: {@code file/} and a file name for the side's folder, {@code mod/} and
     * the ids of a mod file for a pack a mod brings, {@code mod_resources} and {@code mod_data} for the resources and
     * data of every mod, and a namespace before a colon for a pack a mod adds in code.
     */
    static Row row(ListedPack pack, CatalogIndex index, Side side) {
        Row row = fromId(pack, index, side);
        // The running game's title, such as Programmer Art, names a pack better than its id; a file's name names its own.
        return pack.file() != null || pack.title().isEmpty() ? row : new Row(pack, pack.title(), row.from(), row.modId());
    }

    private static Row fromId(ListedPack pack, CatalogIndex index, Side side) {
        String id = pack.id();
        if (pack.file() != null) return new Row(pack, PackFolders.title(pack.file()), side.folder, "");
        if (id.startsWith("file/")) return new Row(pack, id.substring("file/".length()), side.folder, "");
        if (MINECRAFT.contains(id)) return new Row(pack, id, "Minecraft", "");
        if (id.equals("mod_resources") || id.equals("mod_data")) return new Row(pack, id, "Every mod", "");
        String name = id;
        String owner = "";
        if (id.startsWith("mod/")) {
            int colon = id.indexOf(':');
            String mods = colon < 0 ? id.substring("mod/".length()) : id.substring("mod/".length(), colon);
            owner = mods.split(",")[0];
            if (colon >= 0) name = id.substring(id.lastIndexOf('/') + 1);
        } else if (id.indexOf(':') > 0) {
            owner = id.substring(0, id.indexOf(':'));
        }
        if (owner.isEmpty() || index == null || index.mod(owner).isEmpty()) return new Row(pack, name, owner, "");
        return new Row(pack, name, index.mod(owner).map(PackCatalog.Mod::title).orElse(owner), owner);
    }

    /** Shows why the packs could not be listed, in place of the list. */
    void showFailure(String message) {
        this.listed = List.of();
        this.order = List.of();
        this.model.shown = List.of();
        this.model.fireTableDataChanged();
        this.body.showMessage(message);
    }

    static String state(ListedPack.State state) {
        return switch (state) {
            case ENABLED -> "Enabled";
            case DISABLED -> "Disabled";
            case NEW -> "Enabled on next load";
        };
    }

    /** Whether the shown order or enabled packs differ from those listed. */
    boolean changed() {
        return !this.on.equals(this.listedOn) || !enabledOrder(this.order, this.on).equals(enabledOrder(this.listed, this.listedOn));
    }

    /** The enabled packs of {@code rows}, lowest first. */
    private static List<String> enabledOrder(List<Row> rows, Set<String> on) {
        return rows.reversed().stream().map(row -> row.pack().id()).filter(on::contains).toList();
    }

    /** Why the check box of {@code row} cannot change, or empty when it can. */
    private String locked(Row row, boolean enable) {
        if (this.applier == null) return "Packs cannot be changed here";
        if (this.applying) return "Applying the last change";
        ListedPack pack = row.pack();
        if (pack.is(ListedPack.Rule.PART_OF_MODS)) return "Part of the mods' pack, which the game orders as one";
        if (!enable && pack.is(ListedPack.Rule.REQUIRED)) return "The game keeps it enabled";
        if (enable && pack.is(ListedPack.Rule.MISSING_FEATURES)) return "Needs features the world does not have";
        return "";
    }

    /** Enables the disabled ones of {@code rows} at the top, or disables them all when every one is enabled. */
    private void toggle(List<Row> rows) {
        if (rows.isEmpty()) return;
        boolean enable = rows.stream().anyMatch(row -> !this.on.contains(row.pack().id()));
        List<Row> order = new ArrayList<>(this.order);
        for (Row row : rows) {
            if (!locked(row, enable).isEmpty() || this.on.contains(row.pack().id()) == enable) continue;
            order.remove(row);
            if (enable) {
                // Enabled at the top, as the pack screen adds it, below packs fixed there.
                int top = 0;
                while (top < order.size() && this.on.contains(order.get(top).pack().id()) && order.get(top).pack().is(ListedPack.Rule.FIXED)) top++;
                order.add(top, row);
                this.on.add(row.pack().id());
            } else {
                this.on.remove(row.pack().id());
                // Disabled at the top of the rest.
                int first = 0;
                while (first < order.size() && this.on.contains(order.get(first).pack().id())) first++;
                order.add(first, row);
            }
        }
        this.order = List.copyOf(order);
        refreshKeepingSelection(rows);
    }

    /** Moves the selected enabled pack one place up, {@code -1}, or down, {@code 1}. */
    private void move(int by) {
        List<Row> selected = selectedRows();
        if (selected.size() != 1) return;
        int from = this.order.indexOf(selected.getFirst());
        moveTo(selected.getFirst(), from + by + (by > 0 ? 1 : 0));
    }

    /** Whether {@code row} can be moved: an enabled pack that is not fixed, while the list is not filtered. */
    private boolean movable(Row row) {
        return this.applier != null && !this.applying && this.body.query().isEmpty() && this.on.contains(row.pack().id())
                && !row.pack().is(ListedPack.Rule.FIXED) && !row.pack().is(ListedPack.Rule.PART_OF_MODS);
    }

    /**
     * Moves {@code row} to stand before {@code target}, an index of the order, among the enabled packs and without
     * passing a fixed one; returns whether it moved.
     */
    private boolean moveTo(Row row, int target) {
        if (!movable(row)) return false;
        int from = this.order.indexOf(row);
        int enabled = (int) this.order.stream().filter(candidate -> this.on.contains(candidate.pack().id())).count();
        int to = Math.clamp(target, 0, enabled);
        int low = Math.min(from, to);
        int high = Math.max(from, to - 1);
        for (int index = low; index <= high && index < this.order.size(); index++) {
            if (this.order.get(index).pack().is(ListedPack.Rule.FIXED)) return false;
        }
        List<Row> order = new ArrayList<>(this.order);
        order.remove(from);
        order.add(to > from ? to - 1 : to, row);
        if (order.equals(this.order)) return false;
        this.order = List.copyOf(order);
        refreshKeepingSelection(List.of(row));
        return true;
    }

    private void refreshKeepingSelection(List<Row> rows) {
        showChanges();
        applyFilter();
        this.table.clearSelection();
        for (int index = 0; index < this.model.shown.size(); index++) {
            if (rows.contains(this.model.shown.get(index))) this.table.addRowSelectionInterval(index, index);
        }
    }

    private void showChanges() {
        boolean changed = changed();
        this.changes.setVisible(changed || this.applying);
        this.apply.setEnabled(changed && !this.applying);
        this.discard.setEnabled(!this.applying);
    }

    /** Writes the shown selection through the applier; a failure keeps the changes and says why. */
    void applyChanges() {
        if (this.applier == null || this.applying || !changed()) return;
        this.applying = true;
        showChanges();
        this.body.showNotice("");
        List<String> enabled = enabledOrder(this.order, this.on);
        this.applier.apply(enabled).whenComplete((applied, failure) -> SwingUtilities.invokeLater(() -> {
            this.applying = false;
            if (failure != null) {
                Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
                this.body.showNotice("Not applied: " + cause.getMessage());
            } else {
                // Listed as applied until the packs are read again.
                this.listed = this.order;
                this.listedOn = new LinkedHashSet<>(this.on);
            }
            showChanges();
            this.model.fireTableDataChanged();
        }));
    }

    void discardChanges() {
        if (this.applying) return;
        this.order = this.listed;
        this.on = new LinkedHashSet<>(this.listedOn);
        this.body.showNotice("");
        refreshKeepingSelection(selectedRows());
    }

    private List<Row> selectedRows() {
        List<Row> rows = new ArrayList<>();
        for (int row : this.table.getSelectedRows()) {
            if (row < this.model.shown.size()) rows.add(this.model.shown.get(row));
        }
        return rows;
    }

    private void applyFilter() {
        String query = this.body.query().toLowerCase(Locale.ROOT);
        List<Row> shown = new ArrayList<>();
        for (Row row : this.order) {
            if (query.isEmpty() || row.name().toLowerCase(Locale.ROOT).contains(query)
                    || row.pack().id().toLowerCase(Locale.ROOT).contains(query)
                    || row.from().toLowerCase(Locale.ROOT).contains(query)
                    || state(row.pack().state()).toLowerCase(Locale.ROOT).contains(query)) {
                shown.add(row);
            }
        }
        this.model.shown = List.copyOf(shown);
        this.model.fireTableDataChanged();
        boolean empty = shown.isEmpty();
        if (empty) this.body.showMessage(this.order.isEmpty() ? this.side.none : this.side.noMatch);
        else this.body.showContent();
    }

    /** Opens a pack of its own folder or zip file, or the mod a pack comes from. */
    private void open(Row row) {
        if (row.pack().file() != null) this.navigator.accept(new NavigationTarget.Pack(row.pack().file()));
        else if (!row.modId().isEmpty()) this.navigator.accept(new NavigationTarget.ModPage(row.modId()));
    }

    private String tooltip(Row row) {
        Tooltip tooltip = Tooltip.of(row.name()).detail(row.pack().id()).fact("State", state(row.pack().state()));
        if (!row.from().isEmpty()) tooltip.fact("From", row.from());
        if (row.pack().file() != null) tooltip.fact("File", Tooltip.shortPath(row.pack().file()));
        ListedPack pack = row.pack();
        if (pack.is(ListedPack.Rule.REQUIRED)) tooltip.text("The game keeps it enabled");
        if (pack.is(ListedPack.Rule.FIXED)) tooltip.text("It keeps its place in the order");
        if (pack.is(ListedPack.Rule.PART_OF_MODS)) tooltip.text("Part of the mods' pack, which the game orders as one");
        if (pack.is(ListedPack.Rule.INCOMPATIBLE)) tooltip.text("Made for another version of the game");
        if (pack.is(ListedPack.Rule.MISSING_FEATURES)) tooltip.text("Needs features the world does not have, so it cannot be enabled");
        return tooltip.html();
    }

    private JPopupMenu menu(int viewRow) {
        List<Row> selected = selectedRows();
        if (viewRow < 0 || selected.isEmpty()) return null;
        JPopupMenu menu = new JPopupMenu();
        if (this.applier != null) {
            boolean enable = selected.stream().anyMatch(row -> !this.on.contains(row.pack().id()));
            Action toggle = ContextMenus.action(enable ? "Enable" : "Disable", null, "SPACE", () -> toggle(selected));
            toggle.setEnabled(selected.stream().anyMatch(row -> locked(row, enable).isEmpty()));
            menu.add(toggle);
            if (selected.size() == 1) {
                Action up = ContextMenus.action("Move Up", null, "alt shift UP", () -> move(-1));
                Action down = ContextMenus.action("Move Down", null, "alt shift DOWN", () -> move(1));
                up.setEnabled(movable(selected.getFirst()));
                down.setEnabled(movable(selected.getFirst()));
                menu.add(up);
                menu.add(down);
            }
            menu.addSeparator();
        }
        if (selected.size() > 1) {
            List<String> ids = new ArrayList<>();
            for (Row row : selected) ids.add(row.pack().id());
            menu.add(ContextMenus.defaultCopy(ContextMenus.copyAction("Copy " + ids.size() + " IDs", String.join("\n", ids))));
            return menu;
        }
        Row row = selected.getFirst();
        if (row.pack().file() != null) {
            menu.add(ContextMenus.action("Open", null, "ENTER", () -> open(row)));
            menu.add(ContextMenus.action("Show in Explorer", null, null,
                    () -> this.body.showNotice(Explorer.show(row.pack().file()).orElse(""))));
        } else if (!row.modId().isEmpty()) {
            menu.add(ContextMenus.action("Open " + row.from(), null, "ENTER", () -> open(row)));
        }
        if (menu.getComponentCount() > 0 && !(menu.getComponent(menu.getComponentCount() - 1) instanceof JPopupMenu.Separator)) {
            menu.addSeparator();
        }
        menu.add(ContextMenus.defaultCopy(ContextMenus.copyAction("Copy ID", row.pack().id())));
        if (row.pack().file() != null) menu.add(ContextMenus.copyAction("Copy Path", row.pack().file().toString()));
        return menu;
    }

    int rowCount() {
        return this.model.getRowCount();
    }

    /** The ids shown, top first, with whether each is enabled, for tests. */
    List<String> shownIds() {
        return this.model.shown.stream().map(row -> (this.on.contains(row.pack().id()) ? "+" : "-") + row.pack().id()).toList();
    }

    JTable table() {
        return this.table;
    }

    JTextComponent filterField() {
        return this.body.filter();
    }

    private final class PacksModel extends AbstractTableModel {
        private List<Row> shown = List.of();

        @Override
        public int getRowCount() {
            return this.shown.size();
        }

        @Override
        public int getColumnCount() {
            return 4;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case CHECK_COLUMN -> "";
                case 1 -> "Pack";
                case 2 -> "From";
                default -> "State";
            };
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == CHECK_COLUMN ? Boolean.class : Object.class;
        }

        @Override
        public Object getValueAt(int rowIndex, int column) {
            Row row = this.shown.get(rowIndex);
            return switch (column) {
                case CHECK_COLUMN -> PacksPanel.this.on.contains(row.pack().id());
                case 1 -> row.name();
                case 2 -> row.from();
                default -> state(row.pack().state());
            };
        }
    }

    /** Pack names in regular text, their origin and state in secondary text. */
    private static final class PackRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focused,
                                                       int rowIndex, int column) {
            super.getTableCellRendererComponent(table, value, selected, false, rowIndex, column);
            setBorder(UiMetrics.cellPadding());
            if (!selected) setForeground(column == 1 ? ThemeColors.text() : ThemeColors.secondaryText());
            return this;
        }
    }

    /** A row's check box, off where the game's rules keep it as it is. */
    private final class CheckRenderer extends JCheckBox implements TableCellRenderer {
        CheckRenderer() {
            setHorizontalAlignment(SwingConstants.CENTER);
            setBorderPainted(false);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focused,
                                                       int rowIndex, int column) {
            Row row = PacksPanel.this.model.shown.get(rowIndex);
            boolean enabled = Boolean.TRUE.equals(value);
            setSelected(enabled);
            setEnabled(locked(row, !enabled).isEmpty());
            setOpaque(selected);
            setBackground(selected ? table.getSelectionBackground() : table.getBackground());
            return this;
        }
    }

    /** Drags an enabled pack to another place among the enabled ones. */
    private final class ReorderHandler extends TransferHandler {
        private Row dragged;

        @Override
        public int getSourceActions(JComponent component) {
            return MOVE;
        }

        @Override
        protected Transferable createTransferable(JComponent component) {
            List<Row> selected = selectedRows();
            this.dragged = selected.size() == 1 && movable(selected.getFirst()) ? selected.getFirst() : null;
            return this.dragged == null ? null : new StringSelection(this.dragged.pack().id());
        }

        @Override
        public boolean canImport(TransferSupport support) {
            return this.dragged != null && support.isDrop() && support.isDataFlavorSupported(DataFlavor.stringFlavor);
        }

        @Override
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) return false;
            int row = ((JTable.DropLocation) support.getDropLocation()).getRow();
            // The list is not filtered while a pack is dragged, so a shown row is a row of the order.
            return moveTo(this.dragged, row);
        }

        @Override
        protected void exportDone(JComponent source, Transferable data, int action) {
            this.dragged = null;
        }

        /** Ctrl+C copies the selected packs' ids, as the table did before it could reorder. */
        @Override
        public void exportToClipboard(JComponent component, Clipboard clipboard, int action) {
            List<String> ids = selectedRows().stream().map(row -> row.pack().id()).toList();
            if (!ids.isEmpty()) clipboard.setContents(new StringSelection(String.join("\n", ids)), null);
        }
    }
}
