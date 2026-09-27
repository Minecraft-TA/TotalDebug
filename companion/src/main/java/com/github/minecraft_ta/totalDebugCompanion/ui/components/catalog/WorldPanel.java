package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.catalog.WorldReadings;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CurrentWorld;
import com.github.minecraft_ta.totalDebugCompanion.catalog.PackCatalogService;
import com.github.minecraft_ta.totalDebugCompanion.inspection.ItemIconService;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.navigation.WorldTab;
import com.github.minecraft_ta.totalDebugCompanion.ui.Tooltip;
import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.PageLoader;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.PixelImages;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.TabTitles;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.TypeToFilter;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.EditorTabs;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.inspection.FactsPanel;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.subject.LinkLabel;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.subject.PlateIcon;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.subject.SubjectHeader;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.subject.SubjectIcons;
import com.github.minecraft_ta.totaldebug.protocol.execution.Fact;
import com.github.minecraft_ta.totaldebug.protocol.execution.FactSection;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The current world as its {@code level.dat} saved it: an overview of its settings, time and weather, its game rules
 * and its datapacks. It is read whenever the page is shown, so it works without the game; while the game has the world
 * open, the header says when the game saved it last.
 */
public final class WorldPanel extends JPanel {
    private static final String PAGE_CARD = "page";
    private static final String MESSAGE_CARD = "message";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withZone(ZoneId.systemDefault());

    /** A read world with its icon, or null for either; {@code problem} says why there is no world. */
    private record Loaded(CurrentWorld.Saved saved, BufferedImage icon, String problem) {
    }

    private final Path workspace;
    private final PackCatalogService catalog;
    private final ItemIconService icons;
    private final WorldReadings readings;
    private final PageLoader<Loaded> loader;
    private final Runnable removeCatalogListener;
    private final SubjectHeader header = new SubjectHeader();
    private final JTabbedPane tabs = new JTabbedPane();
    private final Map<WorldTab, Component> tabContent = new EnumMap<>(WorldTab.class);
    private final JPanel overview = new JPanel(new BorderLayout());
    private final GameRulesPanel rules = new GameRulesPanel();
    private final DatapacksPanel datapacks;
    private final JLabel message = new JLabel();
    private final JPanel cards = new JPanel(new CardLayout());
    private CurrentWorld.Saved saved;
    private WorldTab requested;
    private boolean disposed;

    /**
     * {@code workspace} is the game directory, whose {@code saves} hold the worlds; each read is recorded in
     * {@code readings}, which the Project tree follows.
     */
    public WorldPanel(Path workspace, PackCatalogService catalog, ItemIconService icons, WorldReadings readings,
                      Consumer<NavigationTarget> navigator) {
        super(new BorderLayout());
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.readings = Objects.requireNonNull(readings, "readings");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.icons = Objects.requireNonNull(icons, "icons");
        this.datapacks = new DatapacksPanel(Objects.requireNonNull(navigator, "navigator"));

        this.tabContent.put(WorldTab.OVERVIEW, scroll(this.overview));
        this.tabContent.put(WorldTab.GAME_RULES, this.rules);
        this.tabContent.put(WorldTab.DATAPACKS, this.datapacks);
        for (WorldTab tab : WorldTab.values()) {
            this.tabs.addTab(tab.title(), SubjectIcons.tab(tab), this.tabContent.get(tab));
        }
        TypeToFilter.forwardTyping(this.tabs, this::selectedFilter);
        JPanel page = new JPanel(new BorderLayout());
        page.add(this.header, BorderLayout.NORTH);
        page.add(this.tabs, BorderLayout.CENTER);
        // The message comes first, so the page stays empty until the world is read.
        this.message.setVerticalAlignment(JLabel.TOP);
        this.message.setBorder(UiMetrics.messagePadding());
        this.cards.add(this.message, MESSAGE_CARD);
        this.cards.add(page, PAGE_CARD);
        add(this.cards, BorderLayout.CENTER);

        // Only the names of the mods behind datapacks come from the catalog.
        this.removeCatalogListener = catalog.addListener(() -> SwingUtilities.invokeLater(() -> {
            if (!this.disposed && this.saved != null) this.datapacks.setPacks(this.saved.datapacks(), this.catalog.index().orElse(null));
        }));
        // The game saves the world while it runs, so the page reads it whenever it is shown.
        this.loader = new PageLoader<>(() -> () -> read(this.workspace), this::show,
                failure -> show(new Loaded(null, null, "The world could not be read: " + failure.getMessage()))).whenShown(this);
    }

    private static Loaded read(Path workspace) {
        Optional<Path> world = CurrentWorld.directory(workspace);
        if (world.isEmpty()) return new Loaded(null, null, "No world has been played in this instance yet.");
        try {
            CurrentWorld.Saved saved = CurrentWorld.read(world.get());
            return new Loaded(saved, icon(world.get().resolve("icon.png")), "");
        } catch (IOException | RuntimeException unreadable) {
            return new Loaded(null, null, "The world " + world.get().getFileName() + " could not be read: " + unreadable.getMessage());
        }
    }

    /** The world's icon, which the game takes when the world is first saved; null without one. */
    private static BufferedImage icon(Path file) {
        if (!Files.isRegularFile(file)) return null;
        try {
            BufferedImage image = ImageIO.read(file.toFile());
            return image == null ? null : PixelImages.fitWithin(image, SubjectHeader.ICON_SIZE, SubjectHeader.ICON_SIZE);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private void show(Loaded loaded) {
        this.saved = loaded.saved();
        this.readings.read(WorldReadings.Summary.of(this.saved));
        if (this.saved == null) {
            this.message.setText(loaded.problem());
            ((CardLayout) this.cards.getLayout()).show(this.cards, MESSAGE_CARD);
            refreshTitle();
            return;
        }
        CurrentWorld.Saved saved = this.saved;
        this.header.setTitle(saved.name());
        this.header.setIcon(loaded.icon() == null ? new PlateIcon(Icons.WORLD, SubjectHeader.ICON_SIZE) : new ImageIcon(loaded.icon()));
        List<JComponent> subtitle = new ArrayList<>();
        String time = TIME.format(saved.open() ? saved.saved().toInstant() : saved.lastPlayed());
        subtitle.add(SubjectHeader.text(saved.open() ? "Open in the game, saved " + time : "Last played " + time));
        if (!saved.version().isEmpty()) subtitle.add(SubjectHeader.text(saved.version()));
        this.header.setSubtitle(subtitle);

        showOverview(saved);
        this.rules.setRules(saved.gameRules());
        this.datapacks.setPacks(saved.datapacks(), this.catalog.index().orElse(null));
        setTab(WorldTab.GAME_RULES, saved.gameRules().size());
        setTab(WorldTab.DATAPACKS, saved.datapacks().size());
        ((CardLayout) this.cards.getLayout()).show(this.cards, PAGE_CARD);
        if (this.requested != null) show(this.requested);
        refreshTitle();
    }

    private void showOverview(CurrentWorld.Saved saved) {
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        FactsPanel facts = new FactsPanel(sections(saved), this.icons);
        facts.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(facts);
        LinkLabel folder = new LinkLabel(saved.directory().getFileName().toString(), Icons.FOLDER,
                Tooltip.of("Show in Explorer").detail(Tooltip.shortPath(saved.directory())).html(),
                () -> Explorer.show(saved.directory()));
        content.add(new PageSection("Files", PageSection.linkRows(List.of(new PageSection.LinkRow("Folder", List.of(folder))))));
        this.overview.removeAll();
        this.overview.add(content, BorderLayout.NORTH);
        this.overview.revalidate();
        this.overview.repaint();
    }

    /** The world's settings, then its time and weather, each noting a game rule that holds it still. */
    static List<FactSection> sections(CurrentWorld.Saved saved) {
        List<Fact> world = new ArrayList<>();
        // A seed is typed into the game as it is, and a spawn's coordinates are separated by commas, so neither is grouped.
        if (saved.seed() != null) world.add(Fact.text("Seed", Long.toString(saved.seed())));
        world.add(Fact.text("Game mode", saved.gameMode() + (saved.hardcore() ? ", hardcore" : "")));
        world.add(Fact.text("Difficulty", saved.difficulty() + (saved.difficultyLocked() ? ", locked" : "")));
        world.add(Fact.text("Commands", saved.commands() ? "Allowed" : "Not allowed"));
        CurrentWorld.Spawn spawn = saved.spawn();
        world.add(Fact.text("Spawn", spawn.x() + ", " + spawn.y() + ", " + spawn.z()));
        List<Fact> time = new ArrayList<>();
        time.add(Fact.text("Day", NumberFormat.getIntegerInstance(Locale.ROOT).format(saved.day())));
        boolean clockStopped = "false".equals(saved.gameRules().get("doDaylightCycle"));
        time.add(Fact.text("Time", saved.timeOfDay() + (clockStopped ? ", stopped" : "")));
        boolean weatherStopped = "false".equals(saved.gameRules().get("doWeatherCycle"));
        time.add(Fact.text("Weather", saved.weather() + (weatherStopped ? ", stopped" : "")));
        return List.of(new FactSection("World", world, world.size()), new FactSection("Time and weather", time, time.size()));
    }

    /** Selects a tab, now or once the world is read. */
    public void show(WorldTab tab) {
        this.requested = tab;
        if (this.saved == null) return;
        int index = this.tabs.indexOfComponent(this.tabContent.get(tab));
        if (index >= 0) this.tabs.setSelectedIndex(index);
        this.requested = null;
    }

    /** The tab shown now, or the one to show once the world is read, for navigation history. */
    public WorldTab selectedTab() {
        if (this.requested != null) return this.requested;
        for (Map.Entry<WorldTab, Component> entry : this.tabContent.entrySet()) {
            if (entry.getValue() == this.tabs.getSelectedComponent()) return entry.getKey();
        }
        return WorldTab.OVERVIEW;
    }

    /** The world's name once it is read. */
    public String title() {
        return this.saved == null ? "World" : this.saved.name();
    }

    /** Shows a tab with its count, or hides it while it has nothing to show. */
    private void setTab(WorldTab tab, int count) {
        TabTitles.setShown(this.tabs, List.copyOf(this.tabContent.values()), this.tabContent.get(tab), tab.title(),
                SubjectIcons.tab(tab), count);
    }

    private void refreshTitle() {
        if (SwingUtilities.getAncestorOfClass(EditorTabs.class, this) instanceof EditorTabs owner) {
            owner.refreshEditorTitles();
        }
    }

    /** The filter of the selected tab, which typing on the tab strip goes to; null for the Overview. */
    private JTextComponent selectedFilter() {
        Component selected = this.tabs.getSelectedComponent();
        if (selected == this.rules) return this.rules.filterField();
        if (selected == this.datapacks) return this.datapacks.filterField();
        return null;
    }

    private static JScrollPane scroll(JComponent component) {
        JScrollPane scroll = new JScrollPane(component);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    public void dispose() {
        this.disposed = true;
        this.loader.dispose();
        this.removeCatalogListener.run();
    }

    GameRulesPanel rules() {
        return this.rules;
    }

    DatapacksPanel datapacks() {
        return this.datapacks;
    }

    JTabbedPane tabs() {
        return this.tabs;
    }
}
