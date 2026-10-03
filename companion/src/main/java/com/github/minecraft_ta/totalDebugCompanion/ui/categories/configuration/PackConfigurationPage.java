package com.github.minecraft_ta.totalDebugCompanion.ui.categories.configuration;

import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.navigation.Page;
import com.github.minecraft_ta.totalDebugCompanion.ui.EditorContext;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.EditorTabs;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** The modpack's configuration files. */
public final class PackConfigurationPage implements Page<NavigationTarget.PackConfiguration> {
    @Override
    public Class<NavigationTarget.PackConfiguration> target() {
        return NavigationTarget.PackConfiguration.class;
    }

    @Override
    public CompletableFuture<Void> open(NavigationTarget.PackConfiguration target, EditorTabs tabs, Supplier<EditorContext> editors) {
        return tabs.focusOrCreateIfAbsent(PackConfigurationView.class, view -> true,
                () -> new PackConfigurationView(editors.get())).thenApply(view -> null);
    }

    @Override
    public String label(NavigationTarget.PackConfiguration target) {
        return "modpack configuration";
    }

    @Override
    public Reveal reveal(NavigationTarget.PackConfiguration target) {
        return (tree, wanted) -> tree.revealPackConfiguration(wanted);
    }
}
