package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogIndex;
import com.github.minecraft_ta.totalDebugCompanion.catalog.PackCatalogService;
import com.github.minecraft_ta.totalDebugCompanion.inspection.ItemIconService;
import com.github.minecraft_ta.totalDebugCompanion.navigation.ModTab;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.TypeToFilter;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.subject.SubjectIcons;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import java.awt.BorderLayout;
import java.awt.Component;
import java.text.NumberFormat;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Every block, item and entity type the pack registered, with the mod that registered it, in the same tables a mod's
 * page lists its own in. The lists follow the catalog whenever it is captured again.
 */
public final class ContentPanel extends JPanel {
    private static final int LIST_ICON_SIZE = UiMetrics.previewPixels(UiMetrics.ITEM_ICON_SIZE);

    private final PackCatalogService catalog;
    private final CatalogIcons icons;
    private final JTabbedPane tabs = new JTabbedPane();
    private final Map<ModTab, CatalogEntryTable> tables = new EnumMap<>(ModTab.class);
    private final JLabel message = new JLabel();
    private final Runnable removeCatalogListener;
    private CatalogIndex index;

    public ContentPanel(PackCatalogService catalog, ItemIconService icons, Consumer<NavigationTarget> navigator) {
        super(new BorderLayout());
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.icons = new CatalogIcons(icons, LIST_ICON_SIZE);
        Objects.requireNonNull(navigator, "navigator");
        for (ModTab tab : ModTab.CONTENT) {
            CatalogEntryTable table = new CatalogEntryTable("Filter " + tab.listTitle().toLowerCase(Locale.ROOT) + " by name, id or mod", this.icons, this::iconOf, entry -> navigator.accept(new NavigationTarget.Definition(entry.subject())),
                    entry -> this.index == null ? entry.namespace() : this.index.ownerName(entry.namespace()));
            this.tables.put(tab, table);
            this.tabs.addTab(tab.listTitle(), SubjectIcons.tab(tab), table);
        }
        this.message.setVerticalAlignment(JLabel.TOP);
        TypeToFilter.forwardTyping(this.tabs, this::filterField);
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
            for (int position = 0; position < ModTab.CONTENT.size(); position++) {
                ModTab tab = ModTab.CONTENT.get(position);
                List<CatalogIndex.Entry> entries = this.index.entries().stream()
                        .filter(entry -> entry.kind() == tab.contentKind()).toList();
                this.tables.get(tab).setEntries(entries);
                this.tabs.setTitleAt(position, tab.listTitle() + " "
                        + NumberFormat.getIntegerInstance(Locale.ROOT).format(entries.size()));
            }
            add(this.tabs, BorderLayout.CENTER);
        }
        revalidate();
        repaint();
    }

    /** Selects the Blocks, Items or Entity types tab. */
    public void show(ModTab tab) {
        int position = ModTab.CONTENT.indexOf(tab);
        if (position >= 0) this.tabs.setSelectedIndex(position);
    }

    public ModTab selectedTab() {
        return ModTab.CONTENT.get(Math.max(0, this.tabs.getSelectedIndex()));
    }

    private CatalogIndex.ItemIcon iconOf(CatalogIndex.Entry entry) {
        return this.index == null || entry.iconItem().isEmpty() ? null : this.index.itemIcon(entry.iconItem()).orElse(null);
    }

    /** The field that filters the selected list. */
    public JTextComponent filterField() {
        Component selected = this.tabs.getSelectedComponent();
        return selected instanceof CatalogEntryTable table ? table.filterField() : null;
    }

    public void dispose() {
        this.removeCatalogListener.run();
        this.icons.dispose();
    }
}
