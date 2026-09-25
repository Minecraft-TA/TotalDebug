package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogIndex;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ModResources;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ModSummary;
import com.github.minecraft_ta.totalDebugCompanion.catalog.PackCatalogService;
import com.github.minecraft_ta.totalDebugCompanion.navigation.ModTab;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeSourceCatalog;
import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog.CatalogMessages;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog.ModLogoIcons;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.subject.SubjectIcons;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.DirectoryTreeItem;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.TreeItem;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryText;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;
import com.github.minecraft_ta.totaldebug.storage.RuntimeInventory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The Mods tree: one node per installed mod with logical groups for what it registered, its configuration and its
 * resources. Individual blocks and items are not nodes; a group opens the matching tab of the mod's page.
 */
final class ModTreeItems {
    static final String ROOT = "mods";
    static final String OTHER_NAMESPACES = "other-namespaces";
    private static final Set<String> PLATFORM = Set.of("minecraft", "neoforge");

    private ModTreeItems() {
    }

    /** The node name of a group under a mod, used to reveal it. */
    static String groupName(ModTab tab) {
        return tab.name().toLowerCase(Locale.ROOT);
    }

    /** What the tree shows now: the captured catalog when ready, otherwise the runtime's modules. */
    record Snapshot(PackCatalogService.State state, RuntimeSourceCatalog sources) {
        CatalogIndex index() {
            return this.state instanceof PackCatalogService.Ready ready ? ready.index() : null;
        }
    }

    static final class Root extends DirectoryTreeItem {
        private final Supplier<Snapshot> snapshot;

        Root(Supplier<Snapshot> snapshot) {
            super(ROOT);
            this.snapshot = snapshot;
            String status = CatalogMessages.status(snapshot.get().state());
            setPresentation(new PrimarySecondaryText("Mods", status));
            setIcon(Icons.MOD);
        }

        @Override
        public String getTooltip() {
            String unavailable = CatalogMessages.unavailable(this.snapshot.get().state());
            return unavailable.isEmpty() ? "Installed mods" : unavailable;
        }

        @Override
        public List<TreeItem> loadChildren() {
            return children(this.snapshot.get());
        }
    }

    static List<TreeItem> children(Snapshot snapshot) {
        List<TreeItem> children = new ArrayList<>();
        CatalogIndex index = snapshot.index();
        if (index != null) {
            for (PackCatalog.Mod mod : index.mods()) {
                ModSummary.resolve(mod.id(), index, snapshot.sources())
                        .ifPresent(summary -> children.add(new Mod(summary, index)));
            }
            if (!index.otherNamespaces().isEmpty()) children.add(new OtherNamespaces(index, snapshot.sources()));
            return children;
        }
        for (RuntimeInventory.RuntimeModule module : snapshot.sources().modules()) {
            if (module.kind() != RuntimeInventory.ModuleKind.MOD && module.kind() != RuntimeInventory.ModuleKind.PLATFORM) continue;
            ModSummary.resolve(module.id(), null, snapshot.sources()).ifPresent(summary -> children.add(new Mod(summary, null)));
        }
        return children;
    }

    static final class OtherNamespaces extends DirectoryTreeItem {
        private final CatalogIndex index;
        private final RuntimeSourceCatalog sources;

        OtherNamespaces(CatalogIndex index, RuntimeSourceCatalog sources) {
            super(OTHER_NAMESPACES);
            this.index = index;
            this.sources = sources;
            setPresentation(PrimarySecondaryText.primary("Other namespaces"));
            setIcon(Icons.FOLDER);
            setSortPriority(20);
        }

        @Override
        public String getTooltip() {
            return "Registered content in namespaces without a mod of the same id";
        }

        @Override
        public List<TreeItem> loadChildren() {
            List<TreeItem> children = new ArrayList<>();
            for (String namespace : this.index.otherNamespaces()) {
                ModSummary.resolve(namespace, this.index, this.sources)
                        .ifPresent(summary -> children.add(new Mod(summary, this.index)));
            }
            return children;
        }
    }

    /** A mod, namespace or runtime module; activating it opens its page. */
    static final class Mod extends DirectoryTreeItem implements NavigableTreeItem {
        private final ModSummary summary;
        private final CatalogIndex index;

        Mod(ModSummary summary, CatalogIndex index) {
            super(summary.id());
            this.summary = summary;
            this.index = index;
            setPresentation(PrimarySecondaryText.primary(summary.title()));
            setIcon(ModLogoIcons.icon(summary, UiMetrics.previewPixels(UiMetrics.ROW_ICON_SIZE)));
            setSortPriority(PLATFORM.contains(summary.id()) ? 0 : 10);
        }

        @Override
        public String getTooltip() {
            return this.summary.id() + (this.summary.version().isEmpty() ? "" : " " + this.summary.version());
        }

        @Override
        public boolean isActivatable() {
            return true;
        }

        @Override
        public NavigationTarget navigationTarget() {
            return new NavigationTarget.ModPage(this.summary.id());
        }

        @Override
        public List<TreeItem> loadChildren() {
            List<TreeItem> children = new ArrayList<>();
            if (this.index != null && this.summary.captured()) {
                group(children, ModTab.BLOCKS, this.index.entries(this.summary.id(), SubjectRef.DefinitionKind.BLOCK).size());
                group(children, ModTab.ITEMS, this.index.entries(this.summary.id(), SubjectRef.DefinitionKind.ITEM).size());
                group(children, ModTab.ENTITIES, this.index.entries(this.summary.id(), SubjectRef.DefinitionKind.ENTITY_TYPE).size());
                group(children, ModTab.CONFIGURATION, this.summary.mod() == null ? 0 : this.summary.mod().configs().size());
            }
            if (!this.summary.files().isEmpty()) children.add(new Resources(this.summary));
            return children;
        }

        private void group(List<TreeItem> children, ModTab tab, int count) {
            if (count > 0) children.add(new Group(this.summary.id(), tab, count));
        }
    }

    /** Blocks, Items, Entity types or Configuration of one mod. */
    static final class Group extends TreeItem implements NavigableTreeItem {
        private final String modId;
        private final ModTab tab;

        Group(String modId, ModTab tab, int count) {
            super(groupName(tab));
            this.modId = modId;
            this.tab = tab;
            String title = tab == ModTab.ENTITIES ? "Entity types" : tab.title();
            setPresentation(new PrimarySecondaryText(title, NumberFormat.getIntegerInstance(Locale.ROOT).format(count)));
            setIcon(SubjectIcons.tab(tab));
            setSortPriority(tab.ordinal());
        }

        @Override
        public String getTooltip() {
            return getPresentation().primary();
        }

        @Override
        public NavigationTarget navigationTarget() {
            return new NavigationTarget.ModPage(this.modId, this.tab, "");
        }
    }

    /** A mod's resources, expanding into their categories. */
    static final class Resources extends DirectoryTreeItem implements NavigableTreeItem {
        private final ModSummary summary;

        Resources(ModSummary summary) {
            super(groupName(ModTab.RESOURCES));
            this.summary = summary;
            setPresentation(PrimarySecondaryText.primary("Resources"));
            setIcon(Icons.RESOURCES_ROOT);
            setSortPriority(ModTab.RESOURCES.ordinal());
        }

        @Override
        public String getTooltip() {
            return "Resources";
        }

        @Override
        public boolean isActivatable() {
            return true;
        }

        @Override
        public NavigationTarget navigationTarget() {
            return new NavigationTarget.ModPage(this.summary.id(), ModTab.RESOURCES, "");
        }

        @Override
        public List<TreeItem> loadChildren() {
            List<ModResources.Resource> resources;
            try {
                resources = ModResources.list(this.summary.files());
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            List<TreeItem> categories = new ArrayList<>();
            for (ModResources.Category category : ModResources.categories(resources)) {
                long count = resources.stream().filter(resource -> resource.category().equals(category)).count();
                categories.add(new ResourceCategory(this.summary.id(), category, count));
            }
            return categories;
        }
    }

    static final class ResourceCategory extends TreeItem implements NavigableTreeItem {
        private final String modId;
        private final ModResources.Category category;

        ResourceCategory(String modId, ModResources.Category category, long count) {
            super(category.key());
            this.modId = modId;
            this.category = category;
            String title = category.root().equals("data") ? category.folder() + " (data)" : category.folder();
            setPresentation(new PrimarySecondaryText(title, NumberFormat.getIntegerInstance(Locale.ROOT).format(count)));
            setIcon(Icons.FOLDER);
            setSortPriority(category.root().equals("assets") ? 0 : 1);
        }

        @Override
        public String getTooltip() {
            return this.category.key();
        }

        @Override
        public NavigationTarget navigationTarget() {
            return new NavigationTarget.ModPage(this.modId, ModTab.RESOURCES, this.category.key());
        }
    }
}
