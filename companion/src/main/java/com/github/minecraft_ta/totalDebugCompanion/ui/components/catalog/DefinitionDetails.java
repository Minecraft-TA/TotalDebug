package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogIndex;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ModResources;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ModSummary;
import com.github.minecraft_ta.totalDebugCompanion.catalog.PackCatalogService;
import com.github.minecraft_ta.totalDebugCompanion.catalog.RegistryIds;
import com.github.minecraft_ta.totalDebugCompanion.inspection.ItemIconService;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.itemrender.ItemRenderResourceRoot;
import com.github.minecraft_ta.totalDebugCompanion.itemrender.ModelAppearance;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.resource.FileTypeResolver;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeSourceCatalog;
import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.CenteredIcon;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.PixelImages;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.subject.ContentKinds;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.subject.LinkLabel;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import com.github.minecraft_ta.totaldebug.protocol.execution.Fact;
import com.github.minecraft_ta.totaldebug.protocol.execution.FactLink;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * What the captured catalog and the resource snapshot say about one registered block, item or entity type: its title,
 * icon and class, the entries it links to and its further facts, and its Appearance and Files sections, which load in
 * the background. {@code changed} runs on the event thread whenever the catalog changes.
 */
public final class DefinitionDetails {
    /** What the details are read from, and where their links lead. */
    public record Services(PackCatalogService catalog, Supplier<RuntimeSourceCatalog> sources, ItemIconService icons,
                           Consumer<NavigationTarget> navigator) {
        public Services {
            Objects.requireNonNull(catalog, "catalog");
            Objects.requireNonNull(sources, "sources");
            Objects.requireNonNull(icons, "icons");
            Objects.requireNonNull(navigator, "navigator");
        }
    }

    private final SubjectRef.Definition subject;
    private final Services services;
    private final Runnable changed;
    private final Runnable removeCatalogListener;
    private final Runnable removeIconListener;
    private final JPanel extras = new JPanel();
    private PackCatalogService.State state;
    private CatalogIndex index;
    private CatalogIndex.Entry entry;
    private long generation;
    private long appearanceGeneration;
    private ModelAppearance appearance;
    private List<Icon> appearancePreviews = List.of();
    private List<ModResources.Resource> matched = List.of();
    private List<ModResources.Resource> owned = List.of();
    private CompletableFuture<?> resourceLoad = CompletableFuture.completedFuture(null);
    private int labelWidth;
    private boolean disposed;

    public DefinitionDetails(SubjectRef.Definition subject, Services services, Runnable changed) {
        this.subject = Objects.requireNonNull(subject, "subject");
        this.services = Objects.requireNonNull(services, "services");
        this.changed = Objects.requireNonNull(changed, "changed");
        this.extras.setLayout(new BoxLayout(this.extras, BoxLayout.Y_AXIS));
        this.removeCatalogListener = services.catalog().addListener(this::reload);
        this.removeIconListener = services.icons().addListener(this::loadAppearance);
        read();
        loadAppearance();
        loadResources();
    }

    public SubjectRef.Definition subject() {
        return this.subject;
    }

    /** The catalog's title for the definition, or its id when the catalog does not have it. */
    public String title() {
        return this.entry == null ? this.subject.id() : this.entry.title();
    }

    /** Why the catalog cannot describe the definition, or empty when it does. */
    public String unavailable() {
        if (this.entry != null) return "";
        String unavailable = CatalogMessages.unavailable(this.state);
        return unavailable.isEmpty() ? this.subject.id() + " is not in the captured catalog" : unavailable;
    }

    /** The name of the mod owning the definition, or its namespace. */
    public String modName() {
        String namespace = this.subject.namespace();
        return this.index == null ? namespace : this.index.ownerName(namespace);
    }

    /** The item drawn for the definition, when the catalog names one. */
    public Optional<CatalogIndex.ItemIcon> icon() {
        return this.index == null || this.entry == null || this.entry.iconItem().isEmpty()
                ? Optional.empty() : this.index.itemIcon(this.entry.iconItem());
    }

    /** The class the catalog records for the definition, linked to its source. */
    public Optional<Fact> classFact() {
        if (this.index == null) return Optional.empty();
        return this.index.definition(this.subject).map(PackCatalog.RegistryEntry::className)
                .filter(name -> !name.isEmpty())
                .map(name -> Fact.text("Class", name.substring(name.lastIndexOf('.') + 1)).withLink(FactLink.toClass(name)));
    }

    /**
     * The entries the definition links to, each linked to its page when its registry was captured and named by its id
     * otherwise, followed by its further facts named after their keys.
     */
    public List<Fact> related() {
        List<Fact> facts = new ArrayList<>();
        if (this.index == null) return facts;
        this.index.definition(this.subject).ifPresent(definition -> {
            for (PackCatalog.Link link : definition.links()) {
                String label = ContentKinds.label(link.relation());
                SubjectRef.Definition related = new SubjectRef.Definition(link.registry(), link.id());
                facts.add(this.index.entry(related)
                        .map(entry -> Fact.text(label, entry.title()).withLink(FactLink.toSubject(related)))
                        .orElse(Fact.text(label, link.id())));
            }
            new TreeMap<>(definition.facts()).forEach((key, value) -> facts.add(Fact.text(ContentKinds.label(key), value)));
        });
        return facts;
    }

    /** The Appearance and Files sections, kept current as they load. */
    public JComponent extras() {
        return this.extras;
    }

    /** Lines the Files rows up with a label column of {@code width} above them. */
    public void alignLabels(int width) {
        if (width == this.labelWidth) return;
        this.labelWidth = width;
        showExtras();
    }

    private void read() {
        this.state = this.services.catalog().state();
        this.index = this.state instanceof PackCatalogService.Ready ready ? ready.index() : null;
        this.entry = this.index == null ? null : this.index.entry(this.subject).orElse(null);
    }

    private void reload() {
        if (this.disposed) return;
        read();
        loadAppearance();
        loadResources();
        this.changed.run();
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
            switch (this.subject.registry()) {
                case RegistryIds.BLOCK -> {
                    blockId = this.subject.id();
                    String item = this.index.definition(this.subject).map(definition -> definition.link("item")).orElse("");
                    if (!item.isEmpty()) itemModel = this.services.icons().itemIcon(item).model();
                }
                case RegistryIds.ITEM -> itemModel = this.services.icons().itemIcon(this.subject.id()).model();
                default -> {
                }
            }
        }
        if (blockId.isEmpty() && itemModel.isEmpty()) {
            this.appearance = null;
            showExtras();
            return;
        }
        // Textures are scaled to thumbnails off the Swing thread; a texture can be large.
        this.services.icons().appearance(blockId, itemModel).thenAcceptAsync(found -> {
            List<Icon> previews = found.map(DefinitionDetails::previews).orElse(List.of());
            SwingUtilities.invokeLater(() -> {
                if (this.disposed || current != this.appearanceGeneration) return;
                this.appearance = found.orElse(null);
                this.appearancePreviews = previews;
                showExtras();
            });
        });
    }

    /** Data files of the owning mod named like the definition, such as its loot table and recipes. */
    private void loadResources() {
        long current = ++this.generation;
        Optional<ModSummary> owner = ModSummary.resolve(this.subject.namespace(), this.index, this.services.sources().get());
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

    /** A thumbnail for each of the appearance's textures, in order. Not on the Swing thread. */
    private static List<Icon> previews(ModelAppearance appearance) {
        int size = UiMetrics.previewPixels(UiMetrics.THUMBNAIL_SIZE);
        List<Icon> previews = new ArrayList<>();
        for (ModelAppearance.Texture texture : appearance.textures()) {
            previews.add(texture.image() == null ? new CenteredIcon(Icons.IMAGE_FILE, size)
                    : new ImageIcon(PixelImages.fit(texture.image(), size)));
        }
        return previews;
    }

    /** Shows the Appearance and Files sections from what has loaded so far. */
    private void showExtras() {
        this.extras.removeAll();
        if (this.appearance != null && !this.appearance.textures().isEmpty()) {
            this.extras.add(aligned(new PageSection("Appearance", appearanceBody(this.appearance))));
        }
        List<FileLink> files = files();
        if (!files.isEmpty()) this.extras.add(aligned(new PageSection("Files", filesBody(files))));
        this.extras.add(Box.createVerticalGlue());
        this.extras.revalidate();
        this.extras.repaint();
    }

    private JComponent appearanceBody(ModelAppearance appearance) {
        JPanel body = new JPanel(new BorderLayout(0, 6));
        JPanel grid = new JPanel(new GridLayout(0, Math.min(6, appearance.textures().size()), 8, 8));
        for (int index = 0; index < appearance.textures().size(); index++) {
            ModelAppearance.Texture texture = appearance.textures().get(index);
            Icon preview = this.appearancePreviews.get(index);
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
                        DefinitionDetails.this.services.navigator().accept(target(texture.resourcePath(), texture.root()));
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
        List<PageSection.LinkRow> rows = new ArrayList<>();
        for (FileLink file : files) {
            String name = file.path().substring(file.path().lastIndexOf('/') + 1);
            rows.add(new PageSection.LinkRow(file.role(), List.of(new LinkLabel(file.path(),
                    FileTypeResolver.resolve(name).icon(), file.path(), () -> this.services.navigator().accept(file.target())))));
        }
        return PageSection.linkRows(rows, this.labelWidth);
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

    private static <T extends JComponent> T aligned(T component) {
        component.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        return component;
    }

    /** The listing of the definition's resources that runs or ran last; mod files stay open while it runs. */
    public CompletableFuture<?> resourceLoad() {
        return this.resourceLoad;
    }

    public void dispose() {
        this.disposed = true;
        this.removeCatalogListener.run();
        this.removeIconListener.run();
    }
}
