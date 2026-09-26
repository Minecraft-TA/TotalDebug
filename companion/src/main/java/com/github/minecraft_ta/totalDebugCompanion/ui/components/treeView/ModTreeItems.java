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
import com.github.minecraft_ta.totalDebugCompanion.ui.components.subject.ContentKinds;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.subject.SubjectIcons;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.DirectoryTreeItem;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.TreeItem;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryText;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;
import com.github.minecraft_ta.totaldebug.storage.RuntimeInventory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The Modpack tree (see docs/MODPACK.md): the pack's mods and its configuration. Mods has one node per installed mod
 * with logical groups for what it registered, its configuration and its resources. Individual blocks and items are
 * not nodes; a group opens the matching tab of the mod's page.
 */
final class ModTreeItems {
    static final String ROOT = "modpack";
    static final String MODS = "mods";
    static final String CONFIGURATION = "configuration";
    static final String CHANGES = "changes";
    static final String KEY_BINDINGS = "key-bindings";
    static final String CONTENT = "content";
    static final String OTHER_NAMESPACES = "other-namespaces";
    private static final Set<String> PLATFORM = Set.of("minecraft", "neoforge");

    private ModTreeItems() {
    }

    /** The node name of a group under a mod, used to reveal it. */
    static String groupName(ModTab tab) {
        return tab.name().toLowerCase(Locale.ROOT);
    }

    /**
     * What the tree shows now: the captured catalog when ready, otherwise the runtime's modules, and how many changes
     * Companion made that are still in effect.
     */
    record Snapshot(PackCatalogService.State state, RuntimeSourceCatalog sources, int changes) {
        CatalogIndex index() {
            return this.state instanceof PackCatalogService.Ready ready ? ready.index() : null;
        }
    }

    /** The Modpack root; its status tells whether the pack's catalog is captured. */
    static final class Root extends DirectoryTreeItem {
        private final Supplier<Snapshot> snapshot;

        Root(Supplier<Snapshot> snapshot) {
            super(ROOT);
            this.snapshot = snapshot;
            String status = CatalogMessages.status(snapshot.get().state());
            setPresentation(new PrimarySecondaryText("Modpack", status));
            setIcon(Icons.MODPACK);
        }

        @Override
        public String getTooltip() {
            String unavailable = CatalogMessages.unavailable(this.snapshot.get().state());
            return unavailable.isEmpty() ? "The pack's mods and configuration" : unavailable;
        }

        @Override
        public List<TreeItem> loadChildren() {
            return packChildren(this.snapshot.get());
        }
    }

    /**
     * The rows under Modpack: Mods, Content, Configuration and Key bindings once the catalog describes them, and
     * Changes while Companion has changes in effect.
     */
    static List<TreeItem> packChildren(Snapshot snapshot) {
        List<TreeItem> children = new ArrayList<>();
        children.add(new Mods(snapshot));
        if (snapshot.index() != null && !snapshot.index().entries().isEmpty()) children.add(new Content(null, snapshot.index().content()));
        if (snapshot.index() != null) children.add(new Configuration());
        if (snapshot.index() != null && !snapshot.index().catalog().keyBindings().isEmpty()) {
            children.add(new KeyBindings(snapshot.index().catalog().keyBindings().size()));
        }
        if (snapshot.changes() > 0) children.add(new Changes(snapshot.changes()));
        return children;
    }

    /** The installed mods, and the namespaces registered without a mod. */
    static final class Mods extends DirectoryTreeItem {
        private final Snapshot snapshot;

        Mods(Snapshot snapshot) {
            super(MODS);
            this.snapshot = snapshot;
            CatalogIndex index = snapshot.index();
            setPresentation(new PrimarySecondaryText("Mods",
                    index == null ? "" : NumberFormat.getIntegerInstance(Locale.ROOT).format(index.mods().size())));
            setIcon(Icons.MOD);
            setSortPriority(0);
        }

        @Override
        public String getTooltip() {
            return "Installed mods";
        }

        @Override
        public List<TreeItem> loadChildren() {
            return children(this.snapshot);
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
        private final int resourceCount;

        Mod(ModSummary summary, CatalogIndex index) {
            super(summary.id());
            this.summary = summary;
            this.index = index;
            this.resourceCount = resourceCount(summary);
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

        /** A mod with nothing registered, no configuration and no file of its own has no arrow to expand. */
        @Override
        protected boolean isInitiallyEmpty() {
            return loadChildren().isEmpty();
        }

        @Override
        public List<TreeItem> loadChildren() {
            List<TreeItem> children = new ArrayList<>();
            if (this.index != null && this.summary.captured()) {
                Map<String, List<CatalogIndex.Entry>> content = this.index.content(this.summary.id());
                if (!content.isEmpty()) children.add(new Content(this.summary.id(), content));
                group(children, ModTab.CONFIGURATION, this.summary.mod() == null ? 0 : this.summary.mod().configs().size());
                group(children, ModTab.KEY_BINDINGS, this.index.keyBindings(this.summary.id()).size());
            }
            if (this.resourceCount != 0) children.add(new Resources(this.summary, this.resourceCount));
            return children;
        }

        /**
         * How many files the mod keeps under {@code assets/} and {@code data/}; a mod of code alone has none. Mod rows
         * are created while the tree loads in the background, so the listing, which is cached for the mod page, does
         * not block the window. An unreadable file answers -1: Resources stays, and its page says why.
         */
        private static int resourceCount(ModSummary summary) {
            if (summary.files().isEmpty()) return 0;
            try {
                return ModResources.list(summary.files()).size();
            } catch (IOException | UncheckedIOException unreadable) {
                return -1;
            }
        }

        private void group(List<TreeItem> children, ModTab tab, int count) {
            if (count > 0) children.add(new Group(this.summary.id(), tab, count));
        }
    }

    /** Configuration or Key bindings of one mod. */
    static final class Group extends TreeItem implements NavigableTreeItem {
        private final String modId;
        private final ModTab tab;

        Group(String modId, ModTab tab, int count) {
            super(groupName(tab));
            this.modId = modId;
            this.tab = tab;
            setPresentation(new PrimarySecondaryText(tab.title(), NumberFormat.getIntegerInstance(Locale.ROOT).format(count)));
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

    /** Opens the settings of every mod, those that differ from their default first. */
    static final class Configuration extends TreeItem implements NavigableTreeItem {
        Configuration() {
            super(CONFIGURATION);
            setPresentation(PrimarySecondaryText.primary("Configuration"));
            setIcon(Icons.CONFIG_FILE);
            setSortPriority(2);
        }

        @Override
        public String getTooltip() {
            return "Settings of every mod";
        }

        @Override
        public NavigationTarget navigationTarget() {
            return new NavigationTarget.PackConfiguration();
        }
    }

    /**
     * Registered content by kind, as one row per registry: the pack's, or one mod's when {@code modId} is not null.
     * Each row is named by its registry id, so a kind is revealed by its registry.
     */
    static final class Content extends DirectoryTreeItem {
        private final String modId;
        private final Map<String, List<CatalogIndex.Entry>> content;

        Content(String modId, Map<String, List<CatalogIndex.Entry>> content) {
            super(CONTENT);
            this.modId = modId;
            this.content = content;
            setPresentation(PrimarySecondaryText.primary("Content"));
            setIcon(SubjectIcons.tab(ModTab.CONTENT));
            setSortPriority(modId == null ? 1 : ModTab.CONTENT.ordinal());
        }

        @Override
        public String getTooltip() {
            return this.modId == null ? "Registered content of every mod" : "Registered content";
        }

        @Override
        public List<TreeItem> loadChildren() {
            List<TreeItem> children = new ArrayList<>();
            int position = 0;
            for (Map.Entry<String, List<CatalogIndex.Entry>> kind : this.content.entrySet()) {
                children.add(new ContentList(this.modId, kind.getKey(), kind.getValue().size(), position++));
            }
            return children;
        }
    }

    /** Opens one kind of content: the pack's, or one mod's when {@code modId} is not null. */
    static final class ContentList extends TreeItem implements NavigableTreeItem {
        private final String modId;
        private final String registry;

        ContentList(String modId, String registry, int count, int position) {
            super(registry);
            this.modId = modId;
            this.registry = registry;
            ContentKinds.ContentKind kind = ContentKinds.of(registry);
            setPresentation(new PrimarySecondaryText(kind.plural(), NumberFormat.getIntegerInstance(Locale.ROOT).format(count)));
            setIcon(kind.icon());
            setSortPriority(position);
        }

        @Override
        public String getTooltip() {
            String plural = ContentKinds.of(this.registry).plural();
            return this.modId == null ? plural + " of every mod" : plural;
        }

        @Override
        public NavigationTarget navigationTarget() {
            return this.modId == null ? new NavigationTarget.Content(this.registry)
                    : new NavigationTarget.ModPage(this.modId, ModTab.CONTENT, this.registry);
        }
    }

    /** Opens the key bindings of every mod. */
    static final class KeyBindings extends TreeItem implements NavigableTreeItem {
        KeyBindings(int count) {
            super(KEY_BINDINGS);
            setPresentation(new PrimarySecondaryText("Key bindings", NumberFormat.getIntegerInstance(Locale.ROOT).format(count)));
            setIcon(Icons.KEYBOARD);
            setSortPriority(3);
        }

        @Override
        public String getTooltip() {
            return "Key bindings of every mod";
        }

        @Override
        public NavigationTarget navigationTarget() {
            return new NavigationTarget.KeyBindings("");
        }
    }

    /** Opens what Companion changed in the pack. */
    static final class Changes extends TreeItem implements NavigableTreeItem {
        Changes(int count) {
            super(CHANGES);
            setPresentation(new PrimarySecondaryText("Changes", NumberFormat.getIntegerInstance(Locale.ROOT).format(count)));
            setIcon(Icons.CHANGES);
            setSortPriority(4);
        }

        @Override
        public String getTooltip() {
            return "What Companion changed in the pack";
        }

        @Override
        public NavigationTarget navigationTarget() {
            return new NavigationTarget.Changes();
        }
    }

    /** A mod's resources with their count; opening it shows the Resources tab, which lists them by category. */
    static final class Resources extends TreeItem implements NavigableTreeItem {
        private final ModSummary summary;

        Resources(ModSummary summary, int count) {
            super(groupName(ModTab.RESOURCES));
            this.summary = summary;
            setPresentation(new PrimarySecondaryText("Resources",
                    count < 0 ? "" : NumberFormat.getIntegerInstance(Locale.ROOT).format(count)));
            setIcon(Icons.RESOURCES_ROOT);
            setSortPriority(ModTab.RESOURCES.ordinal());
        }

        @Override
        public String getTooltip() {
            return "Resources";
        }

        @Override
        public NavigationTarget navigationTarget() {
            return new NavigationTarget.ModPage(this.summary.id(), ModTab.RESOURCES, "");
        }
    }
}
