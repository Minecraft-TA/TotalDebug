package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogIndex;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ModResources;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ModSummary;
import com.github.minecraft_ta.totalDebugCompanion.catalog.PackCatalogService;
import com.github.minecraft_ta.totalDebugCompanion.inspection.ItemIconService;
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

/** A registered block, item or entity type as the captured pack catalog describes it. */
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
    private final JTabbedPane tabs = new JTabbedPane();
    private final JPanel overview = new JPanel(new BorderLayout());
    private final ResourceBrowser resources;
    private CatalogIndex index;
    private CatalogIndex.Entry entry;
    private long generation;
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
        this.resources = new ResourceBrowser(navigator, category -> { });
        add(this.header, BorderLayout.NORTH);
        JScrollPane overviewScroll = new JScrollPane(this.overview);
        overviewScroll.setBorder(BorderFactory.createEmptyBorder());
        this.tabs.addTab("Overview", SubjectIcons.definition(subject.kind()), overviewScroll);
        this.tabs.addTab("Resources", Icons.RESOURCES_ROOT, this.resources);
        add(this.tabs, BorderLayout.CENTER);
        this.removeCatalogListener = catalog.addListener(this::rebuild);
        this.removeIconListener = icons.addListener(this::loadIcon);
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

    /** Resources of the owning mod whose file name is the definition's path, such as its model, textures and loot. */
    private void loadResources() {
        long current = ++this.generation;
        Optional<ModSummary> owner = ModSummary.resolve(this.subject.namespace(), this.index, this.sources.get());
        String path = this.subject.id().substring(this.subject.id().indexOf(':') + 1);
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (owner.isEmpty() || owner.get().files().isEmpty()) {
            this.resources.setResources(List.of());
            this.tabs.setEnabledAt(1, false);
            return;
        }
        CompletableFuture<List<ModResources.Resource>> loading = CompletableFuture.supplyAsync(() -> {
            try {
                return matching(ModResources.list(owner.get().files()), this.subject.namespace(), name);
            } catch (IOException exception) {
                throw new IllegalStateException(exception.getMessage(), exception);
            }
        });
        this.resourceLoad = loading;
        loading.whenComplete((list, failure) -> SwingUtilities.invokeLater(() -> {
            if (this.disposed || current != this.generation) return;
            this.resources.setResources(failure == null ? list : List.of());
            this.resources.setMessage(failure == null ? "" : "Resources could not be read: " + failure.getCause().getMessage());
            this.tabs.setEnabledAt(1, failure != null || !list.isEmpty());
        }));
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

    JTabbedPane tabs() {
        return this.tabs;
    }
}
