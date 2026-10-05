package com.github.minecraft_ta.totalDebugCompanion.ui.categories.resources;

import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.navigation.Page;
import com.github.minecraft_ta.totalDebugCompanion.ui.EditorContext;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.EditorTabs;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** The resources of every pack the game uses, or the packs themselves. */
public final class PackResourcesPage implements Page<NavigationTarget.PackResources> {
    @Override
    public Class<NavigationTarget.PackResources> target() {
        return NavigationTarget.PackResources.class;
    }

    @Override
    public CompletableFuture<Void> open(NavigationTarget.PackResources target, EditorTabs tabs, Supplier<EditorContext> editors) {
        return tabs.focusOrCreateIfAbsent(PackResourcesView.class, view -> true,
                () -> new PackResourcesView(editors.get())).thenAccept(view -> view.show(target));
    }

    @Override
    public String label(NavigationTarget.PackResources target) {
        return "modpack resources";
    }

    @Override
    public Reveal reveal(NavigationTarget.PackResources target) {
        return (tree, wanted) -> tree.revealPackResources(wanted);
    }
}
