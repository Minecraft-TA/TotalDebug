package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.ui.components.PageLoader;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogIndex;
import com.github.minecraft_ta.totalDebugCompanion.catalog.PackCatalogService;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.pack.PackResources;
import com.github.minecraft_ta.totalDebugCompanion.pack.ResourceEdits;
import com.github.minecraft_ta.totaldebug.protocol.message.PackStackPayload;

import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * Every resource of the pack as the game uses it: each file once, with the pack its copy comes from and, in its
 * tooltip, the lower packs it hides. Read again when the catalog or the game's packs change.
 */
public final class PackResourcesPanel extends JPanel {
    private final PackCatalogService catalog;
    private final ResourceEdits edits;
    private final Path workspace;
    private final ResourceBrowser browser;
    private final PageLoader<ResourceBrowser.Prepared> loader;
    /** The kind of resource shown, such as {@code assets/textures}; empty for all. */
    private String category = "";

    public PackResourcesPanel(PackCatalogService catalog, ResourceEdits edits, Path workspace,
                              Consumer<NavigationTarget> navigator) {
        super(new BorderLayout());
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.edits = Objects.requireNonNull(edits, "edits");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.browser = new ResourceBrowser(navigator, category -> this.category = category);
        add(this.browser, BorderLayout.CENTER);
        // Joining every pack is too slow to repeat whenever the page is shown, so only a change in a source reads it again.
        // A save or revert in the managed pack changes which copy wins, once it has enabled the pack.
        this.loader = new PageLoader<>(this::prepareJoin, prepared -> {
            this.browser.setResources(prepared);
            this.browser.setMessage("");
        }, failure -> {
            this.browser.setResources(List.of());
            this.browser.setMessage("Resources could not be read: " + failure.getMessage());
        }).follow(catalog::addListener).follow(edits::addStackListener).follow(edits::addEditListener);
        load();
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
        PackStackPayload stack = this.edits.packStack();
        if (this.browser.rowCount() == 0) this.browser.setMessage("Reading the resources of every pack");
        return () -> {
            PackResources.Joined joined = PackResources.join(PackResources.assets(stack, index, this.workspace),
                    PackResources.data(stack, index));
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

    public void dispose() {
        this.loader.dispose();
        this.browser.dispose();
    }
}
