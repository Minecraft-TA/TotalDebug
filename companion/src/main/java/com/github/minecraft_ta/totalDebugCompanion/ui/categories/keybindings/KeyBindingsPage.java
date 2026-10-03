package com.github.minecraft_ta.totalDebugCompanion.ui.categories.keybindings;

import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.navigation.Page;
import com.github.minecraft_ta.totalDebugCompanion.ui.EditorContext;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.EditorTabs;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** The key bindings of every mod. */
public final class KeyBindingsPage implements Page<NavigationTarget.KeyBindings> {
    @Override
    public Class<NavigationTarget.KeyBindings> target() {
        return NavigationTarget.KeyBindings.class;
    }

    @Override
    public CompletableFuture<Void> open(NavigationTarget.KeyBindings target, EditorTabs tabs, Supplier<EditorContext> editors) {
        return tabs.focusOrCreateIfAbsent(KeyBindingsView.class, view -> true,
                () -> new KeyBindingsView(editors.get())).thenAccept(view -> view.show(target.binding()));
    }

    @Override
    public String label(NavigationTarget.KeyBindings target) {
        return "key bindings";
    }

    @Override
    public Reveal reveal(NavigationTarget.KeyBindings target) {
        return (tree, wanted) -> tree.revealKeyBindings(wanted);
    }
}
