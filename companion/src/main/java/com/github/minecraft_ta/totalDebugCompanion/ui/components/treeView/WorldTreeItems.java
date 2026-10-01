package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView;

import com.github.minecraft_ta.totalDebugCompanion.catalog.WorldReading;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CurrentWorld;
import com.github.minecraft_ta.totalDebugCompanion.game.GameLocation;
import com.github.minecraft_ta.totalDebugCompanion.game.GameState;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.navigation.WorldTab;
import com.github.minecraft_ta.totalDebugCompanion.pack.GamePacks;
import com.github.minecraft_ta.totalDebugCompanion.pack.PackResources;
import com.github.minecraft_ta.totalDebugCompanion.ui.Tooltip;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.subject.SubjectIcons;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.DirectoryTreeItem;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.TreeItem;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryText;

import com.github.minecraft_ta.totaldebug.protocol.message.PackStackPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.PlayingPayload;
import java.io.IOException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The World tree: the current world, which opens its page, with rows for its game rules and datapacks, as the world's
 * owner read it ({@link WorldReading}); the tree loads its rows again when that changed.
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
        private final GameLocation location;
        private final WorldReading world;
        private final GamePacks packs;

        Root(GameLocation location, WorldReading world, GamePacks packs) {
            super(ROOT);
            this.location = location;
            this.world = world;
            this.packs = packs;
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
            GameState game = this.location.read();
            Optional<PlayingPayload.Multiplayer> server = game.server();
            if (server.isPresent()) {
                // The server's world, as the server names its datapacks.
                PackStackPayload datapacks = this.packs.datapacks();
                int count = datapacks == null || !server.get().totalDebug() ? 0 : PackResources.serverDatapacks(datapacks).size();
                return count == 0 ? List.of() : List.of(new Tab(WorldTab.DATAPACKS, count));
            }
            // As its owner read it last; the page says why a world could not be read.
            CurrentWorld.Saved saved;
            try {
                saved = this.world.value().saved();
            } catch (IOException unreadable) {
                return List.of();
            }
            if (saved == null) return List.of();
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
