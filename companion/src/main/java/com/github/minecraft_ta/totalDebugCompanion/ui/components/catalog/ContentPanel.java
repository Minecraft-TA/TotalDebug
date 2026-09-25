package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogIndex;
import com.github.minecraft_ta.totalDebugCompanion.catalog.PackCatalogService;
import com.github.minecraft_ta.totalDebugCompanion.inspection.ItemIconService;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import java.awt.BorderLayout;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Everything the pack registered, by kind, with the mod that registered each entry, in the browser a mod's Content
 * tab lists its own in. The lists follow the catalog whenever it is captured again.
 */
public final class ContentPanel extends JPanel {
    private static final int LIST_ICON_SIZE = UiMetrics.previewPixels(UiMetrics.ITEM_ICON_SIZE);

    private final PackCatalogService catalog;
    private final CatalogIcons icons;
    private final ContentBrowser browser;
    private final JLabel message = new JLabel();
    private final Runnable removeCatalogListener;
    private CatalogIndex index;

    public ContentPanel(PackCatalogService catalog, ItemIconService icons, Consumer<NavigationTarget> navigator) {
        super(new BorderLayout());
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.icons = new CatalogIcons(icons, LIST_ICON_SIZE);
        this.browser = new ContentBrowser(this.icons, this::iconOf, Objects.requireNonNull(navigator, "navigator"),
                entry -> this.index == null ? entry.namespace() : this.index.ownerName(entry.namespace()));
        this.message.setVerticalAlignment(JLabel.TOP);
        this.removeCatalogListener = catalog.addListener(() -> SwingUtilities.invokeLater(this::load));
        load();
    }

    /** Lists the catalog's entries again. */
    public void load() {
        this.index = this.catalog.index().orElse(null);
        removeAll();
        if (this.index == null) {
            this.message.setText("The pack catalog is not captured yet.");
            add(this.message, BorderLayout.CENTER);
        } else {
            this.browser.setContent(this.index.content());
            add(this.browser, BorderLayout.CENTER);
        }
        revalidate();
        repaint();
    }

    /** Selects a kind by its registry, such as {@code minecraft:fluid}, or All for an empty one. */
    public void show(String registry) {
        this.browser.select(registry);
    }

    /** The registry of the selected kind, or empty for All. */
    public String selectedKind() {
        return this.browser.selectedKind();
    }

    private CatalogIndex.ItemIcon iconOf(CatalogIndex.Entry entry) {
        return this.index == null || entry.iconItem().isEmpty() ? null : this.index.itemIcon(entry.iconItem()).orElse(null);
    }

    /** The field that filters the listed entries. */
    public JTextComponent filterField() {
        return this.browser.filterField();
    }

    public void dispose() {
        this.removeCatalogListener.run();
        this.icons.dispose();
    }
}
