package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.ClassBytecodeSource;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogIndex;
import com.github.minecraft_ta.totalDebugCompanion.catalog.MixinMember;
import com.github.minecraft_ta.totalDebugCompanion.catalog.MixinSelector;
import com.github.minecraft_ta.totalDebugCompanion.catalog.MixinTarget;
import com.github.minecraft_ta.totalDebugCompanion.catalog.Mixins;
import com.github.minecraft_ta.totalDebugCompanion.catalog.PackCatalogService;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.ui.ContextMenus;
import com.github.minecraft_ta.totalDebugCompanion.ui.Tooltip;
import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.BrowserBody;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.PageLoader;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.Tables;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;

import javax.swing.AbstractAction;
import javax.swing.JCheckBox;
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
import java.io.IOException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The mixins the mods declare, one row per member of a target class they change, with the mods that change it and how.
 * Each change's selector is resolved against the members the target declares, read from the class index, as Mixin
 * resolves it. Shared narrows the list to members several mods change, where their changes can meet; an Overwrite among
 * them stands out. A row opens its target class; its menu opens each mixin class and mod.
 */
public final class MixinsPanel extends JPanel {
    /** What a row stands for: a member the target declares, or a selector that selects none, and why. */
    enum Kind {
        CLASS(""),
        FIELD(""),
        METHOD(""),
        NOT_SELECTED("Selects nothing the class declares"),
        DYNAMIC("A dynamic selector, which only the game resolves"),
        NO_CLASS("The class is not in the game");

        private final String reason;

        Kind(String reason) {
            this.reason = reason;
        }

        boolean selected() {
            return this.reason.isEmpty();
        }
    }

    /** Where the target classes of mixins come from: a class by binary name, or empty when the game has none of it. */
    @FunctionalInterface
    interface Targets {
        Optional<MixinTarget> find(String binaryName) throws IOException;
    }

    /** A member of a target class that mixins change, or a selector that selects none, and the changes of the row. */
    record Row(String target, String member, Kind kind, List<Entry> entries) {
        /** What identifies the row across reloads. */
        String key() {
            return this.target + "#" + this.kind + ":" + this.member;
        }

        Set<String> mods() {
            Set<String> mods = new LinkedHashSet<>();
            for (Entry entry : this.entries) mods.add(entry.mixin().modId());
            return mods;
        }

        /**
         * Whether changes of different mods meet here: on a member both select, on one side at least, as a client-only
         * and a server-only never do.
         */
        boolean shared() {
            if (!this.kind.selected()) return false;
            for (Entry first : this.entries) {
                for (Entry second : this.entries) {
                    if (meet(first, second)) return true;
                }
            }
            return false;
        }

        /** One mod replaces the member whole where another mod's change meets it, which that change may not survive. */
        boolean overwritten() {
            if (!this.kind.selected()) return false;
            for (Entry overwrite : this.entries) {
                if (!overwrite.kind().equals("Overwrite")) continue;
                for (Entry other : this.entries) {
                    if (meet(overwrite, other)) return true;
                }
            }
            return false;
        }

        private static boolean meet(Entry first, Entry second) {
            Mixins.Side one = first.mixin().side();
            Mixins.Side two = second.mixin().side();
            return !first.mixin().modId().equals(second.mixin().modId())
                    && (one == Mixins.Side.BOTH || two == Mixins.Side.BOTH || one == two);
        }

        /** The row as a reference: the target, and the member with its overload, or the selector as written. */
        String reference() {
            return this.target + (this.kind == Kind.CLASS ? "" : "#" + this.member);
        }

        String kinds() {
            Set<String> kinds = new LinkedHashSet<>();
            for (Entry entry : this.entries) kinds.add(entry.kind());
            return String.join(", ", kinds);
        }
    }

    /** One mixin's change of the row. */
    record Entry(Mixins.Mixin mixin, String kind) {
    }

    /** The rows read, and why files or classes could not be read. */
    private record Loaded(CatalogIndex index, List<Row> rows, List<String> problems) {
    }

    private final PackCatalogService catalog;
    /** The current runtime's classes, or null while the class index is not ready. */
    private final Supplier<ClassBytecodeSource> classes;
    private final Consumer<NavigationTarget> navigator;
    private final RowsModel model = new RowsModel();
    private final JTable table = new JTable(this.model) {
        @Override
        public String getToolTipText(MouseEvent event) {
            int row = rowAtPoint(event.getPoint());
            return row < 0 ? null : tooltip(MixinsPanel.this.model.shown.get(row));
        }
    };
    private final BrowserBody body;
    private final JCheckBox sharedOnly = new JCheckBox("Shared");
    private final PageLoader<Loaded> loader;
    private CatalogIndex index;
    private List<Row> all = List.of();
    private String unavailable = "";

    public MixinsPanel(PackCatalogService catalog, Supplier<ClassBytecodeSource> classes, Consumer<NavigationTarget> navigator) {
        super(new BorderLayout());
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.classes = Objects.requireNonNull(classes, "classes");
        this.navigator = Objects.requireNonNull(navigator, "navigator");
        this.table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        Tables.configure(this.table);
        this.table.setDefaultRenderer(Object.class, new RowRenderer());
        ToolTipManager.sharedInstance().registerComponent(this.table);
        ContextMenus.installTable(this.table, this::menu);
        this.table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                int row = MixinsPanel.this.table.rowAtPoint(event.getPoint());
                if (row >= 0 && event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)) openTarget(MixinsPanel.this.model.shown.get(row));
            }
        });
        this.table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "openTarget");
        this.table.getActionMap().put("openTarget", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                int row = MixinsPanel.this.table.getSelectedRow();
                if (row >= 0) openTarget(MixinsPanel.this.model.shown.get(row));
            }
        });
        int[] weights = {30, 25, 25, 20};
        for (int column = 0; column < weights.length; column++) {
            this.table.getColumnModel().getColumn(column).setPreferredWidth(weights[column] * 10);
        }
        this.body = new BrowserBody("Filter by class, member or mod", BrowserBody.scroll(this.table), this.table, this::applyFilter);
        this.sharedOnly.setToolTipText(Tooltip.of("Shared").text("Only members that several mods change").html());
        this.sharedOnly.addActionListener(event -> applyFilter());
        this.body.addOption(this.sharedOnly);
        add(this.body, BorderLayout.CENTER);
        this.loader = new PageLoader<>(this::prepareLoad, this::show, failure -> {
            this.all = List.of();
            this.unavailable = "The mixins could not be read: " + failure.getMessage();
            applyFilter();
        }).whenShown(this).follow(catalog::addListener);
    }

    /** Reads the mods' mixins again, and resolves them against the current runtime's classes. */
    public void load() {
        this.loader.load();
    }

    /**
     * Reads the mixins of the mods the catalog knows and resolves them against the runtime's classes; without a catalog
     * there is nothing to read, and without the class index nothing to resolve against.
     */
    private Callable<Loaded> prepareLoad() {
        CatalogIndex captured = this.catalog.index().orElse(null);
        ClassBytecodeSource source = this.classes.get();
        if (captured == null || source == null) {
            String reason = captured == null ? CatalogMessages.unavailable(this.catalog.state()) : "";
            show(new Loaded(null, List.of(), List.of()));
            this.unavailable = captured != null ? "The class index is not ready yet."
                    : reason.isEmpty() ? "The pack catalog is not captured yet." : reason;
            applyFilter();
            return null;
        }
        if (this.all.isEmpty()) this.body.showMessage("Reading the mixins of every mod");
        return () -> {
            Mixins.Read read = Mixins.read(captured);
            List<String> problems = new ArrayList<>(read.problems());
            List<Row> rows = rows(read.mixins(), binaryName -> {
                byte[] bytes = source.findClassBytes(binaryName);
                return bytes == null ? Optional.empty() : Optional.of(MixinTarget.read(bytes));
            }, problems);
            return new Loaded(captured, rows, problems);
        };
    }

    /**
     * One row per member of each target that changes select, by target, then member: each change's selector is resolved
     * against the members the target declares, so a row holds every change that selects its member. A change that selects
     * nothing, or whose target the game does not have, is a row of the selector as written, which meets no other change.
     * A target that cannot be read is named in {@code problems}, and its changes left out.
     */
    static List<Row> rows(List<Mixins.Mixin> mixins, Targets targets, List<String> problems) {
        Map<String, Optional<MixinTarget>> read = new LinkedHashMap<>();
        Set<String> unreadable = new HashSet<>();
        Map<Row, List<Entry>> byRow = new LinkedHashMap<>();
        for (Mixins.Mixin mixin : mixins) {
            for (String target : mixin.targets()) {
                if (unreadable.contains(target)) continue;
                Optional<MixinTarget> declared = read.get(target);
                if (declared == null) {
                    try {
                        declared = targets.find(target);
                    } catch (IOException | RuntimeException failure) {
                        unreadable.add(target);
                        problems.add(target + ": " + (failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()));
                        continue;
                    }
                    read.put(target, declared);
                }
                for (Mixins.Change change : mixin.changes()) {
                    // A selector naming its owner changes only that one of the mixin's targets.
                    if (!change.appliesTo(target)) continue;
                    Entry entry = new Entry(mixin, change.kind());
                    List<MixinMember> selected = declared.map(found -> change.selector().select(found, change.staticHandler())).orElse(List.of());
                    Kind missing = declared.isEmpty() ? Kind.NO_CLASS : change.selector() instanceof MixinSelector.Dynamic ? Kind.DYNAMIC : Kind.NOT_SELECTED;
                    if (selected.isEmpty()) byRow.computeIfAbsent(new Row(target, change.selector().shown(), missing, List.of()), ignored -> new ArrayList<>()).add(entry);
                    for (MixinMember member : selected) {
                        byRow.computeIfAbsent(new Row(target, member.shown(), kind(member), List.of()), ignored -> new ArrayList<>()).add(entry);
                    }
                }
            }
        }
        List<Row> rows = new ArrayList<>();
        byRow.forEach((row, entries) -> rows.add(new Row(row.target(), row.member(), row.kind(), entries.stream().distinct().toList())));
        rows.sort(Comparator.comparing((Row row) -> simple(row.target())).thenComparing(Row::target)
                .thenComparing(Row::kind).thenComparing(Row::member));
        return rows;
    }

    private static Kind kind(MixinMember member) {
        return switch (member) {
            case MixinMember.Whole ignored -> Kind.CLASS;
            case MixinMember.Field ignored -> Kind.FIELD;
            case MixinMember.Method ignored -> Kind.METHOD;
        };
    }

    private void show(Loaded loaded) {
        this.index = loaded.index();
        this.all = loaded.rows();
        this.unavailable = "";
        // The list leaves out what could not be read, which the line under the bar names.
        List<String> problems = loaded.problems();
        // The line names the first three; its tooltip lists every one.
        this.body.showNotice(problems.isEmpty() ? "" : "Not read: " + String.join("; ", problems.subList(0, Math.min(3, problems.size())))
                + (problems.size() > 3 ? " and " + (problems.size() - 3) + " more" : ""),
                problems.size() > 3 ? Tooltip.of("Not read").text(String.join("\n", problems)).html() : null);
        applyFilter();
    }

    private void applyFilter() {
        // The selected rows stay selected where they are still shown, as a reload of the catalog keeps them.
        Set<String> selected = new HashSet<>();
        for (int row : this.table.getSelectedRows()) {
            if (row < this.model.shown.size()) selected.add(this.model.shown.get(row).key());
        }
        String query = this.body.query().toLowerCase(Locale.ROOT);
        boolean shared = this.sharedOnly.isSelected();
        List<Row> shown = new ArrayList<>();
        for (Row row : this.all) {
            if (shared && !row.shared()) continue;
            if (query.isEmpty() || row.target().toLowerCase(Locale.ROOT).contains(query) || row.member().toLowerCase(Locale.ROOT).contains(query)
                    || mods(row).toLowerCase(Locale.ROOT).contains(query) || row.mods().stream().anyMatch(mod -> mod.contains(query))
                    || row.kinds().toLowerCase(Locale.ROOT).contains(query)) {
                shown.add(row);
            }
        }
        this.model.shown = List.copyOf(shown);
        this.model.fireTableDataChanged();
        for (int row = 0; row < shown.size(); row++) {
            if (selected.contains(shown.get(row).key())) this.table.addRowSelectionInterval(row, row);
        }
        if (!shown.isEmpty()) this.body.showContent();
        else if (!this.unavailable.isEmpty()) this.body.showMessage(this.unavailable);
        else if (this.all.isEmpty()) this.body.showMessage("No mod declares mixins.");
        else this.body.showMessage(shared && query.isEmpty() ? "No member is changed by several mods." : "No mixin matches the filter.");
    }

    /** The names of the mods that change the row's member, as the catalog names them. */
    private String mods(Row row) {
        List<String> names = new ArrayList<>();
        for (String mod : row.mods()) names.add(modName(mod));
        return String.join(", ", names);
    }

    private String modName(String modId) {
        return this.index == null ? modId : this.index.mod(modId).map(PackCatalog.Mod::name).orElse(modId);
    }

    private static String simple(String binaryName) {
        return binaryName.substring(binaryName.lastIndexOf('.') + 1);
    }

    private String tooltip(Row row) {
        Tooltip tooltip = Tooltip.of(simple(row.target()) + (row.kind() == Kind.CLASS ? "" : "." + row.member()))
                .detail(row.target());
        for (Entry entry : row.entries()) {
            Mixins.Mixin mixin = entry.mixin();
            tooltip.fact(modName(mixin.modId()), entry.kind() + " in " + simple(mixin.className()) + ", " + mixin.side().label()
                    + (mixin.priority() == 1000 ? "" : ", priority " + NumberFormat.getIntegerInstance(Locale.ROOT).format(mixin.priority())));
        }
        if (row.kind() == Kind.CLASS) tooltip.text("Adds members or interfaces to the class");
        if (!row.kind().selected()) tooltip.text(row.kind().reason);
        if (row.overwritten()) tooltip.text("One mod replaces the member whole; the others' changes may not reach it");
        return tooltip.html();
    }

    /** Opens the row's target class, which a class the game does not have has no source of. */
    private void openTarget(Row row) {
        if (row.kind() != Kind.NO_CLASS) this.navigator.accept(new NavigationTarget.RuntimeClass(row.target()));
    }

    private JPopupMenu menu(int viewRow) {
        if (viewRow < 0) return null;
        List<Row> selected = new ArrayList<>();
        for (int row : this.table.getSelectedRows()) selected.add(this.model.shown.get(row));
        if (selected.isEmpty()) return null;
        JPopupMenu menu = new JPopupMenu();
        if (selected.size() > 1) {
            List<String> names = new ArrayList<>();
            for (Row row : selected) names.add(row.reference());
            menu.add(ContextMenus.defaultCopy(ContextMenus.copyAction("Copy " + names.size() + " References", String.join("\n", names))));
            return menu;
        }
        Row row = selected.getFirst();
        if (row.kind() != Kind.NO_CLASS) menu.add(ContextMenus.action("Open Source", Icons.JUMP_TO_SOURCE, "ENTER", () -> openTarget(row)));
        Set<String> opened = new LinkedHashSet<>();
        for (Entry entry : row.entries()) {
            String mixin = entry.mixin().className();
            if (opened.add(mixin)) {
                menu.add(ContextMenus.action("Open Source of " + simple(mixin), Icons.JUMP_TO_SOURCE, null,
                        () -> this.navigator.accept(new NavigationTarget.RuntimeClass(mixin))));
            }
        }
        menu.addSeparator();
        for (String mod : row.mods()) {
            menu.add(ContextMenus.action("Open " + modName(mod), null, null, () -> this.navigator.accept(new NavigationTarget.ModPage(mod))));
        }
        menu.addSeparator();
        menu.add(ContextMenus.defaultCopy(ContextMenus.copyAction("Copy Reference", row.reference())));
        return menu;
    }

    int rowCount() {
        return this.model.getRowCount();
    }

    JTextComponent filterField() {
        return this.body.filter();
    }

    public void dispose() {
        this.loader.dispose();
    }

    private final class RowsModel extends AbstractTableModel {
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
                case 0 -> "Target";
                case 1 -> "Member";
                case 2 -> "Mods";
                default -> "Changes";
            };
        }

        @Override
        public Object getValueAt(int rowIndex, int column) {
            Row row = this.shown.get(rowIndex);
            return switch (column) {
                case 0 -> simple(row.target());
                case 1 -> row.member();
                case 2 -> mods(row);
                default -> row.kinds();
            };
        }
    }

    /**
     * Targets and members in regular text, the rest, the class itself and selectors that select nothing in secondary
     * text; a member one mod replaces under others' changes as a warning.
     */
    private final class RowRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focused,
                                                       int rowIndex, int column) {
            super.getTableCellRendererComponent(table, value, selected, false, rowIndex, column);
            setBorder(UiMetrics.cellPadding());
            Row row = MixinsPanel.this.model.shown.get(rowIndex);
            if (!selected) {
                setForeground(column == 3 && row.overwritten() ? ThemeColors.warning()
                        : column == 0 || column == 1 && row.kind() != Kind.CLASS && row.kind().selected() ? ThemeColors.text() : ThemeColors.secondaryText());
            }
            return this;
        }
    }
}
