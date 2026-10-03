package com.github.minecraft_ta.totalDebugCompanion.ui.categories.world;

import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.navigation.Page;
import com.github.minecraft_ta.totalDebugCompanion.ui.EditorContext;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.EditorTabs;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** The world the game plays. */
public final class WorldPage implements Page<NavigationTarget.World> {
    @Override
    public Class<NavigationTarget.World> target() {
        return NavigationTarget.World.class;
    }

    @Override
    public CompletableFuture<Void> open(NavigationTarget.World target, EditorTabs tabs, Supplier<EditorContext> editors) {
        return tabs.focusOrCreateIfAbsent(WorldView.class, view -> true,
                () -> new WorldView(editors.get())).thenAccept(view -> view.show(target.tab()));
    }

    @Override
    public String label(NavigationTarget.World target) {
        return "world";
    }

    @Override
    public Reveal reveal(NavigationTarget.World target) {
        return (tree, wanted) -> tree.revealWorld(target.tab(), wanted);
    }
}
