package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.TabTitles;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.Tables;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogIndex;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigChanges;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigEdit;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigValues;
import com.github.minecraft_ta.totalDebugCompanion.catalog.KeyBindingControl;
import com.github.minecraft_ta.totalDebugCompanion.catalog.KeyBindings;
import com.github.minecraft_ta.totalDebugCompanion.catalog.PackCatalogService;
import com.github.minecraft_ta.totalDebugCompanion.navigation.ModTab;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.pack.ResourceEdits;
import com.github.minecraft_ta.totalDebugCompanion.resource.FileTypeResolver;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totalDebugCompanion.ui.ContextMenus;
import com.github.minecraft_ta.totalDebugCompanion.ui.Tooltip;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconTextField;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.TypeToFilter;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.subject.SubjectIcons;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.event.HierarchyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Every change Companion made to the pack that is still in effect, with the value it replaced: configuration settings
 * under their mod and file, key bindings, resources in the packs Companion manages, and what was tried in the running
 * game's memory. A change is reverted from its row, with the others selected, or all at once. A change the pack no longer holds, because its original value was
 * written back elsewhere, leaves the record when it is read.
 */
public final class ChangesPanel extends JPanel {
    private static final String TABS_CARD = "tabs";
    private static final String MESSAGE_CARD = "message";

    /** The mod and file a section row stands for; {@code file} is null for a mod row. */
    private record Section(String modId, String modName, String fileName, Path file) {
    }

    /** A changed key binding: the key it has now and the one it had before Companion changed it. */
    private record KeyChange(String name, String action, KeyBindings.Assignment current, KeyBindings.Assignment original,
                             String mod) {
    }

    /** A changed resource: its path inside its root, the pack that holds it, and whether that pack still has what was written. */
    private record ResourceChange(ChangeRecord.Change change, String name, String pack, boolean held) {
        ChangeRecord.Resource target() {
            return (ChangeRecord.Resource) this.change.target();
        }
    }

    /** A setting or resource tried in the game's memory: its name, what it is, and the value tried. */
    private record GameChange(ChangeRecord.Change change, String name, String kind, String value) {
    }

    private record Loaded(List<ConfigSettingsTable.Row> rows, Map<String, ConfigWriter.Target> targets,
                          Map<String, ChangeRecord.Change> changes, Map<String, Section> sections, List<KeyChange> keys,
                          KeyBindings bindings, List<ResourceChange> resources, List<GameChange> game,
                          List<String> problems) {
    }

    private final PackCatalogService catalog;
    private final ChangeRecord record;
    private final KeyBindingControl keyControl;
    private final ResourceEdits resourceEdits;
    private final ConfigChanges configChanges;
    private final Consumer<NavigationTarget> navigator;
    private final ConfigWriter writer;
    private final Runnable removeRecordListener;
    private final FlatIconTextField filter = new FlatIconTextField(Icons.SEARCH_ICON);
    private final JButton revertAll = new JButton("Revert All");
    private final JLabel notice = new JLabel();
    private final ConfigSettingsTable table = new ConfigSettingsTable();
    private final KeyChangesModel keyModel = new KeyChangesModel();
    private final JTable keyTable = new JTable(this.keyModel);
    private final JScrollPane settingsScroll = new JScrollPane(this.table);
    private final JScrollPane keysScroll = new JScrollPane(this.keyTable);
    private final ResourceChangesModel resourceModel = new ResourceChangesModel();
    private final JTable resourceTable = new JTable(this.resourceModel);
    private final JScrollPane resourcesScroll = new JScrollPane(this.resourceTable);
    private final GameChangesModel gameModel = new GameChangesModel();
    private final JTable gameTable = new JTable(this.gameModel);
    private final JScrollPane gameScroll = new JScrollPane(this.gameTable);
    private final JTabbedPane tabs = new JTabbedPane();
    private final JLabel message = new JLabel();
    private final JPanel cards = new JPanel(new CardLayout());
    private Map<String, ConfigWriter.Target> targets = Map.of();
    private Map<String, ChangeRecord.Change> changes = Map.of();
    private Map<String, Section> sections = Map.of();
    private List<KeyChange> keys = List.of();
    private List<ResourceChange> resources = List.of();
    private List<GameChange> game = List.of();
    private KeyBindings bindings;
    private String problem = "";
    private String status = "";
    private long generation;

    public ChangesPanel(PackCatalogService catalog, ConfigChanges configChanges, KeyBindingControl keyControl,
                        ResourceEdits resourceEdits, Consumer<NavigationTarget> navigator) {
        super(new BorderLayout());
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.record = configChanges.record();
        this.keyControl = Objects.requireNonNull(keyControl, "keyControl");
        this.resourceEdits = Objects.requireNonNull(resourceEdits, "resourceEdits");
        this.configChanges = configChanges;
        this.navigator = Objects.requireNonNull(navigator, "navigator");
        this.writer = new ConfigWriter(configChanges, this::setStatus, this::load);

        this.filter.putClientProperty("JTextField.placeholderText", "Filter changes");
        this.filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { applyFilter(); }
            @Override public void removeUpdate(DocumentEvent event) { applyFilter(); }
            @Override public void changedUpdate(DocumentEvent event) { applyFilter(); }
        });
        this.revertAll.setToolTipText("Write every original value back");
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

        this.settingsScroll.setBorder(BorderFactory.createEmptyBorder());
        this.keysScroll.setBorder(BorderFactory.createEmptyBorder());
        this.resourcesScroll.setBorder(BorderFactory.createEmptyBorder());
        this.gameScroll.setBorder(BorderFactory.createEmptyBorder());
        configureKeyTable();
        configureResourceTable();
        configureGameTable();
        this.cards.add(this.tabs, TABS_CARD);
        this.message.setVerticalAlignment(JLabel.TOP);
        this.message.setBorder(UiMetrics.messagePadding());
        this.cards.add(this.message, MESSAGE_CARD);
        add(this.cards, BorderLayout.CENTER);

        this.table.setEditing(this::edit, this::setStatus, row -> {
            ConfigWriter.Target target = this.targets.get(row.path());
            return target == null ? null : this.writer.pending(target.file(), target.setting().path());
        }, row -> {
            ChangeRecord.Change change = this.changes.get(row.path());
            return change == null ? null : change.original();
        });
        this.table.setSectionTooltip(this::sectionTooltip);
        this.table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                int row = ChangesPanel.this.table.rowAtPoint(event.getPoint());
                if (row >= 0 && event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)) {
                    open(ChangesPanel.this.table.row(row));
                }
            }
        });
        this.writer.bindUndo(this.table);
        TypeToFilter.install(this.table, this.filter);
        TypeToFilter.install(this.keyTable, this.filter);
        TypeToFilter.install(this.resourceTable, this.filter);
        TypeToFilter.install(this.gameTable, this.filter);
        this.removeRecordListener = this.record.addListener(() -> SwingUtilities.invokeLater(this::load));
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) load();
        });
        load();
    }

    private void configureKeyTable() {
        this.keyTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        Tables.configure(this.keyTable);
        KeyCaps caps = new KeyCaps();
        this.keyTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focused,
                                                           int row, int column) {
                KeyChange change = ChangesPanel.this.keyModel.shown.get(row);
                Color background = selected ? table.getSelectionBackground() : table.getBackground();
                Color foreground = selected ? table.getSelectionForeground() : ThemeColors.text();
                if (column == 1) {
                    caps.configure(ChangesPanel.this.bindings.caps(change.current()),
                            null, change.current().unbound() ? "Not bound" : "was " + ChangesPanel.this.bindings.display(change.original()),
                            table.getFont(), foreground, background);
                    return caps;
                }
                super.getTableCellRendererComponent(table, value, selected, false, row, column);
                setBorder(UiMetrics.cellPadding());
                setForeground(column == 0 || selected ? foreground : ThemeColors.secondaryText());
                return this;
            }
        });
        ContextMenus.installTable(this.keyTable, this::keyMenu);
        this.keyTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                int row = ChangesPanel.this.keyTable.rowAtPoint(event.getPoint());
                if (row >= 0 && event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)) {
                    ChangesPanel.this.navigator.accept(new NavigationTarget.KeyBindings(ChangesPanel.this.keyModel.shown.get(row).name()));
                }
            }
        });
    }

    private void configureResourceTable() {
        this.resourceTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        Tables.configure(this.resourceTable);
        this.resourceTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focused,
                                                           int row, int column) {
                super.getTableCellRendererComponent(table, value, selected, false, row, column);
                ResourceChange change = ChangesPanel.this.resourceModel.shown.get(row);
                setBorder(UiMetrics.cellPadding());
                setIcon(column == 0 ? FileTypeResolver.resolve(change.name()).icon() : null);
                setForeground(selected ? table.getSelectionForeground()
                        : column == 0 ? ThemeColors.text() : ThemeColors.secondaryText());
                Path file = change.target().location().resolve(change.target().path());
                Tooltip tooltip = Tooltip.of(change.target().path()).detail(Tooltip.shortPath(file))
                        .fact("Changed", ago(change.change().lastChanged()));
                if (change.change().original().isEmpty()) tooltip.text("Added by Companion; Revert deletes it");
                if (!change.held()) tooltip.text("The file was changed outside Companion since");
                setToolTipText(tooltip.html());
                return this;
            }
        });
        ContextMenus.installTable(this.resourceTable, this::resourceMenu);
        this.resourceTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                int row = ChangesPanel.this.resourceTable.rowAtPoint(event.getPoint());
                if (row >= 0 && event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)) {
                    openResource(ChangesPanel.this.resourceModel.shown.get(row));
                }
            }
        });
    }

    private void configureGameTable() {
        this.gameTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        Tables.configure(this.gameTable);
        this.gameTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focused,
                                                           int row, int column) {
                super.getTableCellRendererComponent(table, value, selected, false, row, column);
                setBorder(UiMetrics.cellPadding());
                setForeground(selected ? table.getSelectionForeground()
                        : column == 0 ? ThemeColors.text() : ThemeColors.secondaryText());
                return this;
            }
        });
        ContextMenus.installTable(this.gameTable, this::gameMenu);
    }

    private JPopupMenu gameMenu(int row) {
        if (row < 0) return null;
        List<GameChange> selected = new ArrayList<>();
        for (int viewRow : this.gameTable.getSelectedRows()) selected.add(this.gameModel.shown.get(viewRow));
        JPopupMenu menu = new JPopupMenu();
        if (selected.size() > 1) {
            menu.add(ContextMenus.action("Revert " + selected.size(), null, null, () -> report(revertInGame(selected))));
            return menu;
        }
        GameChange change = this.gameModel.shown.get(row);
        menu.add(ContextMenus.action("Revert", null, null, () -> report(revertInGame(List.of(change)))));
        return menu;
    }

    /** Shows what {@code reverted} completes with, which is empty unless something failed. */
    private void report(CompletableFuture<String> reverted) {
        reverted.thenAccept(failure -> SwingUtilities.invokeLater(() -> setStatus(failure)));
    }

    /** Puts back what the game used before the tries; completes with what failed, or empty. */
    private CompletableFuture<String> revertInGame(List<GameChange> reverted) {
        List<CompletableFuture<?>> requests = new ArrayList<>();
        for (GameChange change : reverted) {
            requests.add(change.change().target() instanceof ChangeRecord.Setting
                    ? this.configChanges.gameValues().revert(change.change())
                    : this.resourceEdits.revert(change.change()));
        }
        return CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).handle((ignored, failure) -> {
            List<String> failed = new ArrayList<>();
            for (int index = 0; index < requests.size(); index++) {
                CompletableFuture<?> request = requests.get(index);
                String name = reverted.get(index).name();
                if (request.isCompletedExceptionally()) {
                    Throwable cause = request.exceptionNow();
                    failed.add(name + ": " + (cause.getCause() == null ? cause.getMessage() : cause.getCause().getMessage()));
                } else if (request.join() instanceof ResourceEdits.Saved saved && !saved.reloadFailure().isEmpty()) {
                    failed.add(name + ": " + saved.reloadFailure());
                }
            }
            return failed.isEmpty() ? "" : "Not reverted: " + String.join("; ", failed);
        });
    }

    private JPopupMenu resourceMenu(int row) {
        if (row < 0) return null;
        List<ResourceChange> selected = new ArrayList<>();
        for (int viewRow : this.resourceTable.getSelectedRows()) selected.add(this.resourceModel.shown.get(viewRow));
        JPopupMenu menu = new JPopupMenu();
        if (selected.size() > 1) {
            menu.add(ContextMenus.action("Revert " + selected.size(), null, null, () -> report(revertResources(selected))));
            return menu;
        }
        ResourceChange change = this.resourceModel.shown.get(row);
        menu.add(ContextMenus.action("Open", null, null, () -> openResource(change)));
        menu.add(ContextMenus.action(change.change().original().isEmpty() ? "Revert and Delete" : "Revert", null, null,
                () -> report(revertResources(List.of(change)))));
        return menu;
    }

    private void openResource(ResourceChange change) {
        Path file = change.target().location().resolve(change.target().path());
        this.navigator.accept(new NavigationTarget.LocalFile(file));
    }

    /** Puts back what the managed packs held before Companion changed them; completes with what failed, or empty. */
    private CompletableFuture<String> revertResources(List<ResourceChange> reverted) {
        List<CompletableFuture<ResourceEdits.Saved>> requests = new ArrayList<>();
        for (ResourceChange change : reverted) requests.add(this.resourceEdits.revert(change.change()));
        return CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).handle((ignored, failure) -> {
            List<String> failed = new ArrayList<>();
            for (int index = 0; index < requests.size(); index++) {
                CompletableFuture<ResourceEdits.Saved> request = requests.get(index);
                String name = reverted.get(index).name();
                if (request.isCompletedExceptionally()) {
                    Throwable cause = request.exceptionNow();
                    failed.add(name + ": " + (cause.getCause() == null ? cause.getMessage() : cause.getCause().getMessage()));
                } else if (!request.join().reloadFailure().isEmpty()) {
                    failed.add(name + ": " + request.join().reloadFailure());
                }
            }
            return failed.isEmpty() ? "" : "Not reverted or not reloaded: " + String.join("; ", failed);
        });
    }

    private JPopupMenu keyMenu(int row) {
        if (row < 0) return null;
        List<KeyChange> selected = new ArrayList<>();
        for (int viewRow : this.keyTable.getSelectedRows()) selected.add(this.keyModel.shown.get(viewRow));
        JPopupMenu menu = new JPopupMenu();
        if (selected.size() > 1) {
            menu.add(ContextMenus.action("Revert " + selected.size(), null, null, () -> report(revert(selected))));
            return menu;
        }
        KeyChange change = this.keyModel.shown.get(row);
        menu.add(ContextMenus.action("Revert to " + this.bindings.display(change.original()), null, null,
                () -> report(revert(List.of(change)))));
        menu.add(ContextMenus.action("Show in Key Bindings", null, null,
                () -> this.navigator.accept(new NavigationTarget.KeyBindings(change.name()))));
        return menu;
    }

    /** Reads the changed files and keys again. */
    public void load() {
        CatalogIndex index = this.catalog.index().orElse(null);
        List<ChangeRecord.Change> recorded = this.record.changes();
        long current = ++this.generation;
        CompletableFuture.supplyAsync(() -> {
            this.writer.refreshPending();
            return read(recorded, index);
        }).whenComplete((loaded, failure) -> SwingUtilities.invokeLater(() -> {
            if (current != this.generation) return;
            if (failure != null) {
                setStatus("Could not read the changed files: " + failure.getMessage());
            } else {
                show(loaded);
            }
        }));
    }

    /** The recorded changes: settings under their mod and file, mods by name, then key bindings. Blocking. */
    private Loaded read(List<ChangeRecord.Change> recorded, CatalogIndex index) {
        Map<String, List<ChangeRecord.Change>> byMod = new HashMap<>();
        List<ChangeRecord.Change> keyChanges = new ArrayList<>();
        List<ChangeRecord.Change> resourceChanges = new ArrayList<>();
        List<GameChange> game = new ArrayList<>();
        for (ChangeRecord.Change change : recorded) {
            if (change.level() == ChangeRecord.Level.GAME) {
                game.add(gameChange(change, index));
                continue;
            }
            switch (change.target()) {
                case ChangeRecord.Setting setting -> byMod.computeIfAbsent(setting.modId(), ignored -> new ArrayList<>()).add(change);
                case ChangeRecord.KeyBinding ignored -> keyChanges.add(change);
                case ChangeRecord.Resource ignored -> resourceChanges.add(change);
            }
        }
        List<String> mods = new ArrayList<>(byMod.keySet());
        mods.sort(Comparator.comparing(modId -> modName(index, modId).toLowerCase(Locale.ROOT)));
        List<ConfigSettingsTable.Row> rows = new ArrayList<>();
        Map<String, ConfigWriter.Target> targets = new HashMap<>();
        Map<String, ChangeRecord.Change> changes = new HashMap<>();
        Map<String, Section> sections = new HashMap<>();
        List<String> problems = new ArrayList<>();
        for (String modId : mods) {
            Map<Path, List<ChangeRecord.Change>> byFile = new LinkedHashMap<>();
            for (ChangeRecord.Change change : byMod.get(modId)) byFile.computeIfAbsent(setting(change).file(), ignored -> new ArrayList<>()).add(change);
            List<ConfigSettingsTable.Row> modRows = new ArrayList<>();
            int fileNumber = 0;
            for (Map.Entry<Path, List<ChangeRecord.Change>> entry : byFile.entrySet()) {
                Path file = entry.getKey();
                String fileName = setting(entry.getValue().getFirst()).fileName();
                ConfigValues values;
                try {
                    values = ConfigValues.read(file);
                } catch (IOException exception) {
                    problems.add(exception.getMessage());
                    continue;
                }
                PackCatalog.ConfigFile described = configFile(index, modId, fileName);
                // Files of one name in several worlds stay apart by number.
                String filePath = modId + "." + fileNumber++;
                List<ConfigSettingsTable.Row> fileRows = new ArrayList<>();
                for (ChangeRecord.Change change : entry.getValue()) {
                    String key = setting(change).setting();
                    String literal = values.literals().get(key);
                    if (literal == null) {
                        problems.add(key + " is no longer in " + file.getFileName());
                        continue;
                    }
                    this.record.observed(change.target(), ChangeRecord.Level.PACK, literal, ConfigEdit::sameValue);
                    if (ConfigEdit.sameValue(literal, change.original())) continue;
                    PackCatalog.ConfigSetting setting = setting(described, key);
                    ConfigSettingsTable.Row row = new ConfigSettingsTable.Row(2, filePath + "." + key, key, setting.comment(),
                            setting, values.values().getOrDefault(key, ""), literal);
                    fileRows.add(row);
                    changes.put(row.path(), change);
                    targets.put(row.path(), new ConfigWriter.Target(modId, fileName, file,
                            described == null ? PackCatalog.ConfigType.COMMON : described.type(), setting));
                }
                if (fileRows.isEmpty()) continue;
                modRows.add(new ConfigSettingsTable.Row(1, filePath, fileName, "", null, "", null));
                sections.put(filePath, new Section(modId, modName(index, modId), fileName, file));
                modRows.addAll(fileRows);
            }
            if (modRows.isEmpty()) continue;
            rows.add(new ConfigSettingsTable.Row(0, modId, modName(index, modId), "", null, "", null));
            sections.put(modId, new Section(modId, modName(index, modId), null, null));
            rows.addAll(modRows);
        }
        Map<String, KeyBindings.Assignment> options = options(problems);
        KeyBindings bindings = keyBindings(index, options);
        List<KeyChange> keys = new ArrayList<>();
        for (ChangeRecord.Change change : keyChanges) {
            String name = ((ChangeRecord.KeyBinding) change.target()).name();
            KeyBindings.Binding binding = bindings.bindings().stream()
                    .filter(candidate -> candidate.spec().name().equals(name)).findFirst().orElse(null);
            // A binding the catalog does not describe still has its line in options.txt.
            KeyBindings.Assignment current = binding != null ? binding.current()
                    : options.getOrDefault(name, KeyBindings.Assignment.decode(change.current()));
            this.record.observed(change.target(), ChangeRecord.Level.PACK, current.encode(), String::equals);
            if (current.encode().equals(change.original())) continue;
            String mod = binding == null || index == null ? "" : modName(index, index.keyBindingOwner(binding.spec()));
            keys.add(new KeyChange(name, binding == null ? name : binding.name(), current,
                    KeyBindings.Assignment.decode(change.original()), mod));
        }
        List<ResourceChange> resources = new ArrayList<>();
        for (ChangeRecord.Change change : resourceChanges) {
            ChangeRecord.Resource target = (ChangeRecord.Resource) change.target();
            if (target.location() == null) continue;
            boolean held = this.resourceEdits.holds(change);
            if (this.record.change(target, change.level()) == null) continue;
            String name = target.path().substring(target.path().indexOf('/') + 1);
            resources.add(new ResourceChange(change, name, packName(target.location()), held));
        }
        resources.sort(Comparator.comparing(ResourceChange::name));
        game.sort(Comparator.comparing(GameChange::name));
        return new Loaded(rows, targets, changes, sections, keys, bindings, resources, game, problems);
    }

    /** A change in the game's memory as a row: a setting by its mod and key, a resource by its path. */
    private static GameChange gameChange(ChangeRecord.Change change, CatalogIndex index) {
        return switch (change.target()) {
            case ChangeRecord.Setting setting -> new GameChange(change, setting.setting(),
                    "Setting of " + modName(index, setting.modId()), change.current());
            case ChangeRecord.Resource resource -> new GameChange(change, resource.path().substring(resource.path().indexOf('/') + 1),
                    resource.assets() ? "Resource" : "Data", "");
            case ChangeRecord.KeyBinding binding -> new GameChange(change, binding.name(), "Key binding", change.current());
        };
    }

    /** The managed pack a folder is: the resource pack, or the datapack of a world. */
    private static String packName(Path pack) {
        Path parent = pack.getParent();
        if (parent != null && parent.getFileName() != null && parent.getFileName().toString().equals("datapacks")
                && parent.getParent() != null) {
            return "Datapack of " + parent.getParent().getFileName();
        }
        return "Resource pack";
    }

    private static ChangeRecord.Setting setting(ChangeRecord.Change change) {
        return (ChangeRecord.Setting) change.target();
    }

    /** The pack's bindings with the keys options.txt gives them now. */
    private Map<String, KeyBindings.Assignment> options(List<String> problems) {
        try {
            return KeyBindings.readOptions(this.keyControl.options());
        } catch (IOException exception) {
            problems.add("Could not read options.txt: " + exception.getMessage());
            return Map.of();
        }
    }

    private static KeyBindings keyBindings(CatalogIndex index, Map<String, KeyBindings.Assignment> current) {
        PackCatalog captured = index == null ? null : index.catalog();
        return captured == null ? new KeyBindings(List.of(), List.of(), current, Map.of())
                : new KeyBindings(captured.keyBindings(), captured.keyContexts(), current, captured.keyNames());
    }

    private static String modName(CatalogIndex index, String modId) {
        return index == null ? modId : index.mod(modId).map(PackCatalog.Mod::name).orElse(modId);
    }

    private static PackCatalog.ConfigFile configFile(CatalogIndex index, String modId, String fileName) {
        if (index == null) return null;
        return index.mod(modId).flatMap(mod -> mod.configs().stream()
                .filter(file -> file.fileName().equals(fileName)).findFirst()).orElse(null);
    }

    /** The captured setting, or one without a description when the catalog no longer has it. */
    private static PackCatalog.ConfigSetting setting(PackCatalog.ConfigFile file, String key) {
        if (file != null) {
            for (PackCatalog.ConfigSetting setting : file.settings()) {
                if (setting.path().equals(key)) return setting;
            }
        }
        return new PackCatalog.ConfigSetting(key, "", "", "", List.of(), PackCatalog.Restart.NONE);
    }

    private void show(Loaded loaded) {
        this.targets = loaded.targets();
        this.changes = loaded.changes();
        this.sections = loaded.sections();
        this.keys = loaded.keys();
        this.resources = loaded.resources();
        this.game = loaded.game();
        this.bindings = loaded.bindings();
        this.problem = String.join("; ", loaded.problems());
        showNotice();
        this.table.show(loaded.rows(), true, true);
        this.keyModel.setChanges(loaded.keys());
        this.resourceModel.setChanges(loaded.resources());
        this.gameModel.setChanges(loaded.game());
        this.revertAll.setEnabled(!loaded.changes().isEmpty() || !loaded.keys().isEmpty() || !loaded.resources().isEmpty()
                || !loaded.game().isEmpty());
        this.tabs.removeAll();
        if (!loaded.changes().isEmpty()) {
            this.tabs.addTab("Configuration", SubjectIcons.tab(ModTab.CONFIGURATION), this.settingsScroll);
            TabTitles.setCounted(this.tabs, this.tabs.getTabCount() - 1, "Configuration", loaded.changes().size());
        }
        if (!loaded.keys().isEmpty()) {
            this.tabs.addTab("Key bindings", SubjectIcons.tab(ModTab.KEY_BINDINGS), this.keysScroll);
            TabTitles.setCounted(this.tabs, this.tabs.getTabCount() - 1, "Key bindings", loaded.keys().size());
        }
        if (!loaded.resources().isEmpty()) {
            this.tabs.addTab("Resources", SubjectIcons.tab(ModTab.RESOURCES), this.resourcesScroll);
            TabTitles.setCounted(this.tabs, this.tabs.getTabCount() - 1, "Resources", loaded.resources().size());
        }
        if (!loaded.game().isEmpty()) {
            this.tabs.addTab("In the game", Icons.RUN, this.gameScroll);
            TabTitles.setCounted(this.tabs, this.tabs.getTabCount() - 1, "In the game", loaded.game().size());
        }
        applyFilter();
    }

    private void edit(ConfigSettingsTable.Row row, String literal) {
        ConfigWriter.Target target = this.targets.get(row.path());
        if (target != null) this.writer.edit(target, row.literal(), literal);
    }

    /** Puts bindings back on the keys they had before Companion changed them; completes with what failed, or empty. */
    private CompletableFuture<String> revert(List<KeyChange> reverted) {
        List<KeyBindingControl.Change> requests = new ArrayList<>();
        Map<String, String> names = new HashMap<>();
        for (KeyChange change : reverted) {
            requests.add(new KeyBindingControl.Change(change.name(), change.current(), change.original()));
            names.put(change.name(), change.action());
        }
        return this.keyControl.setAll(requests).thenApply(failed -> KeyBindingsPanel.notChanged(failed, names));
    }

    /** Writes every original value back, after asking. */
    private void revertAll() {
        int total = this.changes.size() + this.keys.size() + this.resources.size() + this.game.size();
        if (total == 0) return;
        int answer = JOptionPane.showConfirmDialog(this,
                "Put back the original value of " + total + (total == 1 ? " change?" : " changes?"),
                "Revert All", JOptionPane.OK_CANCEL_OPTION);
        if (answer != JOptionPane.OK_OPTION) return;
        Map<ConfigWriter.Target, ChangeRecord.Change> files = new LinkedHashMap<>();
        this.changes.forEach((path, change) -> {
            ConfigWriter.Target target = this.targets.get(path);
            if (target != null) files.put(target, change);
        });
        CompletableFuture<String> inGame = revertInGame(this.game);
        // Memory first: a file written back replaces the game's memory, while a late in-memory revert would outlast it.
        inGame.whenComplete((ignored, failure) -> SwingUtilities.invokeLater(() ->
                files.forEach((target, change) -> this.writer.edit(target, change.current(), change.original()))));
        List<CompletableFuture<String>> reverts = List.of(revert(this.keys), revertResources(this.resources), inGame);
        // One status once every kind is done, so a later success never hides an earlier failure.
        report(CompletableFuture.allOf(reverts.toArray(CompletableFuture[]::new)).thenApply(ignored -> String.join("; ",
                reverts.stream().map(CompletableFuture::join).filter(failure -> !failure.isEmpty()).toList())));
    }

    /** A mod row opens the mod's page, a file row its Configuration tab. */
    private void open(ConfigSettingsTable.Row row) {
        Section section = row.setting() == null ? this.sections.get(row.path()) : null;
        if (section == null) return;
        this.navigator.accept(new NavigationTarget.ModPage(section.modId(),
                section.file() == null ? ModTab.OVERVIEW : ModTab.CONFIGURATION, ""));
    }

    private String sectionTooltip(ConfigSettingsTable.Row row) {
        Section section = this.sections.get(row.path());
        if (section == null) return null;
        if (section.file() == null) return Tooltip.of(section.modName()).detail(section.modId()).html();
        Tooltip tooltip = Tooltip.of(section.fileName()).detail(Tooltip.shortPath(section.file()));
        Instant last = null;
        for (ChangeRecord.Change change : this.changes.values()) {
            if (setting(change).file().equals(section.file()) && (last == null || change.lastChanged().isAfter(last))) {
                last = change.lastChanged();
            }
        }
        if (last != null) tooltip.fact("Changed", ago(last));
        return tooltip.html();
    }

    /** How long ago {@code time} was, in the largest whole unit. */
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
        String query = this.filter.getText();
        this.table.filter(query, false);
        this.keyModel.filter(query.strip().toLowerCase(Locale.ROOT));
        this.resourceModel.filter(query.strip().toLowerCase(Locale.ROOT));
        this.gameModel.filter(query.strip().toLowerCase(Locale.ROOT));
        boolean empty = this.tabs.getTabCount() == 0;
        this.message.setText(!query.isBlank() ? "No change matches the filter." : "Companion has not changed anything in this pack.");
        ((CardLayout) this.cards.getLayout()).show(this.cards, empty ? MESSAGE_CARD : TABS_CARD);
    }

    /** The table of changed settings, which sits in a tab only while there are some. */
    ConfigSettingsTable settingsTable() {
        return this.table;
    }

    public void dispose() {
        this.removeRecordListener.run();
    }

    private final class GameChangesModel extends AbstractTableModel {
        private List<GameChange> all = List.of();
        private List<GameChange> shown = List.of();
        private String query = "";

        void setChanges(List<GameChange> changes) {
            this.all = List.copyOf(changes);
            filter(this.query);
        }

        void filter(String query) {
            this.query = query;
            this.shown = this.all.stream().filter(change -> query.isEmpty()
                    || change.name().toLowerCase(Locale.ROOT).contains(query)
                    || change.kind().toLowerCase(Locale.ROOT).contains(query)).toList();
            fireTableDataChanged();
        }

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
                case 0 -> "Name";
                case 1 -> "Kind";
                default -> "Value";
            };
        }

        @Override
        public Object getValueAt(int row, int column) {
            GameChange change = this.shown.get(row);
            return switch (column) {
                case 0 -> change.name();
                case 1 -> change.kind();
                default -> change.value();
            };
        }
    }

    private final class ResourceChangesModel extends AbstractTableModel {
        private List<ResourceChange> all = List.of();
        private List<ResourceChange> shown = List.of();
        private String query = "";

        void setChanges(List<ResourceChange> changes) {
            this.all = List.copyOf(changes);
            filter(this.query);
        }

        void filter(String query) {
            this.query = query;
            this.shown = this.all.stream().filter(change -> query.isEmpty()
                    || change.name().toLowerCase(Locale.ROOT).contains(query)
                    || change.pack().toLowerCase(Locale.ROOT).contains(query)).toList();
            fireTableDataChanged();
        }

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
                case 0 -> "Resource";
                case 1 -> "Pack";
                default -> "Changed";
            };
        }

        @Override
        public Object getValueAt(int row, int column) {
            ResourceChange change = this.shown.get(row);
            return switch (column) {
                case 0 -> change.name();
                case 1 -> change.pack();
                default -> ago(change.change().lastChanged());
            };
        }
    }

    private final class KeyChangesModel extends AbstractTableModel {
        private List<KeyChange> all = List.of();
        private List<KeyChange> shown = List.of();
        private String query = "";

        void setChanges(List<KeyChange> changes) {
            this.all = List.copyOf(changes);
            filter(this.query);
        }

        void filter(String query) {
            this.query = query;
            this.shown = this.all.stream().filter(change -> query.isEmpty()
                    || change.action().toLowerCase(Locale.ROOT).contains(query)
                    || change.mod().toLowerCase(Locale.ROOT).contains(query)
                    || ChangesPanel.this.bindings.namesKey(change.current(), query)).toList();
            fireTableDataChanged();
        }

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
                case 0 -> "Action";
                case 1 -> "Key";
                default -> "Mod";
            };
        }

        @Override
        public Object getValueAt(int row, int column) {
            KeyChange change = this.shown.get(row);
            return switch (column) {
                case 0 -> change.action();
                case 1 -> ChangesPanel.this.bindings.display(change.current());
                default -> change.mod();
            };
        }
    }
}
