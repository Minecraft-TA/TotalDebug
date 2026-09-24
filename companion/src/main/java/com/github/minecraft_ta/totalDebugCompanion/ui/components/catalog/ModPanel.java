package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogIndex;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ModResources;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ModSummary;
import com.github.minecraft_ta.totalDebugCompanion.catalog.PackCatalogService;
import com.github.minecraft_ta.totalDebugCompanion.inspection.ItemIconService;
import com.github.minecraft_ta.totalDebugCompanion.navigation.ModTab;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.navigation.SubjectLinks;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeSourceCatalog;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.EditorTabs;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.inspection.FactsPanel;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.subject.LinkLabel;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.subject.SubjectHeader;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.subject.SubjectIcons;
import com.github.minecraft_ta.totaldebug.protocol.execution.Fact;
import com.github.minecraft_ta.totaldebug.protocol.execution.FactLink;
import com.github.minecraft_ta.totaldebug.protocol.execution.FactSection;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Image;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.zip.ZipFile;

/**
 * An installed mod's page: what it is and depends on, what it registered, its configuration files and its resources.
 * A namespace without a mod shows its registered content; a mod seen before the first capture shows its resources.
 */
public final class ModPanel extends JPanel {
    private static final int LIST_ICON_SIZE = 32;

    private final String modId;
    private final PackCatalogService catalog;
    private final Supplier<RuntimeSourceCatalog> sources;
    private final Consumer<NavigationTarget> navigator;
    private final ItemIconService icons;
    private final CatalogIcons listIcons;
    private final Runnable removeCatalogListener;
    private final SubjectHeader header = new SubjectHeader();
    private final JButton configuration = new JButton("Configuration", Icons.CONFIG_FILE);
    private final JButton browseCode = new JButton("Browse code", Icons.JAVA_CLASS);
    private final JTabbedPane tabs = new JTabbedPane();
    private final Map<ModTab, Component> tabContent = new EnumMap<>(ModTab.class);
    private final JPanel overview = new JPanel(new BorderLayout());
    private final Map<ModTab, CatalogEntryTable> entryTables = new EnumMap<>(ModTab.class);
    private final ConfigTableModel configs = new ConfigTableModel();
    private final JTable configTable = new JTable(this.configs);
    private final ResourceBrowser resources;
    private ModSummary summary;
    private CatalogIndex index;
    private long resourceGeneration;
    private CompletableFuture<?> resourceLoad = CompletableFuture.completedFuture(null);
    private boolean disposed;

    public ModPanel(String modId, PackCatalogService catalog, Supplier<RuntimeSourceCatalog> sources,
                    ItemIconService icons, Consumer<NavigationTarget> navigator) {
        super(new BorderLayout());
        this.modId = Objects.requireNonNull(modId, "modId");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.sources = Objects.requireNonNull(sources, "sources");
        this.icons = Objects.requireNonNull(icons, "icons");
        this.navigator = Objects.requireNonNull(navigator, "navigator");
        this.listIcons = new CatalogIcons(icons, LIST_ICON_SIZE);
        this.resources = new ResourceBrowser(navigator, category -> { });

        this.header.addControl(this.configuration);
        this.header.addControl(this.browseCode);
        this.configuration.addActionListener(event -> openConfiguration());
        this.browseCode.addActionListener(event -> {
            if (this.summary != null && !this.summary.moduleId().isEmpty()) {
                this.navigator.accept(new NavigationTarget.RuntimeModuleNode(this.summary.moduleId()));
            }
        });
        add(this.header, BorderLayout.NORTH);

        this.tabContent.put(ModTab.OVERVIEW, scroll(this.overview));
        entryTab(ModTab.BLOCKS, "Filter blocks");
        entryTab(ModTab.ITEMS, "Filter items");
        entryTab(ModTab.ENTITIES, "Filter entity types");
        this.configTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        this.configTable.setShowGrid(false);
        this.configTable.setFillsViewportHeight(true);
        this.configTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                int row = ModPanel.this.configTable.rowAtPoint(event.getPoint());
                if (event.getClickCount() == 2 && row >= 0) openConfig(ModPanel.this.configs.files.get(row));
            }
        });
        this.tabContent.put(ModTab.CONFIGURATION, new JScrollPane(this.configTable));
        this.tabContent.put(ModTab.RESOURCES, this.resources);
        for (ModTab tab : ModTab.values()) {
            this.tabs.addTab(tab.title(), SubjectIcons.tab(tab), this.tabContent.get(tab));
        }
        add(this.tabs, BorderLayout.CENTER);
        this.removeCatalogListener = catalog.addListener(this::rebuild);
        rebuild();
    }

    private void entryTab(ModTab tab, String placeholder) {
        CatalogEntryTable table = new CatalogEntryTable(placeholder, this.listIcons, this::iconOf,
                entry -> this.navigator.accept(new NavigationTarget.Definition(entry.subject())));
        this.entryTables.put(tab, table);
        this.tabContent.put(tab, table);
    }

    private CatalogIndex.ItemIcon iconOf(CatalogIndex.Entry entry) {
        return this.index == null || entry.iconItem().isEmpty() ? null : this.index.itemIcon(entry.iconItem()).orElse(null);
    }

    public String modId() {
        return this.modId;
    }

    public String title() {
        return this.summary == null ? this.modId : this.summary.title();
    }

    /** Selects the requested tab, and the category on the Resources tab. */
    public void show(NavigationTarget.ModPage page) {
        if (page.tab() == ModTab.RESOURCES) this.resources.selectCategory(page.resourceCategory());
        Component content = this.tabContent.get(page.tab());
        int index = this.tabs.indexOfComponent(content);
        if (index >= 0 && this.tabs.isEnabledAt(index)) this.tabs.setSelectedIndex(index);
    }

    /** The page as it is shown now, for navigation history. */
    public NavigationTarget.ModPage target() {
        ModTab tab = ModTab.OVERVIEW;
        for (Map.Entry<ModTab, Component> entry : this.tabContent.entrySet()) {
            if (entry.getValue() == this.tabs.getSelectedComponent()) tab = entry.getKey();
        }
        return new NavigationTarget.ModPage(this.modId, tab, tab == ModTab.RESOURCES ? this.resources.selectedCategory() : "");
    }

    /** Shows the mod as the current catalog and runtime describe it. */
    void rebuild() {
        if (this.disposed) return;
        PackCatalogService.State state = this.catalog.state();
        this.index = state instanceof PackCatalogService.Ready ready ? ready.index() : null;
        this.summary = ModSummary.resolve(this.modId, this.index, this.sources.get()).orElse(null);
        this.listIcons.clear();
        if (this.summary == null) {
            this.header.setTitle(this.modId);
            this.header.setIcon(Icons.MOD);
            this.header.setSubtitle(List.of(SubjectHeader.text(this.modId)));
            this.configuration.setVisible(false);
            this.browseCode.setVisible(false);
            String unavailable = CatalogMessages.unavailable(state);
            showOverview(message(this.index == null && !unavailable.isEmpty() ? unavailable
                    : this.modId + " is not an installed mod"), null);
            for (ModTab tab : ModTab.values()) this.tabs.setEnabledAt(this.tabs.indexOfComponent(this.tabContent.get(tab)), tab == ModTab.OVERVIEW);
            refreshTitle();
            return;
        }
        PackCatalog.Mod mod = this.summary.mod();
        this.header.setTitle(this.summary.title());
        this.header.setIcon(Icons.MOD);
        List<JComponent> subtitle = new ArrayList<>();
        subtitle.add(SubjectHeader.text(this.summary.id()));
        if (!this.summary.version().isEmpty()) subtitle.add(SubjectHeader.text(this.summary.version()));
        this.header.setSubtitle(subtitle);
        if (mod != null && !mod.logo().isEmpty()) loadLogo(mod.logo());

        List<PackCatalog.ConfigFile> configFiles = mod == null ? List.of() : mod.configs();
        this.configs.files = configFiles;
        this.configs.fireTableDataChanged();
        this.configuration.setVisible(!configFiles.isEmpty());
        this.browseCode.setVisible(!this.summary.moduleId().isEmpty() && hasModule(this.summary.moduleId()));

        String unavailable = this.summary.captured() ? "" : CatalogMessages.unavailable(state);
        showOverview(overviewContent(mod, unavailable), footer(mod));
        setEntries(ModTab.BLOCKS, SubjectRef.DefinitionKind.BLOCK);
        setEntries(ModTab.ITEMS, SubjectRef.DefinitionKind.ITEM);
        setEntries(ModTab.ENTITIES, SubjectRef.DefinitionKind.ENTITY_TYPE);
        setTab(ModTab.CONFIGURATION, configFiles.size());
        loadResources();
        refreshTitle();
    }

    private void setEntries(ModTab tab, SubjectRef.DefinitionKind kind) {
        List<CatalogIndex.Entry> entries = this.index == null || !this.summary.captured()
                ? List.of() : this.index.entries(this.summary.id(), kind);
        this.entryTables.get(tab).setEntries(entries);
        setTab(tab, entries.size());
    }

    private void setTab(ModTab tab, int count) {
        int index = this.tabs.indexOfComponent(this.tabContent.get(tab));
        this.tabs.setTitleAt(index, count == 0 ? tab.title() : tab.title() + " " + NumberFormat.getIntegerInstance(Locale.ROOT).format(count));
        this.tabs.setEnabledAt(index, count > 0);
        if (count == 0 && this.tabs.getSelectedIndex() == index) this.tabs.setSelectedIndex(0);
    }

    private JComponent overviewContent(PackCatalog.Mod mod, String unavailable) {
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        if (mod != null && !mod.description().isEmpty()) {
            JTextArea description = new JTextArea(mod.description());
            description.setEditable(false);
            description.setLineWrap(true);
            description.setWrapStyleWord(true);
            description.setOpaque(false);
            description.setBorder(BorderFactory.createEmptyBorder(8, 12, 4, 12));
            description.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(description);
        }
        if (!unavailable.isEmpty()) {
            JLabel notice = message(unavailable);
            notice.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(notice);
        }
        List<FactSection> sections = sections(mod);
        if (!sections.isEmpty()) {
            FactsPanel facts = new FactsPanel(sections, this.icons, new FactsPanel.Actions() {
                @Override
                public void open(FactLink link) {
                    ModPanel.this.navigator.accept(SubjectLinks.target(link));
                }

                @Override
                public void openData(String section, String label) {
                }
            }, new HashSet<>());
            facts.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(facts);
        }
        if (mod != null && !mod.urls().isEmpty()) {
            JPanel links = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
            links.setBorder(BorderFactory.createEmptyBorder(0, 12, 8, 12));
            links.setAlignmentX(Component.LEFT_ALIGNMENT);
            addUrl(links, "Website", mod.urls().get("display"));
            addUrl(links, "Issues", mod.urls().get("issues"));
            content.add(links);
        }
        return content;
    }

    /** The mod's identity and dependencies; a namespace without a mod lists what it registered. */
    List<FactSection> sections(PackCatalog.Mod mod) {
        List<FactSection> sections = new ArrayList<>();
        List<Fact> identity = new ArrayList<>();
        identity.add(Fact.text("ID", this.summary.id()));
        if (!this.summary.version().isEmpty()) identity.add(Fact.text("Version", this.summary.version()));
        if (mod != null && !mod.authors().isEmpty()) identity.add(Fact.text("Authors", String.join(", ", mod.authors())));
        if (mod != null && !mod.license().isEmpty()) identity.add(Fact.text("License", mod.license()));
        if (!this.summary.moduleId().isEmpty() && !this.summary.moduleId().equals(this.summary.id())) {
            identity.add(Fact.text("Runtime module", this.summary.moduleId()));
        }
        sections.add(new FactSection(mod == null && this.summary.moduleId().isEmpty() ? "Namespace" : "Mod", identity, identity.size()));
        if (mod != null && !mod.dependencies().isEmpty()) {
            List<Fact> dependencies = new ArrayList<>();
            for (PackCatalog.Dependency dependency : mod.dependencies()) {
                Optional<PackCatalog.Mod> installed = this.index == null ? Optional.empty() : this.index.mod(dependency.modId());
                String type = dependency.type().name().toLowerCase(Locale.ROOT);
                StringBuilder value = new StringBuilder(Character.toUpperCase(type.charAt(0)) + type.substring(1));
                if (!dependency.versionRange().isEmpty()) value.append(" · ").append(DependencyVersions.describe(dependency.versionRange()));
                if (dependency.side() != PackCatalog.Side.BOTH) value.append(", ").append(dependency.side().name().toLowerCase(Locale.ROOT)).append(" only");
                if (installed.isEmpty() && dependency.type() == PackCatalog.DependencyType.OPTIONAL) value.append(", not installed");
                Fact fact = Fact.text(installed.map(PackCatalog.Mod::title).orElse(dependency.modId()), value.toString());
                if (installed.isPresent()) fact = fact.withLink(FactLink.toSubject(new SubjectRef.Mod(dependency.modId())));
                dependencies.add(fact);
            }
            sections.add(new FactSection("Dependencies", dependencies, dependencies.size()));
        }
        return sections;
    }

    private JComponent footer(PackCatalog.Mod mod) {
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 4));
        footer.setBorder(BorderFactory.createEmptyBorder(4, 0, 8, 0));
        List<Path> codeFiles = mod != null && "file".equalsIgnoreCase(mod.file().getScheme())
                ? List.of(Path.of(mod.file())) : this.summary.files();
        for (Path file : codeFiles) {
            footer.add(new LinkLabel(file.getFileName().toString(), Icons.JAR_FILE, file.toString(), () -> {
                if (!this.summary.moduleId().isEmpty() && hasModule(this.summary.moduleId())) {
                    this.navigator.accept(new NavigationTarget.RuntimeModuleNode(this.summary.moduleId()));
                }
            }));
        }
        if (mod != null) {
            mod.configs().stream().filter(config -> config.path() != null).findFirst().ifPresent(config -> {
                footer.add(new LinkLabel(config.fileName(), Icons.CONFIG_FILE, config.path().toString(), () -> openConfig(config)));
            });
        }
        return footer.getComponentCount() == 0 ? null : footer;
    }

    private void showOverview(JComponent content, JComponent footer) {
        this.overview.removeAll();
        this.overview.add(content, BorderLayout.NORTH);
        if (footer != null) this.overview.add(footer, BorderLayout.SOUTH);
        this.overview.revalidate();
        this.overview.repaint();
    }

    private void loadResources() {
        long generation = ++this.resourceGeneration;
        List<Path> files = this.summary.files();
        if (files.isEmpty()) {
            this.resources.setResources(List.of());
            setTab(ModTab.RESOURCES, 0);
            return;
        }
        CompletableFuture<List<ModResources.Resource>> loading = CompletableFuture.supplyAsync(() -> {
            try {
                return ModResources.list(files);
            } catch (IOException exception) {
                throw new IllegalStateException(exception.getMessage(), exception);
            }
        });
        this.resourceLoad = loading;
        loading.whenComplete((list, failure) -> SwingUtilities.invokeLater(() -> {
            if (this.disposed || generation != this.resourceGeneration) return;
            this.resources.setResources(failure == null ? list : List.of());
            this.resources.setMessage(failure == null ? "" : "Resources could not be read: " + failure.getCause().getMessage());
            setTab(ModTab.RESOURCES, failure == null ? list.size() : 1);
        }));
    }

    private void loadLogo(String logo) {
        List<Path> files = this.summary.files();
        if (files.isEmpty()) return;
        Path file = files.getFirst();
        CompletableFuture.supplyAsync(() -> readLogo(file, logo)).thenAccept(image -> SwingUtilities.invokeLater(() -> {
            if (!this.disposed && image != null) this.header.setIcon(image);
        }));
    }

    static Icon readLogo(Path file, String logo) {
        try {
            BufferedImage image;
            if (Files.isDirectory(file)) {
                image = ImageIO.read(file.resolve(logo).toFile());
            } else {
                try (ZipFile zip = new ZipFile(file.toFile())) {
                    var entry = zip.getEntry(logo);
                    if (entry == null) return null;
                    try (InputStream input = zip.getInputStream(entry)) {
                        image = ImageIO.read(input);
                    }
                }
            }
            if (image == null) return null;
            double scale = Math.min(144.0 / image.getWidth(),
                    (double) SubjectHeader.ICON_SIZE / image.getHeight());
            int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
            // Logos often have white or black lettering on transparency. Keep it readable in either theme.
            long brightness = 0;
            long alpha = 0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int pixel = image.getRGB(x, y);
                    int a = pixel >>> 24;
                    brightness += (long) a * (((pixel >>> 16) & 255) + ((pixel >>> 8) & 255) + (pixel & 255));
                    alpha += a;
                }
            }
            BufferedImage fitted = new BufferedImage(width + 8, height + 8, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = fitted.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                graphics.setColor(brightness > alpha * 3 * 128 ? new Color(0x2B2D30) : new Color(0xF2F3F5));
                graphics.fillRoundRect(0, 0, fitted.getWidth(), fitted.getHeight(), 6, 6);
                graphics.drawImage(image.getScaledInstance(width, height, Image.SCALE_SMOOTH), 4, 4, null);
            } finally {
                graphics.dispose();
            }
            return new ImageIcon(fitted);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private void openConfiguration() {
        List<PackCatalog.ConfigFile> files = this.configs.files;
        List<PackCatalog.ConfigFile> openable = files.stream().filter(config -> config.path() != null).toList();
        if (openable.size() == 1 && files.size() == 1) {
            openConfig(openable.getFirst());
        } else {
            this.tabs.setSelectedComponent(this.tabContent.get(ModTab.CONFIGURATION));
        }
    }

    private void openConfig(PackCatalog.ConfigFile config) {
        if (config.path() != null) this.navigator.accept(new NavigationTarget.LocalFile(config.path()));
    }

    private boolean hasModule(String moduleId) {
        return this.sources.get().modules().stream().anyMatch(module -> module.id().equals(moduleId));
    }

    private void refreshTitle() {
        if (SwingUtilities.getAncestorOfClass(EditorTabs.class, this) instanceof EditorTabs owner) {
            owner.refreshEditorTitles();
        }
    }

    private static void addUrl(JPanel links, String label, String url) {
        if (url == null || url.isBlank()) return;
        links.add(new LinkLabel(label, Icons.WEB, url, () -> {
            try {
                Desktop.getDesktop().browse(URI.create(url));
            } catch (IOException | RuntimeException ignored) {
                // The URL stays visible in the tooltip when no browser can open it.
            }
        }));
    }

    private static JLabel message(String text) {
        JLabel label = new JLabel(text);
        label.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        return label;
    }

    private static JScrollPane scroll(JComponent component) {
        JScrollPane scroll = new JScrollPane(component);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    /** The listing of this page's resources that runs or ran last; mod files stay open while it runs. */
    CompletableFuture<?> resourceLoad() {
        return this.resourceLoad;
    }

    public void dispose() {
        this.disposed = true;
        this.removeCatalogListener.run();
        this.listIcons.dispose();
    }

    JTabbedPane tabs() {
        return this.tabs;
    }

    CatalogEntryTable entryTable(ModTab tab) {
        return this.entryTables.get(tab);
    }

    private static final class ConfigTableModel extends AbstractTableModel {
        private List<PackCatalog.ConfigFile> files = List.of();

        @Override
        public int getRowCount() {
            return this.files.size();
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> "File";
                case 1 -> "Type";
                default -> "Path";
            };
        }

        @Override
        public Object getValueAt(int row, int column) {
            PackCatalog.ConfigFile file = this.files.get(row);
            return switch (column) {
                case 0 -> file.fileName();
                case 1 -> file.type().name().toLowerCase(Locale.ROOT);
                default -> file.path() == null ? "Per world" : file.path().toString();
            };
        }
    }
}
