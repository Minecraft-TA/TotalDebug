package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.TypeToFilter;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.TabTitles;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.PageLoader;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ListedPack;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogIndex;
import com.github.minecraft_ta.totalDebugCompanion.catalog.PackCatalogService;
import com.github.minecraft_ta.totalDebugCompanion.navigation.ResourcesTab;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.pack.PackResources;
import com.github.minecraft_ta.totalDebugCompanion.pack.ResourceEdits;
import com.github.minecraft_ta.totalDebugCompanion.pack.PackSelections;
import com.github.minecraft_ta.totaldebug.protocol.message.PackStackPayload;

import javax.swing.JTabbedPane;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * Every resource of the pack as the game uses it, on the Files tab: each file once, with the pack its copy comes from
 * and, in its tooltip, the lower packs it hides. The Packs tab lists the resource packs as the game's pack screen does.
 * Files are joined again when the catalog or the game's packs change; packs are listed again whenever they are shown.
 */
public final class PackResourcesPanel extends JPanel {
    private final PackCatalogService catalog;
    private final ResourceEdits edits;
    private final Path workspace;
    private final ResourceBrowser browser;
    private final PacksPanel packs;
    private final JTabbedPane tabs = new JTabbedPane();
    private final PageLoader<ResourceBrowser.Prepared> loader;
    private final PageLoader<List<ListedPack>> packLoader;
    /** The kind of resource shown, such as {@code assets/textures}; empty for all. */
    private String category = "";

    public PackResourcesPanel(PackCatalogService catalog, ResourceEdits edits, PackSelections selections, Path workspace,
                              Consumer<NavigationTarget> navigator) {
        super(new BorderLayout());
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.edits = Objects.requireNonNull(edits, "edits");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.browser = new ResourceBrowser(navigator, category -> this.category = category);
        this.packs = new PacksPanel(PacksPanel.Side.RESOURCES, navigator);
        this.packs.setApplier(enabled -> selections.set(ChangeRecord.PackSide.RESOURCES, null, enabled),
                "Enables the checked resource packs in this order: in the connected game, which reloads its resources, otherwise in options.txt");
        this.tabs.addTab(ResourcesTab.FILES.title(), Icons.FOLDER, this.browser);
        this.tabs.addTab(ResourcesTab.PACKS.title(), Icons.RESOURCES_ROOT, this.packs);
        TypeToFilter.forwardTyping(this.tabs, () -> this.tabs.getSelectedComponent() == this.packs
                ? this.packs.filterField() : this.browser.filterField());
        add(this.tabs, BorderLayout.CENTER);
        // Joining every pack is too slow to repeat whenever the page is shown, so only a change in a source reads it again,
        // once the page is shown. A save or revert in the managed pack changes which copy wins, once it has enabled the pack.
        this.loader = new PageLoader<>(this::prepareJoin, prepared -> {
            this.browser.setResources(prepared);
            this.browser.setMessage("");
            TabTitles.setCounted(this.tabs, 0, ResourcesTab.FILES.title(), prepared.resources().size());
        }, failure -> {
            TabTitles.setUncounted(this.tabs, 0, ResourcesTab.FILES.title());
            this.browser.setResources(List.of());
            this.browser.setMessage("Resources could not be read: " + failure.getMessage());
        }).waitsWhileHidden(this).follow(catalog.changed()::subscribe).follow(edits.packs().changed(ChangeRecord.PackSide.RESOURCES)::subscribe)
                .follow(edits.packs().changed(ChangeRecord.PackSide.DATA)::subscribe).follow(edits.edited()::subscribe);
        this.packLoader = new PageLoader<>(() -> {
            PackStackPayload stack = this.edits.packs().resourcePacks();
            return () -> PackResources.resourcePacks(stack, this.workspace);
        }, listed -> {
            this.packs.setPacks(listed, this.catalog.index().orElse(null));
            TabTitles.setCounted(this.tabs, 1, ResourcesTab.PACKS.title(), listed.size());
        }, failure -> {
            TabTitles.setUncounted(this.tabs, 1, ResourcesTab.PACKS.title());
            this.packs.showFailure("The resource packs could not be listed: " + failure.getMessage());
        })
                // Its count on the tab follows while the page is shown; the folders are read again when the tab is chosen.
                .waitsWhileHidden(this).readsWhenShown(this.packs)
                .follow(edits.packs().changed(ChangeRecord.PackSide.RESOURCES)::subscribe).follow(catalog.changed()::subscribe);
        load();
    }

    /** Selects a tab, and on Files a kind of resource. */
    public void show(NavigationTarget.PackResources target) {
        this.tabs.setSelectedComponent(target.tab() == ResourcesTab.PACKS ? this.packs : this.browser);
        if (target.tab() == ResourcesTab.FILES) selectCategory(target.category());
    }

    /** The page as it is shown now, for navigation history. */
    public NavigationTarget.PackResources target() {
        return this.tabs.getSelectedComponent() == this.packs ? new NavigationTarget.PackResources(ResourcesTab.PACKS, "")
                : new NavigationTarget.PackResources(this.category);
    }

    /** Joins the packs' resources again. */
    public void load() {
        this.loader.load();
    }

    /** Joins the packs the game or {@code options.txt} names; without a catalog there is nothing to join. */
    private Callable<ResourceBrowser.Prepared> prepareJoin() {
        CatalogIndex index = this.catalog.index().orElse(null);
        if (index == null) {
            this.browser.setResources(List.of());
            this.browser.setMessage("The pack catalog is not captured yet.");
            return null;
        }
        PackStackPayload resourcePacks = this.edits.packs().resourcePacks();
        PackStackPayload datapacks = this.edits.packs().datapacks();
        if (this.browser.rowCount() == 0) this.browser.setMessage("Reading the resources of every pack");
        return () -> {
            PackResources.Joined joined = PackResources.join(PackResources.assets(resourcePacks, index, this.workspace),
                    PackResources.data(datapacks, index, this.edits.location()));
            return ResourceBrowser.prepare(joined.resources(), joined.from(), joined.hidden());
        };
    }

    public void selectCategory(String key) {
        this.category = key == null ? "" : key;
        this.browser.selectCategory(key);
    }

    /** The kind of resource shown, such as {@code assets/textures}; empty for all. */
    public String category() {
        return this.category;
    }

    ResourceBrowser browser() {
        return this.browser;
    }

    PacksPanel packs() {
        return this.packs;
    }

    public void dispose() {
        this.loader.dispose();
        this.packLoader.dispose();
        this.browser.dispose();
    }
}
