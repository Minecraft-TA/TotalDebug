package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogIndex;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ListedPack;
import com.github.minecraft_ta.totalDebugCompanion.catalog.PackFolders;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.ui.ContextMenus;
import com.github.minecraft_ta.totalDebugCompanion.ui.Tooltip;
import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.BrowserBody;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.Tables;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.text.JTextComponent;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Resource packs or datapacks as the game's pack screen lists them: enabled ones with the highest first, then the rest,
 * and for datapacks the packs in the world's folder the game enables when it loads the world next. Opening a pack of its
 * own folder or zip file opens its page; opening a pack a mod brings opens the mod's page.
 */
final class PacksPanel extends JPanel {
    /** Packs Minecraft itself brings: vanilla and its optional feature and resource packs. */
    private static final Set<String> MINECRAFT = Set.of("vanilla", "bundle", "trade_rebalance", "programmer_art", "high_contrast");

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

    PacksPanel(Side side, Consumer<NavigationTarget> navigator) {
        super(new BorderLayout());
        this.side = side;
        this.navigator = navigator;
        this.table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        Tables.configure(this.table);
        this.table.setDefaultRenderer(Object.class, new PackRenderer());
        ToolTipManager.sharedInstance().registerComponent(this.table);
        ContextMenus.installTable(this.table, this::menu);
        this.table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                int row = PacksPanel.this.table.rowAtPoint(event.getPoint());
                if (row >= 0 && event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)) {
                    open(PacksPanel.this.model.shown.get(row));
                }
            }
        });
        this.table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "openPack");
        this.table.getActionMap().put("openPack", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                int row = PacksPanel.this.table.getSelectedRow();
                if (row >= 0) open(PacksPanel.this.model.shown.get(row));
            }
        });
        int[] weights = {45, 35, 20};
        for (int column = 0; column < weights.length; column++) {
            this.table.getColumnModel().getColumn(column).setPreferredWidth(weights[column] * 10);
        }
        this.body = new BrowserBody("Filter by pack, mod or state", BrowserBody.scroll(this.table), this.table, this::applyFilter);
        add(this.body, BorderLayout.CENTER);
    }

    /** Shows the packs, naming their mods from {@code index} when it is captured; the selected ones stay selected. */
    void setPacks(List<ListedPack> packs, CatalogIndex index) {
        Set<String> selected = new HashSet<>();
        for (int row : this.table.getSelectedRows()) selected.add(this.model.shown.get(row).pack().id());
        List<Row> rows = new ArrayList<>();
        for (ListedPack pack : packs) rows.add(row(pack, index, this.side));
        this.model.all = List.copyOf(rows);
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
        this.model.all = List.of();
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

    private void applyFilter() {
        String query = this.body.query().toLowerCase(Locale.ROOT);
        List<Row> shown = new ArrayList<>();
        for (Row row : this.model.all) {
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
        if (empty) this.body.showMessage(this.model.all.isEmpty() ? this.side.none : this.side.noMatch);
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
        return tooltip.html();
    }

    private JPopupMenu menu(int viewRow) {
        List<Row> selected = new ArrayList<>();
        for (int row : this.table.getSelectedRows()) selected.add(this.model.shown.get(row));
        if (viewRow < 0 || selected.isEmpty()) return null;
        JPopupMenu menu = new JPopupMenu();
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
        if (menu.getComponentCount() > 0) menu.addSeparator();
        menu.add(ContextMenus.defaultCopy(ContextMenus.copyAction("Copy ID", row.pack().id())));
        if (row.pack().file() != null) menu.add(ContextMenus.copyAction("Copy Path", row.pack().file().toString()));
        return menu;
    }

    int rowCount() {
        return this.model.getRowCount();
    }

    JTextComponent filterField() {
        return this.body.filter();
    }

    private static final class PacksModel extends AbstractTableModel {
        private List<Row> all = List.of();
        private List<Row> shown = List.of();

        @Override
        public int getRowCount() {
            return this.shown.size();
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> "Pack";
                case 1 -> "From";
                default -> "State";
            };
        }

        @Override
        public Object getValueAt(int rowIndex, int column) {
            Row row = this.shown.get(rowIndex);
            return switch (column) {
                case 0 -> row.name();
                case 1 -> row.from();
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
            if (!selected) setForeground(column == 0 ? ThemeColors.text() : ThemeColors.secondaryText());
            return this;
        }
    }
}
