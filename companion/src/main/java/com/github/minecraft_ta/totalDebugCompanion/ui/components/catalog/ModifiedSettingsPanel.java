package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogIndex;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigChanges;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigSources;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigValues;
import com.github.minecraft_ta.totalDebugCompanion.catalog.PackCatalogService;
import com.github.minecraft_ta.totalDebugCompanion.navigation.ModTab;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.ui.Tooltip;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconTextField;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.TypeToFilter;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.event.HierarchyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Every setting of the pack that differs from its default, under its mod and file, edited like on a mod's
 * Configuration tab. A server configuration is read from the world that changed it last. Files are read again whenever
 * the page is shown, the catalog changes or an edit is written.
 */
public final class ModifiedSettingsPanel extends JPanel {
    private static final String TABLE_CARD = "table";
    private static final String MESSAGE_CARD = "message";

    /** A file whose modified settings are listed; {@code source} names the world of a server configuration. */
    private record Listed(PackCatalog.Mod mod, PackCatalog.ConfigFile file, ConfigSources.Source source) {
    }

    private record Loaded(List<ConfigSettingsTable.Row> rows, Map<String, ConfigWriter.Target> targets,
                          Map<String, Listed> sections, List<String> problems) {
    }

    private final PackCatalogService catalog;
    private final Path workspace;
    private final Consumer<NavigationTarget> navigator;
    private final ConfigWriter writer;
    private final Runnable removeCatalogListener;
    private final FlatIconTextField filter = new FlatIconTextField(Icons.SEARCH_ICON);
    private final JLabel notice = new JLabel();
    private final ConfigSettingsTable table = new ConfigSettingsTable();
    private final JLabel message = new JLabel();
    private final JPanel cards = new JPanel(new CardLayout());
    /** Where each listed setting is written, by row path. */
    private Map<String, ConfigWriter.Target> targets = Map.of();
    /** The mod or file of each section row, by row path. */
    private Map<String, Listed> sections = Map.of();
    private String problem = "";
    private String status = "";
    private long generation;

    public ModifiedSettingsPanel(PackCatalogService catalog, Path workspace, ConfigChanges changes,
                                 Consumer<NavigationTarget> navigator) {
        super(new BorderLayout());
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.workspace = workspace;
        this.navigator = Objects.requireNonNull(navigator, "navigator");
        this.writer = new ConfigWriter(changes, this::setStatus, this::load);

        this.filter.putClientProperty("JTextField.placeholderText", "Filter settings");
        this.filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { applyFilter(); }
            @Override public void removeUpdate(DocumentEvent event) { applyFilter(); }
            @Override public void changedUpdate(DocumentEvent event) { applyFilter(); }
        });
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        bar.add(this.filter, BorderLayout.CENTER);
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
            ConfigWriter.Target target = this.targets.get(row.path());
            return target == null ? null : this.writer.original(target.file(), target.setting().path());
        });
        this.table.setSectionTooltip(this::sectionTooltip);
        this.table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                int row = ModifiedSettingsPanel.this.table.rowAtPoint(event.getPoint());
                if (row >= 0 && event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)) {
                    open(ModifiedSettingsPanel.this.table.row(row));
                }
            }
        });
        this.writer.bindUndo(this.table);
        TypeToFilter.install(this.table, this.filter);
        this.removeCatalogListener = catalog.addListener(() -> SwingUtilities.invokeLater(this::load));
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) load();
        });
        load();
    }

    /** Reads every file again. */
    public void load() {
        CatalogIndex index = this.catalog.index().orElse(null);
        long current = ++this.generation;
        if (index == null) {
            show(new Loaded(List.of(), Map.of(), Map.of(), List.of()), "The pack catalog is not captured yet.");
            return;
        }
        CompletableFuture.supplyAsync(() -> {
            this.writer.refreshPending();
            return read(index);
        }).whenComplete((loaded, failure) -> SwingUtilities.invokeLater(() -> {
            if (current != this.generation) return;
            if (failure != null) {
                show(new Loaded(List.of(), Map.of(), Map.of(), List.of()), "Could not read the configuration files: " + failure.getMessage());
            } else {
                show(loaded, "");
            }
        }));
    }

    /** The modified settings of every mod, mods by name and files by name. Blocking. */
    private Loaded read(CatalogIndex index) {
        List<ConfigSettingsTable.Row> rows = new ArrayList<>();
        Map<String, ConfigWriter.Target> targets = new HashMap<>();
        Map<String, Listed> sections = new HashMap<>();
        List<String> problems = new ArrayList<>();
        List<PackCatalog.Mod> mods = new ArrayList<>(index.mods());
        mods.sort(Comparator.comparing(mod -> mod.name().toLowerCase(Locale.ROOT)));
        for (PackCatalog.Mod mod : mods) {
            boolean modShown = false;
            for (PackCatalog.ConfigFile file : mod.configs()) {
                if (file.settings().isEmpty()) continue;
                List<ConfigSources.Source> sources = ConfigSources.of(this.workspace, file);
                if (sources.isEmpty()) continue;
                ConfigSources.Source source = sources.getFirst();
                ConfigValues values;
                try {
                    values = ConfigValues.read(source.path());
                } catch (IOException exception) {
                    problems.add(exception.getMessage());
                    continue;
                }
                String filePath = mod.id() + "." + file.fileName();
                List<ConfigSettingsTable.Row> modified = new ArrayList<>();
                for (ConfigSettingsTable.Row row : ConfigSettingsTable.rows(file, values)) {
                    if (!row.modified()) continue;
                    ConfigSettingsTable.Row listed = new ConfigSettingsTable.Row(2, filePath + "." + row.path(), row.path(),
                            row.comment(), row.setting(), row.value(), row.literal());
                    modified.add(listed);
                    targets.put(listed.path(), new ConfigWriter.Target(source.path(), file.type(), row.setting()));
                }
                if (modified.isEmpty()) continue;
                if (!modShown) {
                    rows.add(new ConfigSettingsTable.Row(0, mod.id(), mod.name(), "", null, "", null));
                    sections.put(mod.id(), new Listed(mod, null, null));
                    modShown = true;
                }
                rows.add(new ConfigSettingsTable.Row(1, filePath, file.fileName(), "", null, "", null));
                sections.put(filePath, new Listed(mod, file, source));
                rows.addAll(modified);
            }
        }
        return new Loaded(rows, targets, sections, problems);
    }

    private void show(Loaded loaded, String problem) {
        this.targets = loaded.targets();
        this.sections = loaded.sections();
        this.problem = !problem.isEmpty() ? problem
                : loaded.problems().isEmpty() ? "" : String.join("; ", loaded.problems());
        showNotice();
        this.table.show(loaded.rows(), true, true);
        applyFilter();
        this.message.setText(loaded.rows().isEmpty() && problem.isEmpty() ? "No setting differs from its default." : problem);
        ((CardLayout) this.cards.getLayout()).show(this.cards, loaded.rows().isEmpty() ? MESSAGE_CARD : TABLE_CARD);
    }

    private void edit(ConfigSettingsTable.Row row, String literal) {
        ConfigWriter.Target target = this.targets.get(row.path());
        if (target != null) this.writer.edit(target, row.literal(), literal);
    }

    /** A mod row opens the mod's page, a file row its Configuration tab. */
    private void open(ConfigSettingsTable.Row row) {
        Listed listed = row.setting() == null ? this.sections.get(row.path()) : null;
        if (listed == null) return;
        this.navigator.accept(new NavigationTarget.ModPage(listed.mod().id(),
                listed.file() == null ? ModTab.OVERVIEW : ModTab.CONFIGURATION, ""));
    }

    private String sectionTooltip(ConfigSettingsTable.Row row) {
        Listed listed = this.sections.get(row.path());
        if (listed == null) return null;
        if (listed.file() == null) return Tooltip.of(listed.mod().name()).detail(listed.mod().id()).html();
        Tooltip tooltip = Tooltip.of(listed.file().fileName()).detail(Tooltip.shortPath(listed.source().path()));
        if (listed.file().path() == null) tooltip.fact("World", listed.source().label());
        return tooltip.html();
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
    }

    /** The field that filters the settings. */
    public JTextComponent filterField() {
        return this.filter;
    }

    public void dispose() {
        this.removeCatalogListener.run();
    }
}
