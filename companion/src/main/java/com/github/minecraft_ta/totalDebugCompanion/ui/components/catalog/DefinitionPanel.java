package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogIndex;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ModResources;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ModSummary;
import com.github.minecraft_ta.totalDebugCompanion.catalog.PackCatalogService;
import com.github.minecraft_ta.totalDebugCompanion.inspection.ItemIconService;
import com.github.minecraft_ta.totalDebugCompanion.itemrender.ItemRenderResourceRoot;
import com.github.minecraft_ta.totalDebugCompanion.itemrender.ModelAppearance;
import com.github.minecraft_ta.totalDebugCompanion.resource.FileTypeResolver;
import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.CenteredIcon;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.PixelImages;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.navigation.SubjectLinks;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeSourceCatalog;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.EditorTabs;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.inspection.FactsPanel;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.inspection.ItemTabIcon;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.subject.LinkLabel;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.subject.SubjectHeader;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.subject.SubjectIcons;
import com.github.minecraft_ta.totaldebug.protocol.execution.Fact;
import com.github.minecraft_ta.totaldebug.protocol.execution.FactLink;
import com.github.minecraft_ta.totaldebug.protocol.execution.FactSection;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.SwingConstants;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.util.Set;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A registered block, item or entity type as the captured pack catalog describes it, with the textures it is drawn
 * with and the files that define it.
 */
public final class DefinitionPanel extends JPanel {
    private final SubjectRef.Definition subject;
    private final PackCatalogService catalog;
    private final Supplier<RuntimeSourceCatalog> sources;
    private final ItemIconService icons;
    private final Consumer<NavigationTarget> navigator;
    private final Runnable removeCatalogListener;
    private final Runnable removeIconListener;
    private final SubjectHeader header = new SubjectHeader();
    private final ItemTabIcon tabIcon;
    private final JPanel overview = new JPanel(new BorderLayout());
    private final JPanel extras = new JPanel();
    private CatalogIndex index;
    private CatalogIndex.Entry entry;
    private long generation;
    private long appearanceGeneration;
    private ModelAppearance appearance;
    private List<ModResources.Resource> matched = List.of();
    private List<ModResources.Resource> owned = List.of();
    private CompletableFuture<?> resourceLoad = CompletableFuture.completedFuture(null);
    private boolean disposed;

    public DefinitionPanel(SubjectRef.Definition subject, PackCatalogService catalog, Supplier<RuntimeSourceCatalog> sources,
                           ItemIconService icons, Consumer<NavigationTarget> navigator) {
        super(new BorderLayout());
        this.subject = Objects.requireNonNull(subject, "subject");
        this.tabIcon = new ItemTabIcon(SubjectIcons.definition(subject.kind()));
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.sources = Objects.requireNonNull(sources, "sources");
        this.icons = Objects.requireNonNull(icons, "icons");
        this.navigator = Objects.requireNonNull(navigator, "navigator");
        add(this.header, BorderLayout.NORTH);
        this.extras.setLayout(new BoxLayout(this.extras, BoxLayout.Y_AXIS));
        JPanel page = new JPanel(new BorderLayout());
        page.add(this.overview, BorderLayout.NORTH);
        page.add(this.extras, BorderLayout.CENTER);
        JScrollPane scroll = new JScrollPane(page);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);
        this.removeCatalogListener = catalog.addListener(this::rebuild);
        this.removeIconListener = icons.addListener(() -> {
            loadIcon();
            loadAppearance();
        });
        rebuild();
    }

    public SubjectRef.Definition subject() {
        return this.subject;
    }

    public String title() {
        return this.entry == null ? this.subject.id() : this.entry.title();
    }

    public ItemTabIcon tabIcon() {
        return this.tabIcon;
    }

    static String kindLabel(SubjectRef.DefinitionKind kind) {
        return switch (kind) {
            case BLOCK -> "Block type";
            case ITEM -> "Item type";
            case ENTITY_TYPE -> "Entity type";
        };
    }

    void rebuild() {
        if (this.disposed) return;
        PackCatalogService.State state = this.catalog.state();
        this.index = state instanceof PackCatalogService.Ready ready ? ready.index() : null;
        this.entry = this.index == null ? null : this.index.entry(this.subject).orElse(null);
        this.header.setTitle(title());
        String namespace = this.subject.namespace();
        List<JComponent> subtitle = new ArrayList<>();
        subtitle.add(SubjectHeader.text(kindLabel(this.subject.kind())));
        subtitle.add(SubjectHeader.text(this.subject.id()));
        String owner = this.index == null ? namespace : this.index.ownerName(namespace);
        subtitle.add(new LinkLabel(owner, Icons.MOD, "mod " + namespace,
                () -> this.navigator.accept(new NavigationTarget.ModPage(namespace))));
        this.header.setSubtitle(subtitle);
        this.overview.removeAll();
        if (this.entry == null) {
            String unavailable = CatalogMessages.unavailable(state);
            this.overview.add(message(unavailable.isEmpty() ? this.subject.id() + " is not in the captured catalog" : unavailable),
                    BorderLayout.NORTH);
        } else {
            FactsPanel facts = new FactsPanel(sections(), this.icons, new FactsPanel.Actions() {
                @Override
                public void open(FactLink link) {
                    DefinitionPanel.this.navigator.accept(SubjectLinks.target(link));
                }

                @Override
                public void openData(String section, String label) {
                }
            }, new HashSet<>());
            this.overview.add(facts, BorderLayout.NORTH);
        }
        this.overview.revalidate();
        this.overview.repaint();
        loadIcon();
        loadAppearance();
        loadResources();
        if (SwingUtilities.getAncestorOfClass(EditorTabs.class, this) instanceof EditorTabs editorTabs) {
            editorTabs.refreshEditorTitles();
        }
    }

    /** What the catalog records about the definition, with links to related definitions, its mod and its class. */
    List<FactSection> sections() {
        List<Fact> facts = new ArrayList<>();
        facts.add(Fact.text("ID", this.subject.id()));
        String namespace = this.subject.namespace();
        this.index.mod(namespace).ifPresent(mod ->
                facts.add(Fact.text("Mod", mod.title()).withLink(FactLink.toSubject(new SubjectRef.Mod(mod.id())))));
        switch (this.subject.kind()) {
            case BLOCK -> this.index.block(this.subject.id()).ifPresent(block -> {
                classFact(facts, block.className());
                related(facts, "Item", SubjectRef.DefinitionKind.ITEM, block.item());
                if (!block.blockEntityType().isEmpty()) facts.add(Fact.text("Block entity type", block.blockEntityType()));
            });
            case ITEM -> this.index.item(this.subject.id()).ifPresent(item -> {
                classFact(facts, item.className());
                related(facts, "Block", SubjectRef.DefinitionKind.BLOCK, item.block());
                if (!item.model().isEmpty()) facts.add(Fact.text("Model", item.model()));
            });
            case ENTITY_TYPE -> this.index.entityType(this.subject.id()).ifPresent(type -> {
                if (!type.category().isEmpty()) facts.add(Fact.text("Category", type.category()));
                related(facts, "Spawn egg", SubjectRef.DefinitionKind.ITEM, type.spawnEgg());
            });
        }
        String title = switch (this.subject.kind()) {
            case BLOCK -> "Block";
            case ITEM -> "Item";
            case ENTITY_TYPE -> "Entity type";
        };
        return List.of(new FactSection(title, facts, facts.size()));
    }

    private static void classFact(List<Fact> facts, String className) {
        if (className.isEmpty()) return;
        facts.add(Fact.text("Class", className.substring(className.lastIndexOf('.') + 1))
                .withLink(FactLink.toClass(className)));
    }

    private void related(List<Fact> facts, String label, SubjectRef.DefinitionKind kind, String id) {
        if (id.isEmpty()) return;
        SubjectRef.Definition related = new SubjectRef.Definition(kind, id);
        String name = this.index.entry(related).map(CatalogIndex.Entry::title).orElse(id);
        facts.add(Fact.text(label, name).withLink(FactLink.toSubject(related)));
    }

    private void loadIcon() {
        if (this.disposed) return;
        Optional<CatalogIndex.ItemIcon> icon = this.index == null || this.entry == null || this.entry.iconItem().isEmpty()
                ? Optional.empty() : this.index.itemIcon(this.entry.iconItem());
        if (icon.isEmpty()) {
            this.header.setIcon(SubjectIcons.definition(this.subject.kind()));
            return;
        }
        this.icons.render(icon.get().model(), icon.get().tints(), SubjectHeader.ICON_SIZE)
                .thenAccept(image -> SwingUtilities.invokeLater(() -> {
                    if (this.disposed) return;
                    this.header.setIcon(image.<Icon>map(ImageIcon::new).orElse(SubjectIcons.definition(this.subject.kind())));
                }));
        this.icons.render(icon.get().model(), icon.get().tints(), this.tabIcon.size())
                .thenAccept(image -> SwingUtilities.invokeLater(() -> {
                    if (this.disposed) return;
                    this.tabIcon.setImage(image.orElse(null));
                    Component tabs = SwingUtilities.getAncestorOfClass(JTabbedPane.class, this);
                    if (tabs != null) tabs.repaint();
                }));
    }

    /**
     * The blockstate, models and textures in the latest resource snapshot. A block also shows its item's model; an
     * item that places a block is drawn by its own model.
     */
    private void loadAppearance() {
        if (this.disposed) return;
        long current = ++this.appearanceGeneration;
        String blockId = "";
        String itemModel = "";
        if (this.index != null) {
            switch (this.subject.kind()) {
                case BLOCK -> {
                    blockId = this.subject.id();
                    String item = this.index.block(this.subject.id()).map(PackCatalog.BlockEntry::item).orElse("");
                    if (!item.isEmpty()) itemModel = this.icons.itemIcon(item).model();
                }
                case ITEM -> itemModel = this.icons.itemIcon(this.subject.id()).model();
                case ENTITY_TYPE -> {
                }
            }
        }
        if (blockId.isEmpty() && itemModel.isEmpty()) {
            this.appearance = null;
            showExtras();
            return;
        }
        this.icons.appearance(blockId, itemModel).thenAccept(found -> SwingUtilities.invokeLater(() -> {
            if (this.disposed || current != this.appearanceGeneration) return;
            this.appearance = found.orElse(null);
            showExtras();
        }));
    }

    /** Data files of the owning mod named like the definition, such as its loot table and recipes. */
    private void loadResources() {
        long current = ++this.generation;
        Optional<ModSummary> owner = ModSummary.resolve(this.subject.namespace(), this.index, this.sources.get());
        String path = this.subject.id().substring(this.subject.id().indexOf(':') + 1);
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (owner.isEmpty() || owner.get().files().isEmpty()) {
            this.matched = List.of();
            this.owned = List.of();
            showExtras();
            return;
        }
        CompletableFuture<List<ModResources.Resource>> loading = CompletableFuture.supplyAsync(() -> {
            try {
                return ModResources.list(owner.get().files());
            } catch (IOException exception) {
                throw new IllegalStateException(exception.getMessage(), exception);
            }
        });
        this.resourceLoad = loading;
        loading.whenComplete((list, failure) -> SwingUtilities.invokeLater(() -> {
            if (this.disposed || current != this.generation) return;
            this.owned = failure == null ? list : List.of();
            this.matched = matching(this.owned, this.subject.namespace(), name);
            showExtras();
        }));
    }

    /** Shows the Appearance and Files sections from what has loaded so far. */
    private void showExtras() {
        this.extras.removeAll();
        if (this.appearance != null && !this.appearance.textures().isEmpty()) {
            this.extras.add(new PageSection("Appearance", appearanceBody(this.appearance)));
        }
        List<FileLink> files = files();
        if (!files.isEmpty()) this.extras.add(new PageSection("Files", filesBody(files)));
        this.extras.add(Box.createVerticalGlue());
        this.extras.revalidate();
        this.extras.repaint();
    }

    private JComponent appearanceBody(ModelAppearance appearance) {
        JPanel body = new JPanel(new BorderLayout(0, 6));
        JPanel grid = new JPanel(new GridLayout(0, Math.min(6, appearance.textures().size()), 8, 8));
        int size = UiMetrics.previewPixels(UiMetrics.THUMBNAIL_SIZE);
        for (ModelAppearance.Texture texture : appearance.textures()) {
            Icon preview = texture.image() == null ? new CenteredIcon(Icons.IMAGE_FILE, size)
                    : new ImageIcon(PixelImages.fit(texture.image(), size));
            JLabel tile = new JLabel(String.join(", ", texture.variables()), preview, SwingConstants.CENTER);
            tile.setVerticalTextPosition(SwingConstants.BOTTOM);
            tile.setHorizontalTextPosition(SwingConstants.CENTER);
            ThemeColors.keepForeground(tile, ThemeColors::secondaryText);
            tile.setToolTipText(texture.id());
            tile.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            tile.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent event) {
                    if (SwingUtilities.isLeftMouseButton(event)) {
                        DefinitionPanel.this.navigator.accept(target(texture.resourcePath(), texture.root()));
                    }
                }
            });
            grid.add(tile);
        }
        JPanel wrapper = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        wrapper.add(grid);
        body.add(wrapper, BorderLayout.CENTER);
        if (!appearance.loaders().isEmpty()) {
            JLabel loaders = new JLabel("Custom model loader " + String.join(", ", appearance.loaders())
                    + "; it may draw textures that are not listed");
            ThemeColors.keepForeground(loaders, ThemeColors::secondaryText);
            body.add(loaders, BorderLayout.SOUTH);
        }
        return body;
    }

    /** One file of the definition: its role, such as Blockstate or Loot table, and where it opens. */
    record FileLink(String role, String path, NavigationTarget target) {
    }

    /** The blockstate and models from the snapshot, then the mod's data files named like the definition. */
    List<FileLink> files() {
        List<FileLink> links = new ArrayList<>();
        Set<String> listed = new HashSet<>();
        if (this.appearance != null) {
            for (ModelAppearance.File file : this.appearance.files()) {
                links.add(new FileLink(file.role(), shortPath(file.resourcePath()), target(file.resourcePath(), file.root())));
                listed.add(file.resourcePath());
            }
        }
        for (ModResources.Resource resource : this.matched) {
            if (listed.contains(resource.path())) continue;
            boolean data = resource.category().root().equals("data");
            if (!data && this.appearance != null) continue;
            if (resource.category().folder().equals("textures")) continue;
            String role = ResourceBrowser.label(resource.category().key()).replace(" (data)", "");
            links.add(new FileLink(role, shortPath(resource.path()), resource.target()));
        }
        return links;
    }

    private JComponent filesBody(List<FileLink> files) {
        JPanel body = new JPanel(new GridBagLayout());
        for (int row = 0; row < files.size(); row++) {
            FileLink file = files.get(row);
            JLabel role = new JLabel(file.role());
            ThemeColors.keepForeground(role, ThemeColors::secondaryText);
            GridBagConstraints constraints = new GridBagConstraints();
            constraints.gridy = row;
            constraints.anchor = GridBagConstraints.WEST;
            constraints.insets = new Insets(3, 0, 3, 12);
            body.add(role, constraints);
            constraints.gridx = 1;
            constraints.insets = new Insets(3, 0, 3, 0);
            String name = file.path().substring(file.path().lastIndexOf('/') + 1);
            body.add(new LinkLabel(file.path(), FileTypeResolver.resolve(name).icon(), file.path(),
                    () -> this.navigator.accept(file.target())), constraints);
        }
        GridBagConstraints filler = new GridBagConstraints();
        filler.gridx = 2;
        filler.weightx = 1;
        body.add(Box.createHorizontalGlue(), filler);
        return body;
    }

    /** The resource path below its namespace, such as {@code models/block/framed_slab.json}. */
    static String shortPath(String resourcePath) {
        String[] parts = resourcePath.split("/", 3);
        return parts.length == 3 ? parts[2] : resourcePath;
    }

    /** Opens a resource from the owning mod's file when it ships one, otherwise from the resource snapshot. */
    private NavigationTarget target(String resourcePath, ItemRenderResourceRoot root) {
        for (ModResources.Resource resource : this.owned) {
            if (resource.path().equals(resourcePath)) return resource.target();
        }
        return Files.isDirectory(root.path())
                ? new NavigationTarget.LocalFile(root.path().resolve(root.entryPath(resourcePath)))
                : new NavigationTarget.ArchiveEntry(root.path(), root.entryPath(resourcePath));
    }

    static List<ModResources.Resource> matching(List<ModResources.Resource> resources, String namespace, String name) {
        String assets = "assets/" + namespace + "/";
        String data = "data/" + namespace + "/";
        return resources.stream()
                .filter(resource -> resource.path().startsWith(assets) || resource.path().startsWith(data))
                .filter(resource -> resource.stem().equals(name))
                .toList();
    }

    private static JLabel message(String text) {
        JLabel label = new JLabel(text);
        label.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        return label;
    }

    /** The listing of this page's resources that runs or ran last; mod files stay open while it runs. */
    CompletableFuture<?> resourceLoad() {
        return this.resourceLoad;
    }

    public void dispose() {
        this.disposed = true;
        this.removeCatalogListener.run();
        this.removeIconListener.run();
    }
}
