package com.github.minecraft_ta.totalDebugCompanion.navigation;

import com.github.minecraft_ta.totalDebugCompanion.ui.EditorContext;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.EditorTabs;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.FileTreeView;

import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** How a category opens its target, names it, and shows it in the Project tree. Swing thread. */
public interface Page<T extends NavigationTarget.CategoryTarget> {
    /** Where the Project tree shows a target, as a reveal that answers whether it found it. */
    @FunctionalInterface
    interface Reveal {
        CompletableFuture<Boolean> in(FileTreeView tree, BooleanSupplier stillWanted);
    }

    Class<T> target();

    /** Focuses the target's tab, made from {@code editors} where none is open, and shows the target in it. */
    CompletableFuture<Void> open(T target, EditorTabs tabs, Supplier<EditorContext> editors);

    /** The target as a failed navigation names it, such as {@code key bindings}. */
    String label(T target);

    /** Where the Project tree shows the target, or null where it has no place there. */
    default Reveal reveal(T target) {
        return null;
    }
}
