package com.github.minecraft_ta.totalDebugCompanion.ui.categories.content;

import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.navigation.Page;
import com.github.minecraft_ta.totalDebugCompanion.ui.EditorContext;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.EditorTabs;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.subject.ContentKinds;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** The registered content of every mod. */
public final class ContentPage implements Page<NavigationTarget.Content> {
    @Override
    public Class<NavigationTarget.Content> target() {
        return NavigationTarget.Content.class;
    }

    @Override
    public CompletableFuture<Void> open(NavigationTarget.Content target, EditorTabs tabs, Supplier<EditorContext> editors) {
        return tabs.focusOrCreateIfAbsent(ContentView.class, view -> true,
                () -> new ContentView(editors.get())).thenAccept(view -> view.show(target.registry()));
    }

    @Override
    public String label(NavigationTarget.Content target) {
        return "modpack " + (target.registry().isEmpty() ? "content"
                : ContentKinds.of(target.registry()).plural().toLowerCase(Locale.ROOT));
    }

    @Override
    public Reveal reveal(NavigationTarget.Content target) {
        return (tree, wanted) -> tree.revealContent(target.registry(), wanted);
    }
}
