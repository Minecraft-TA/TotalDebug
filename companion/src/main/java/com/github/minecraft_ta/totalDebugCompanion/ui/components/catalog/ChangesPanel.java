package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogIndex;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigChanges;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigValues;
import com.github.minecraft_ta.totalDebugCompanion.catalog.PackCatalogService;
import com.github.minecraft_ta.totalDebugCompanion.navigation.ModTab;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totalDebugCompanion.ui.Tooltip;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconTextField;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.TypeToFilter;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import java.awt.BorderLayout;
import java.awt.CardLayout;
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
 * Every change Companion made to the pack that is still in effect, under its mod and file, with the value it replaced.
 * A change is reverted from its row or all at once, and edited further like on a mod's Configuration tab. A change the
 * file no longer holds, because its original value was written back elsewhere, leaves the record when it is read.
 */
public final class ChangesPanel extends JPanel {
    private static final String TABLE_CARD = "table";
    private static final String MESSAGE_CARD = "message";

    /** The mod and file a section row stands for; {@code file} is null for a mod row. */
    private record Section(String modId, String modName, String fileName, Path file) {
    }

    private record Loaded(List<ConfigSettingsTable.Row> rows, Map<String, ConfigWriter.Target> targets,
                          Map<String, ChangeRecord.Change> changes, Map<String, Section> sections, List<String> problems) {
    }

    private final PackCatalogService catalog;
    private final ChangeRecord record;
    private final Consumer<NavigationTarget> navigator;
    private final ConfigWriter writer;
    private final Runnable removeRecordListener;
    private final FlatIconTextField filter = new FlatIconTextField(Icons.SEARCH_ICON);
    private final JButton revertAll = new JButton("Revert all");
    private final JLabel notice = new JLabel();
    private final ConfigSettingsTable table = new ConfigSettingsTable();
    private final JLabel message = new JLabel();
    private final JPanel cards = new JPanel(new CardLayout());
    private Map<String, ConfigWriter.Target> targets = Map.of();
    private Map<String, ChangeRecord.Change> changes = Map.of();
    private Map<String, Section> sections = Map.of();
    private String problem = "";
    private String status = "";
    private long generation;

    public ChangesPanel(PackCatalogService catalog, ConfigChanges configChanges, Consumer<NavigationTarget> navigator) {
        super(new BorderLayout());
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.record = configChanges.record();
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
        bar.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        bar.add(this.filter, BorderLayout.CENTER);
        bar.add(this.revertAll, BorderLayout.EAST);
        ThemeColors.keepForeground(this.notice, ThemeColors::secondaryText);
        this.notice.setBorder(BorderFactory.createEmptyBorder(0, 10, 6, 10));
        this.notice.setVisible(false);
        JPanel top = new JPanel(new BorderLayout());
        top.add(bar, BorderLayout.NORTH);
        top.add(this.notice, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        JScrollPane scroll = new JScrollPane(this.table);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        this.cards.add(scroll, TABLE_CARD);
        this.message.setVerticalAlignment(JLabel.TOP);
        this.message.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
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
        this.removeRecordListener = this.record.addListener(() -> SwingUtilities.invokeLater(this::load));
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) load();
        });
        load();
    }

    /** Reads the changed files again. */
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

    /** The recorded changes under their mod and file, mods by name. Blocking. */
    private Loaded read(List<ChangeRecord.Change> recorded, CatalogIndex index) {
        Map<String, List<ChangeRecord.Change>> byMod = new HashMap<>();
        for (ChangeRecord.Change change : recorded) byMod.computeIfAbsent(change.target().modId(), ignored -> new ArrayList<>()).add(change);
        List<String> mods = new ArrayList<>(byMod.keySet());
        mods.sort(Comparator.comparing(modId -> modName(index, modId).toLowerCase(Locale.ROOT)));
        List<ConfigSettingsTable.Row> rows = new ArrayList<>();
        Map<String, ConfigWriter.Target> targets = new HashMap<>();
        Map<String, ChangeRecord.Change> changes = new HashMap<>();
        Map<String, Section> sections = new HashMap<>();
        List<String> problems = new ArrayList<>();
        for (String modId : mods) {
            Map<Path, List<ChangeRecord.Change>> byFile = new LinkedHashMap<>();
            for (ChangeRecord.Change change : byMod.get(modId)) byFile.computeIfAbsent(change.target().file(), ignored -> new ArrayList<>()).add(change);
            List<ConfigSettingsTable.Row> modRows = new ArrayList<>();
            int fileNumber = 0;
            for (Map.Entry<Path, List<ChangeRecord.Change>> entry : byFile.entrySet()) {
                Path file = entry.getKey();
                ChangeRecord.Change first = entry.getValue().getFirst();
                ConfigValues values;
                try {
                    values = ConfigValues.read(file);
                } catch (IOException exception) {
                    problems.add(exception.getMessage());
                    continue;
                }
                PackCatalog.ConfigFile described = configFile(index, modId, first.target().fileName());
                // Files of one name in several worlds stay apart by number.
                String filePath = modId + "." + fileNumber++;
                List<ConfigSettingsTable.Row> fileRows = new ArrayList<>();
                for (ChangeRecord.Change change : entry.getValue()) {
                    String key = change.target().setting();
                    String literal = values.literals().get(key);
                    if (literal == null) {
                        problems.add(key + " is no longer in " + file.getFileName());
                        continue;
                    }
                    this.record.observed(change.target(), literal);
                    if (literal.equals(change.original())) continue;
                    PackCatalog.ConfigSetting setting = setting(described, key);
                    ConfigSettingsTable.Row row = new ConfigSettingsTable.Row(2, filePath + "." + key, key, setting.comment(),
                            setting, values.values().getOrDefault(key, ""), literal);
                    fileRows.add(row);
                    changes.put(row.path(), change);
                    targets.put(row.path(), new ConfigWriter.Target(modId, change.target().fileName(), file,
                            described == null ? PackCatalog.ConfigType.COMMON : described.type(), setting));
                }
                if (fileRows.isEmpty()) continue;
                modRows.add(new ConfigSettingsTable.Row(1, filePath, first.target().fileName(), "", null, "", null));
                sections.put(filePath, new Section(modId, modName(index, modId), first.target().fileName(), file));
                modRows.addAll(fileRows);
            }
            if (modRows.isEmpty()) continue;
            rows.add(new ConfigSettingsTable.Row(0, modId, modName(index, modId), "", null, "", null));
            sections.put(modId, new Section(modId, modName(index, modId), null, null));
            rows.addAll(modRows);
        }
        return new Loaded(rows, targets, changes, sections, problems);
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
        this.problem = String.join("; ", loaded.problems());
        showNotice();
        this.table.show(loaded.rows(), true, true);
        this.revertAll.setEnabled(!loaded.rows().isEmpty());
        applyFilter();
    }

    private void edit(ConfigSettingsTable.Row row, String literal) {
        ConfigWriter.Target target = this.targets.get(row.path());
        if (target != null) this.writer.edit(target, row.literal(), literal);
    }

    /** Writes every original value back, after asking. */
    private void revertAll() {
        List<String> paths = new ArrayList<>(this.changes.keySet());
        if (paths.isEmpty()) return;
        int answer = JOptionPane.showConfirmDialog(this,
                "Write the original value of " + paths.size() + (paths.size() == 1 ? " setting" : " settings") + " back?",
                "Revert all", JOptionPane.OK_CANCEL_OPTION);
        if (answer != JOptionPane.OK_OPTION) return;
        for (String path : paths) {
            ConfigWriter.Target target = this.targets.get(path);
            ChangeRecord.Change change = this.changes.get(path);
            if (target != null && change != null) this.writer.edit(target, change.current(), change.original());
        }
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
            if (change.target().file().equals(section.file()) && (last == null || change.lastChanged().isAfter(last))) {
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
        this.table.filter(this.filter.getText(), false);
        boolean empty = this.table.getRowCount() == 0;
        this.message.setText(!this.filter.getText().isBlank() ? "No change matches the filter."
                : "Companion has not changed anything in this pack.");
        ((CardLayout) this.cards.getLayout()).show(this.cards, empty ? MESSAGE_CARD : TABLE_CARD);
    }

    public void dispose() {
        this.removeRecordListener.run();
    }
}
