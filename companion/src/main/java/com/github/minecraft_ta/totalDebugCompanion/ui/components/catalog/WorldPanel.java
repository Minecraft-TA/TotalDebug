package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.game.GameLocation;
import com.github.minecraft_ta.totalDebugCompanion.catalog.WorldReading;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CurrentWorld;
import com.github.minecraft_ta.totalDebugCompanion.game.GameState;
import com.github.minecraft_ta.totalDebugCompanion.catalog.PackCatalogService;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ListedPack;
import com.github.minecraft_ta.totalDebugCompanion.inspection.ItemIconService;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.navigation.WorldTab;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totalDebugCompanion.ui.Tooltip;
import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.NoticeLine;
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
import com.github.minecraft_ta.totalDebugCompanion.pack.GamePacks;
import com.github.minecraft_ta.totalDebugCompanion.pack.PackResources;
import com.github.minecraft_ta.totalDebugCompanion.pack.PackSelections;
import com.github.minecraft_ta.totalDebugCompanion.pack.ResourceEdits;
import com.github.minecraft_ta.totaldebug.protocol.execution.Fact;
import com.github.minecraft_ta.totaldebug.protocol.execution.FactSection;
import com.github.minecraft_ta.totaldebug.protocol.message.PackStackPayload;

import com.github.minecraft_ta.totaldebug.protocol.message.PlayingPayload;
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
import java.util.concurrent.CompletableFuture;

/**
 * The current world as its {@code level.dat} saved it: an overview of its settings, time and weather, its game rules
 * and its datapacks. It is read whenever the page is shown, so it works without the game; while the game has the world
 * open, the header says when the game saved it last. While the game plays on a server, the page shows that server's
 * world as the server names it: its datapacks, changed live, since the rest of it is on the server.
 */
public final class WorldPanel extends JPanel {
    private static final String PAGE_CARD = "page";
    private static final String MESSAGE_CARD = "message";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withZone(ZoneId.systemDefault());

    /** A read world with its icon, or null for either; {@code problem} says why there is no world. */
    record Loaded(CurrentWorld.Saved saved, ServerWorld server, List<ListedPack> datapacks, BufferedImage icon, String problem) {
        static Loaded problem(String problem) {
            return new Loaded(null, null, List.of(), null, problem);
        }
    }

    /** The world of the server the game plays on: its address, and where the change record keeps it. */
    record ServerWorld(String address, Path world) {
    }

    private final PackCatalogService catalog;
    private final ItemIconService icons;
    private final WorldReading world;
    private final GameLocation location;
    private final PageLoader<Loaded> loader;
    private final SubjectHeader header = new SubjectHeader();
    /** Why Show in Explorer could not show the world. */
    private final NoticeLine notice = new NoticeLine();
    private final JTabbedPane tabs = new JTabbedPane();
    private final Map<WorldTab, Component> tabContent = new EnumMap<>(WorldTab.class);
    private final JPanel overview = new JPanel(new BorderLayout());
    private final GameRulesPanel rules = new GameRulesPanel();
    private final PacksPanel datapacks;
    private final JLabel message = new JLabel();
    private final JPanel cards = new JPanel(new CardLayout());
    private CurrentWorld.Saved saved;
    /** The server's world shown instead of a world of the instance, or null. */
    private ServerWorld server;
    /** Where the shown datapacks are changed: the world's folder, or the place the change record keeps a server's world. */
    private Path shownWorld;
    /** The world's datapacks: as the connected game names them while it has the world open, or as level.dat saved them. */
    private List<ListedPack> datapackList = List.of();
    private WorldTab requested;
    private boolean disposed;

    /** Shows {@code world}, the current world, or the world of the server the game {@code edits} tells of plays on. */
    public WorldPanel(PackCatalogService catalog, ItemIconService icons, WorldReading world,
                      ResourceEdits edits, PackSelections selections, Consumer<NavigationTarget> navigator) {
        super(new BorderLayout());
        this.world = Objects.requireNonNull(world, "world");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.icons = Objects.requireNonNull(icons, "icons");
        this.datapacks = new PacksPanel(PacksPanel.Side.DATA, Objects.requireNonNull(navigator, "navigator"));
        this.datapacks.setApplier(enabled -> {
            Path shown = this.saved != null ? this.saved.directory() : this.server != null ? this.server.world() : null;
            if (shown == null) return CompletableFuture.failedFuture(new IllegalStateException("No world is shown"));
            return selections.set(ChangeRecord.PackSide.DATA, shown, enabled);
        }, "Enables the checked datapacks in this order: in the world the connected game plays, which reloads its data, otherwise in the world's level.dat");

        this.tabContent.put(WorldTab.OVERVIEW, scroll(this.overview));
        this.tabContent.put(WorldTab.GAME_RULES, this.rules);
        this.tabContent.put(WorldTab.DATAPACKS, this.datapacks);
        for (WorldTab tab : WorldTab.values()) {
            this.tabs.addTab(tab.title(), SubjectIcons.tab(tab), this.tabContent.get(tab));
        }
        TypeToFilter.forwardTyping(this.tabs, this::selectedFilter);
        JPanel page = new JPanel(new BorderLayout());
        JPanel top = new JPanel(new BorderLayout());
        top.add(this.header, BorderLayout.NORTH);
        top.add(this.notice, BorderLayout.SOUTH);
        page.add(top, BorderLayout.NORTH);
        page.add(this.tabs, BorderLayout.CENTER);
        // The message comes first, so the page stays empty until the world is read.
        this.message.setVerticalAlignment(JLabel.TOP);
        this.message.setBorder(UiMetrics.messagePadding());
        this.cards.add(this.message, MESSAGE_CARD);
        this.cards.add(page, PAGE_CARD);
        add(this.cards, BorderLayout.CENTER);

        // The world's owner reads it when the game plays another and when the user comes back to Companion; the
        // datapacks the connected game names come with its packs.
        this.loader = new PageLoader<>(() -> {
            // The datapacks and the refusal the server named together.
            GamePacks.Named named = edits.packs().named();
            return () -> read(edits.location().read(), this.world, named.datapacks(), named.worldRefusal());
        }, this::show, failure -> show(Loaded.problem("The world could not be read: " + failure.getMessage())))
                .page(this).follows(world.changed()).follows(edits.packs().changed(ChangeRecord.PackSide.DATA))
                // Only the names of the mods behind datapacks come from the catalog.
                .updates(catalog.changed(), () -> {
                    if (this.saved != null || this.server != null) this.datapacks.setPacks(this.datapackList, this.catalog.index().orElse(null));
                })
                // The tab names the world the owner read, also while the page is hidden.
                .retitles(world.changed(), this::refreshTitle).retitles(edits.location().playingChanged(), this::refreshTitle)
                .retitles(edits.location().connectionChanged(), this::refreshTitle);
        this.location = edits.location();
    }

    private static Loaded read(GameState game, WorldReading world, PackStackPayload stack, String refusal) throws IOException {
        Optional<PlayingPayload.Multiplayer> server = game.server();
        if (server.isPresent()) return readServer(game, server.get(), stack, refusal);
        WorldReading.World current = world.value();
        if (current.directory() == null) return Loaded.problem("No world has been played in this instance yet.");
        if (current.saved() == null) return Loaded.problem("The world " + current.directory().getFileName() + " could not be read.");
        CurrentWorld.Saved saved = current.saved();
        return new Loaded(saved, null, PackResources.worldDatapacks(stack, saved), icon(current.directory().resolve("icon.png")), "");
    }

    /**
     * The world of the server the game plays on, as the server named its datapacks; or why it cannot be shown: the
     * server lacks TotalDebug, does not let the player change its world, or has not named them yet.
     */
    static Loaded readServer(GameState game, PlayingPayload.Multiplayer server, PackStackPayload stack, String refusal) {
        String address = server.address().isEmpty() ? "this server" : server.address();
        if (!server.totalDebug()) return Loaded.problem("The server " + address + " does not have TotalDebug, which Companion needs to show its world.");
        if (!refusal.isEmpty()) return Loaded.problem(refusal + ".");
        if (stack == null) return Loaded.problem("Waiting for the server " + address + " to name its world's datapacks.");
        return new Loaded(null, new ServerWorld(address, game.serverWorld(server)), PackResources.serverDatapacks(stack), null, "");
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
        this.server = loaded.server();
        this.datapackList = loaded.datapacks();
        Path world = this.server != null ? this.server.world() : this.saved != null ? this.saved.directory() : null;
        // Changes staged for another world, such as the server played before, would otherwise be applied to this one.
        if (!Objects.equals(world, this.shownWorld)) this.datapacks.discardChanges();
        this.shownWorld = world;
        if (this.server != null) {
            showServer(this.server);
            return;
        }
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
        this.datapacks.setPacks(this.datapackList, this.catalog.index().orElse(null));
        setTab(WorldTab.GAME_RULES, saved.gameRules().size());
        setTab(WorldTab.DATAPACKS, this.datapackList.size());
        ((CardLayout) this.cards.getLayout()).show(this.cards, PAGE_CARD);
        if (this.requested != null) show(this.requested);
        refreshTitle();
    }

    /** The server's world: its address, and its datapacks, which the server names; the rest of the world is on the server. */
    private void showServer(ServerWorld server) {
        this.header.setTitle(server.address());
        this.header.setIcon(new PlateIcon(Icons.WORLD, SubjectHeader.ICON_SIZE));
        this.header.setSubtitle(List.of(SubjectHeader.text("Played on this server now")));
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        FactsPanel facts = new FactsPanel(List.of(new FactSection("Server", List.of(Fact.text("Address", server.address()),
                Fact.text("World", "Kept on the server, which names its datapacks")), 2)), this.icons);
        facts.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(facts);
        this.overview.removeAll();
        this.overview.add(content, BorderLayout.NORTH);
        this.overview.revalidate();
        this.overview.repaint();
        this.rules.setRules(Map.of());
        this.datapacks.setPacks(this.datapackList, this.catalog.index().orElse(null));
        setTab(WorldTab.GAME_RULES, 0);
        setTab(WorldTab.DATAPACKS, this.datapackList.size());
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
                () -> this.notice.show(Explorer.show(saved.directory()).orElse("")));
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
        if (this.saved == null && this.server == null) return;
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

    /** The address of the server the game plays on, or the current world's name as its owner read it last. */
    public String title() {
        if (this.location.playing() instanceof PlayingPayload.Multiplayer server) return server.address();
        return this.world.publishedName().orElse("World");
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

    /** How many times the page read the world, which tests count. */
    public int reads() {
        return this.loader.reads();
    }

    public void dispose() {
        this.disposed = true;
        this.loader.dispose();
    }

    GameRulesPanel rules() {
        return this.rules;
    }

    PacksPanel datapacks() {
        return this.datapacks;
    }

    JTabbedPane tabs() {
        return this.tabs;
    }
}
