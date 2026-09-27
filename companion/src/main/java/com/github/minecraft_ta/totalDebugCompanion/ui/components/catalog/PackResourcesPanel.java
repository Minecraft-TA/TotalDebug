package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogIndex;
import com.github.minecraft_ta.totalDebugCompanion.catalog.PackCatalogService;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.pack.PackResources;
import com.github.minecraft_ta.totalDebugCompanion.pack.ResourceEdits;
import com.github.minecraft_ta.totaldebug.protocol.message.PackStackPayload;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
    private final Runnable removeCatalogListener;
    private final Runnable removeStackListener;
    private final Runnable removeRecordListener;
    /** The kind of resource shown, such as {@code assets/textures}; empty for all. */
    private String category = "";
    private long generation;
    private boolean disposed;

    public PackResourcesPanel(PackCatalogService catalog, ResourceEdits edits, Path workspace,
                              Consumer<NavigationTarget> navigator) {
        super(new BorderLayout());
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.edits = Objects.requireNonNull(edits, "edits");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.browser = new ResourceBrowser(navigator, category -> this.category = category);
        add(this.browser, BorderLayout.CENTER);
        this.removeCatalogListener = catalog.addListener(() -> SwingUtilities.invokeLater(this::load));
        this.removeStackListener = edits.addStackListener(() -> SwingUtilities.invokeLater(this::load));
        // A save or revert in the managed pack changes which copy wins.
        this.removeRecordListener = edits.record().addListener(() -> SwingUtilities.invokeLater(this::load));
        load();
    }

    /** Joins the packs' resources again. */
    public void load() {
        if (this.disposed) return;
        long current = ++this.generation;
        CatalogIndex index = this.catalog.index().orElse(null);
        if (index == null) {
            this.browser.setResources(List.of());
            this.browser.setMessage("The pack catalog is not captured yet.");
            return;
        }
        PackStackPayload stack = this.edits.packStack();
        if (this.browser.rowCount() == 0) this.browser.setMessage("Reading the resources of every pack");
        CompletableFuture.supplyAsync(() -> {
            try {
                PackResources.Joined joined = PackResources.join(PackResources.assets(stack, index, this.workspace),
                        PackResources.data(stack, index));
                return ResourceBrowser.prepare(joined.resources(), joined.from(), joined.hidden());
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }).whenComplete((prepared, failure) -> SwingUtilities.invokeLater(() -> {
            if (this.disposed || current != this.generation) return;
            if (failure != null) {
                Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                this.browser.setResources(List.of());
                this.browser.setMessage("Resources could not be read: " + cause.getMessage());
                return;
            }
            this.browser.setResources(prepared);
            this.browser.setMessage("");
        }));
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
        this.disposed = true;
        this.removeCatalogListener.run();
        this.removeStackListener.run();
        this.removeRecordListener.run();
        this.browser.dispose();
    }
}
