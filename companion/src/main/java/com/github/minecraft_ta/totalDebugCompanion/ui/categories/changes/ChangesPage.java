package com.github.minecraft_ta.totalDebugCompanion.ui.categories.changes;

import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.navigation.Page;
import com.github.minecraft_ta.totalDebugCompanion.ui.EditorContext;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.EditorTabs;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** The changes Companion made to the pack. */
public final class ChangesPage implements Page<NavigationTarget.Changes> {
    @Override
    public Class<NavigationTarget.Changes> target() {
        return NavigationTarget.Changes.class;
    }

    @Override
    public CompletableFuture<Void> open(NavigationTarget.Changes target, EditorTabs tabs, Supplier<EditorContext> editors) {
        return tabs.focusOrCreateIfAbsent(ChangesView.class, view -> true,
                () -> new ChangesView(editors.get())).thenApply(view -> null);
    }

    @Override
    public String label(NavigationTarget.Changes target) {
        return "changes";
    }

    @Override
    public Reveal reveal(NavigationTarget.Changes target) {
        return (tree, wanted) -> tree.revealChanges(wanted);
    }
}
