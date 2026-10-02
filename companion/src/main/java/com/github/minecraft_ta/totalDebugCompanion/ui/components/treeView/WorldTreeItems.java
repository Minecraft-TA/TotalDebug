package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView;

import com.github.minecraft_ta.totalDebugCompanion.catalog.WorldReading;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.navigation.WorldTab;
import com.github.minecraft_ta.totalDebugCompanion.ui.Tooltip;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.subject.SubjectIcons;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.DirectoryTreeItem;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.TreeItem;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryText;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The World tree: the current world, which opens its page, with rows for its game rules and datapacks, counted from
 * what the world's owner read ({@link WorldReading}) or, on a server, the datapacks it named; the tree builds the root
 * again when those counts changed.
 */
final class WorldTreeItems {
    static final String ROOT = "world";

    private WorldTreeItems() {
    }

    /** The node name of a tab's row, used to reveal it. */
    static String rowName(WorldTab tab) {
        return tab.name().toLowerCase(Locale.ROOT);
    }

    /**
     * What the World rows show: how many game rules and datapacks the current world has, or on a server, how many
     * datapacks the server named; a count of zero has no row. Another world with the same counts shows the same rows.
     */
    record Rows(int gameRules, int datapacks) {
    }

    /** The World root, with the rows {@code rows} counts; it opens the World page, whose tabs its rows open. */
    static final class Root extends DirectoryTreeItem implements NavigableTreeItem {
        private final Rows rows;

        Root(Rows rows) {
            super(ROOT);
            this.rows = rows;
            setPresentation(PrimarySecondaryText.primary("World"));
            setIcon(Icons.WORLD);
        }

        @Override
        public String getTooltip() {
            return Tooltip.of("The world the game plays, on a server too, or the one played last").html();
        }

        @Override
        public boolean isActivatable() {
            return true;
        }

        @Override
        public NavigationTarget navigationTarget() {
            return new NavigationTarget.World(WorldTab.OVERVIEW);
        }

        @Override
        public List<TreeItem> loadChildren() {
            List<TreeItem> children = new ArrayList<>();
            if (this.rows.gameRules() > 0) children.add(new Tab(WorldTab.GAME_RULES, this.rows.gameRules()));
            if (this.rows.datapacks() > 0) children.add(new Tab(WorldTab.DATAPACKS, this.rows.datapacks()));
            return children;
        }
    }

    /** Game rules or Datapacks with their count; opening it shows that tab of the World page. */
    static final class Tab extends TreeItem implements NavigableTreeItem {
        private final WorldTab tab;

        Tab(WorldTab tab, int count) {
            super(rowName(tab));
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
            return new NavigationTarget.World(this.tab);
        }
    }
}
