package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogIndex;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.TypeToFilter;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.subject.ContentKinds;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryLabel;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryText;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.DynamicMatteBorder;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;

import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.text.JTextComponent;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Registered content by kind: the kinds with their counts on the left, All first, and the entries of the selected
 * kind in a filterable table. A mod's Content tab and the pack's Content page both show it; the pack's names the mod
 * of each entry. With a single kind the list of kinds is left out.
 */
public final class ContentBrowser extends JPanel {
    private static final String ALL = "";

    /** A row of the list of kinds; {@code registry} is empty for All. */
    private record Kind(String registry, String label, Icon icon, int count) {
    }

    private final DefaultListModel<Kind> kinds = new DefaultListModel<>();
    private final JList<Kind> kindList = new JList<>(this.kinds);
    private final JScrollPane kindScroll = new JScrollPane(this.kindList);
    private final CatalogEntryTable table;
    private final boolean namesMods;
    private Map<String, List<CatalogIndex.Entry>> content = Map.of();
    private String pendingKind = ALL;
    private boolean updating;

    /** {@code modName} names the mod of each entry in its own column, or is null where every entry is one mod's. */
    public ContentBrowser(CatalogIcons icons, Function<CatalogIndex.Entry, CatalogIndex.ItemIcon> iconOf,
                          Consumer<NavigationTarget> navigator, Function<CatalogIndex.Entry, String> modName) {
        super(new BorderLayout());
        this.namesMods = modName != null;
        this.table = new CatalogEntryTable("", icons, iconOf,
                entry -> navigator.accept(new NavigationTarget.Definition(entry.subject())), modName);
        this.kindList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        this.kindList.setCellRenderer((list, kind, index, selected, focused) -> {
            PrimarySecondaryLabel label = new PrimarySecondaryLabel();
            label.configure(new PrimarySecondaryText(kind.label(), NumberFormat.getIntegerInstance(Locale.ROOT).format(kind.count())),
                    kind.icon(), list.getFont(), selected, selected ? list.getSelectionForeground() : ThemeColors.text(),
                    selected ? list.getSelectionBackground() : list.getBackground());
            label.setOpaque(true);
            label.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            label.setBorder(UiMetrics.listRowPadding());
            return label;
        });
        this.kindList.addListSelectionListener(event -> {
            if (event.getValueIsAdjusting() || this.updating) return;
            this.pendingKind = selectedKind();
            showSelected();
        });
        this.kindScroll.setBorder(DynamicMatteBorder.rule(0, 0, 0, 1));
        this.kindScroll.setPreferredSize(new Dimension(190, 0));
        add(this.kindScroll, BorderLayout.WEST);
        add(this.table, BorderLayout.CENTER);
        TypeToFilter.forwardTyping(this.kindList, this::filterField);
    }

    /** Lists {@code content}, by registry in the order given; the selected kind stays selected while it is listed. */
    public void setContent(Map<String, List<CatalogIndex.Entry>> content) {
        this.content = content;
        this.updating = true;
        try {
            this.kinds.clear();
            int total = content.values().stream().mapToInt(List::size).sum();
            this.kinds.addElement(new Kind(ALL, "All", Icons.NONE, total));
            content.forEach((registry, entries) -> {
                ContentKinds.ContentKind kind = ContentKinds.of(registry);
                this.kinds.addElement(new Kind(registry, kind.plural(), kind.icon(), entries.size()));
            });
        } finally {
            this.updating = false;
        }
        this.kindScroll.setVisible(content.size() > 1);
        select(this.pendingKind);
    }

    /** Selects the kind of {@code registry}, such as {@code minecraft:fluid}, or All for an empty or unlisted one. */
    public void select(String registry) {
        this.pendingKind = registry == null ? ALL : registry;
        int position = 0;
        for (int index = 0; index < this.kinds.size(); index++) {
            if (this.kinds.get(index).registry().equals(this.pendingKind)) position = index;
        }
        this.updating = true;
        try {
            if (!this.kinds.isEmpty()) this.kindList.setSelectedIndex(position);
        } finally {
            this.updating = false;
        }
        showSelected();
    }

    /** The registry of the selected kind, or empty for All. */
    public String selectedKind() {
        Kind selected = this.kindList.getSelectedValue();
        return selected == null ? ALL : selected.registry();
    }

    /** How many entries are listed across every kind. */
    public int count() {
        return this.content.values().stream().mapToInt(List::size).sum();
    }

    /** The field that filters the entries, which typing anywhere on the page reaches. */
    public JTextComponent filterField() {
        return this.table.filterField();
    }

    CatalogEntryTable table() {
        return this.table;
    }

    private void showSelected() {
        String registry = selectedKind();
        List<CatalogIndex.Entry> entries;
        String listed;
        if (registry.isEmpty()) {
            entries = new ArrayList<>();
            this.content.values().forEach(entries::addAll);
            listed = "content";
        } else {
            entries = this.content.getOrDefault(registry, List.of());
            listed = ContentKinds.of(registry).plural().toLowerCase(Locale.ROOT);
        }
        this.table.setEntries(entries, registry.isEmpty() && this.content.size() > 1);
        this.table.setPlaceholder("Filter " + listed + (this.namesMods ? " by name, id or mod" : " by name or id"));
    }
}
