package com.github.minecraft_ta.totalDebugCompanion.ui.categories.resources;

import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.navigation.Page;
import com.github.minecraft_ta.totalDebugCompanion.ui.EditorContext;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.EditorTabs;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** A resource pack or datapack of its own. */
public final class PackPage implements Page<NavigationTarget.Pack> {
    @Override
    public Class<NavigationTarget.Pack> target() {
        return NavigationTarget.Pack.class;
    }

    @Override
    public CompletableFuture<Void> open(NavigationTarget.Pack target, EditorTabs tabs, Supplier<EditorContext> editors) {
        return tabs.focusOrCreateIfAbsent(PackView.class, view -> view.file().equals(target.file()),
                () -> new PackView(editors.get(), target.file())).thenApply(view -> null);
    }

    @Override
    public String label(NavigationTarget.Pack target) {
        return "pack " + target.file().getFileName();
    }
}
