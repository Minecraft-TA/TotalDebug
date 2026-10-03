package com.github.minecraft_ta.totalDebugCompanion.ui.categories.logs;

import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.navigation.Page;
import com.github.minecraft_ta.totalDebugCompanion.ui.EditorContext;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.EditorTabs;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** The game's logs and crash reports. */
public final class LogsPage implements Page<NavigationTarget.Logs> {
    @Override
    public Class<NavigationTarget.Logs> target() {
        return NavigationTarget.Logs.class;
    }

    @Override
    public CompletableFuture<Void> open(NavigationTarget.Logs target, EditorTabs tabs, Supplier<EditorContext> editors) {
        return tabs.focusOrCreateIfAbsent(LogsView.class, view -> true,
                () -> new LogsView(editors.get())).thenAccept(view -> view.show(target.file()));
    }

    @Override
    public String label(NavigationTarget.Logs target) {
        return "logs";
    }

    @Override
    public Reveal reveal(NavigationTarget.Logs target) {
        return (tree, wanted) -> tree.revealLogs(wanted);
    }
}
