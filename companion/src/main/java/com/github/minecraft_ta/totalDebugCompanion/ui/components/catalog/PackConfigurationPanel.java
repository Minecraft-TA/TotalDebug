package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogIndex;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigSettings;
import com.github.minecraft_ta.totalDebugCompanion.game.GameLocation;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigSources;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigValues;
import com.github.minecraft_ta.totalDebugCompanion.catalog.PackCatalogService;
import com.github.minecraft_ta.totalDebugCompanion.navigation.ModTab;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.ui.Tooltip;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.PageLoader;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.BrowserBody;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;

import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * The settings of every mod under their mod, file and sections, edited like on a mod's Configuration tab; Modified
 * narrows them to the settings that differ from their default. A server configuration is read from the world that
 * changed it last. Files are read again whenever the page is shown, the catalog changes or an edit is written.
 */
public final class PackConfigurationPanel extends JPanel {

    /** A file whose settings are listed; {@code source} names the world of a server configuration. */
    private record Listed(PackCatalog.Mod mod, PackCatalog.ConfigFile file, ConfigSources.Source source) {
    }

    private record Loaded(List<ConfigSettingsTable.Row> rows, Map<String, ConfigSettings.Target> targets,
                          Map<String, Listed> sections, List<String> problems) {
    }

    private final PackCatalogService catalog;
    private final GameLocation location;
    private final Consumer<NavigationTarget> navigator;
    private final ConfigWriter writer;
    private final PageLoader<Loaded> loader;
    private final JCheckBox modifiedOnly = new JCheckBox("Modified");
    private final ConfigSettingsTable table = new ConfigSettingsTable();
    private final BrowserBody body;
    /** Where each listed setting is written, by row path. */
    private Map<String, ConfigSettings.Target> targets = Map.of();
    /** The mod or file of each section row, by row path. */
    private Map<String, Listed> sections = Map.of();
    private String problem = "";
    /** Why nothing could be read at all, such as a catalog that is not captured yet. */
    private String unavailable = "";
    private String status = "";

    public PackConfigurationPanel(PackCatalogService catalog, ConfigSettings configSettings,
                                 Consumer<NavigationTarget> navigator) {
        super(new BorderLayout());
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.location = configSettings.location();
        this.navigator = Objects.requireNonNull(navigator, "navigator");
        this.writer = new ConfigWriter(configSettings, this::setStatus, this::load);

        this.body = new BrowserBody("Filter settings", BrowserBody.scroll(this.table), this.table, this::applyFilter);
        this.modifiedOnly.setToolTipText("Only settings that differ from their default");
        this.modifiedOnly.addActionListener(event -> applyFilter());
        this.body.addOption(this.modifiedOnly);
        add(this.body, BorderLayout.CENTER);

        this.table.setEditing(this::edit, this::setStatus, row -> {
            ConfigSettings.Target target = this.targets.get(row.path());
            return target == null ? null : this.writer.pending(target.file(), target.setting().path());
        }, row -> {
            ConfigSettings.Target target = this.targets.get(row.path());
            return target == null ? null : this.writer.original(target.file(), target.setting().path());
        });
        this.table.setSectionTooltip(this::sectionTooltip);
        this.table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                int row = PackConfigurationPanel.this.table.rowAtPoint(event.getPoint());
                if (row >= 0 && event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)) {
                    open(PackConfigurationPanel.this.table.row(row));
                }
            }
        });
        this.writer.bindUndo(this.table);
        this.loader = new PageLoader<>(this::prepareLoad, loaded -> show(loaded, ""),
                failure -> show(new Loaded(List.of(), Map.of(), Map.of(), List.of()), "Could not read the configuration files: " + failure.getMessage()))
                .whenShown(this).follow(catalog.changed()::subscribe)
                // A server configuration is shown from the copy of the world the game has open, which changes with it.
                .follow(listener -> this.location.addListener(change -> {
                    if (change != GameLocation.Change.PROCESS) listener.run();
                }));
        load();
    }

    /** Reads every file again. */
    public void load() {
        this.loader.load();
    }

    /** Reads the files the captured catalog names; without a catalog there is nothing to read. */
    private Callable<Loaded> prepareLoad() {
        CatalogIndex index = this.catalog.index().orElse(null);
        if (index == null) {
            show(new Loaded(List.of(), Map.of(), Map.of(), List.of()), "The pack catalog is not captured yet.");
            return null;
        }
        return () -> {
            this.writer.refreshPending();
            return read(index);
        };
    }

    /** The settings of every mod, mods by name. Blocking. */
    private Loaded read(CatalogIndex index) {
        List<ConfigSettingsTable.Row> rows = new ArrayList<>();
        Map<String, ConfigSettings.Target> targets = new HashMap<>();
        Map<String, Listed> sections = new HashMap<>();
        List<String> problems = new ArrayList<>();
        List<PackCatalog.Mod> mods = new ArrayList<>(index.mods());
        mods.sort(Comparator.comparing(mod -> mod.name().toLowerCase(Locale.ROOT)));
        for (PackCatalog.Mod mod : mods) {
            boolean modShown = false;
            for (PackCatalog.ConfigFile file : mod.configs()) {
                if (file.settings().isEmpty()) continue;
                List<ConfigSources.Source> sources = ConfigSources.of(this.location, file);
                if (sources.isEmpty()) continue;
                ConfigSources.Source source = sources.getFirst();
                ConfigValues values;
                try {
                    values = ConfigValues.read(source.path());
                } catch (IOException exception) {
                    problems.add(exception.getMessage());
                    continue;
                }
                // Row paths nest the file's own keys under the mod and file, so filtering keeps the rows above a match.
                String filePath = mod.id() + "." + file.fileName();
                List<ConfigSettingsTable.Row> settings = new ArrayList<>();
                for (ConfigSettingsTable.Row row : ConfigSettingsTable.rows(file, values)) {
                    ConfigSettingsTable.Row listed = new ConfigSettingsTable.Row(row.depth() + 2, filePath + "." + row.path(),
                            row.name(), row.comment(), row.setting(), row.value(), row.literal());
                    settings.add(listed);
                    if (row.setting() != null) {
                        targets.put(listed.path(), new ConfigSettings.Target(mod.id(), file.fileName(), source.path(),
                                file.type(), row.setting()));
                    }
                }
                if (!modShown) {
                    rows.add(new ConfigSettingsTable.Row(0, mod.id(), mod.name(), "", null, "", null));
                    sections.put(mod.id(), new Listed(mod, null, null));
                    modShown = true;
                }
                rows.add(new ConfigSettingsTable.Row(1, filePath, file.fileName(), "", null, "", null));
                sections.put(filePath, new Listed(mod, file, source));
                rows.addAll(settings);
            }
        }
        return new Loaded(rows, targets, sections, problems);
    }

    private void show(Loaded loaded, String problem) {
        this.targets = loaded.targets();
        this.sections = loaded.sections();
        this.problem = String.join("; ", loaded.problems());
        showNotice();
        this.table.show(loaded.rows(), true, true);
        this.unavailable = problem;
        applyFilter();
    }

    private void edit(ConfigSettingsTable.Row row, String literal) {
        ConfigSettings.Target target = this.targets.get(row.path());
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
        this.body.showNotice(this.problem.isEmpty() ? this.status : this.problem);
    }

    private void applyFilter() {
        this.table.filter(this.body.filter().getText(), this.modifiedOnly.isSelected());
        if (this.table.getRowCount() > 0) {
            this.body.showContent();
            return;
        }
        this.body.showMessage(!this.unavailable.isEmpty() ? this.unavailable
                : !this.body.query().isEmpty() ? "No setting matches the filter."
                : this.modifiedOnly.isSelected() ? "No setting differs from its default." : "No mod has settings.");
    }

    public void dispose() {
        this.loader.dispose();
    }
}
