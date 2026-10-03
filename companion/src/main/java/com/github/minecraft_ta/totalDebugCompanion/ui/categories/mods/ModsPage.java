package com.github.minecraft_ta.totalDebugCompanion.ui.categories.mods;

import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.navigation.Page;
import com.github.minecraft_ta.totalDebugCompanion.ui.EditorContext;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.EditorTabs;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** A mod's page, at the tab and section the target names. */
public final class ModsPage implements Page<NavigationTarget.ModPage> {
    @Override
    public Class<NavigationTarget.ModPage> target() {
        return NavigationTarget.ModPage.class;
    }

    @Override
    public CompletableFuture<Void> open(NavigationTarget.ModPage target, EditorTabs tabs, Supplier<EditorContext> editors) {
        return tabs.focusOrCreateIfAbsent(ModView.class, view -> view.modId().equals(target.modId()),
                () -> new ModView(editors.get(), target)).thenAccept(view -> view.show(target));
    }

    @Override
    public String label(NavigationTarget.ModPage target) {
        return "mod " + target.modId();
    }

    @Override
    public Reveal reveal(NavigationTarget.ModPage target) {
        return (tree, wanted) -> tree.revealModPage(target, wanted);
    }
}
