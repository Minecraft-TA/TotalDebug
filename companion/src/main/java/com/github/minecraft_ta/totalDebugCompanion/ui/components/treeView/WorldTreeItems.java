package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CurrentWorld;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.navigation.WorldTab;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.subject.SubjectIcons;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.DirectoryTreeItem;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.TreeItem;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryText;

import java.io.IOException;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The World tree: the current world, which opens its page, with rows for its game rules and datapacks. The world is
 * read when the tree loads its rows, in the background.
 */
final class WorldTreeItems {
    static final String ROOT = "world";

    private WorldTreeItems() {
    }

    /** The node name of a tab's row, used to reveal it. */
    static String rowName(WorldTab tab) {
        return tab.name().toLowerCase(Locale.ROOT);
    }

    static final class Root extends DirectoryTreeItem implements NavigableTreeItem {
        private final Path workspace;

        Root(Path workspace) {
            super(ROOT);
            this.workspace = workspace;
            setPresentation(PrimarySecondaryText.primary("World"));
            setIcon(Icons.WORLD);
        }

        @Override
        public String getTooltip() {
            return "The world the game has open, or the one played last";
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
            Optional<Path> world = CurrentWorld.directory(this.workspace);
            if (world.isEmpty()) return List.of();
            CurrentWorld.Saved saved;
            try {
                saved = CurrentWorld.read(world.get());
            } catch (IOException | RuntimeException unreadable) {
                // The page says why the world could not be read.
                return List.of();
            }
            List<TreeItem> children = new ArrayList<>();
            if (!saved.gameRules().isEmpty()) children.add(new Tab(WorldTab.GAME_RULES, saved.gameRules().size()));
            if (!saved.datapacks().isEmpty()) children.add(new Tab(WorldTab.DATAPACKS, saved.datapacks().size()));
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
