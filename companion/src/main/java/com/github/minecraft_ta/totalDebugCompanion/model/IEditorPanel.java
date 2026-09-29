package com.github.minecraft_ta.totalDebugCompanion.model;

import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeBinding;
import java.util.function.Consumer;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationViewState;

import javax.swing.*;
import java.awt.*;
import java.util.concurrent.CompletableFuture;

public interface IEditorPanel {

    String getTitle();

    String getTooltip();

    Icon getIcon();

    /**
     * The catalog or the item icons changed: draws the tab's icon again where it depends on them, whether the page is
     * shown or not, since the tab strip shows it either way. {@link #getTitle()} is read when asked, so it needs nothing.
     */
    default void refreshTabIcon() {
    }

    Component getComponent();

    default CompletableFuture<Void> ready() {
        return CompletableFuture.completedFuture(null);
    }

    default EditorLocation getLocation() {
        return EditorLocation.empty();
    }

    default Runnable subscribeMetadata(Consumer<String> listener) {
        listener.accept("");
        return () -> {};
    }

    /** The runtime that supplied this view; local editors have no runtime owner. */
    default RuntimeBinding runtimeBinding() { return null; }

    default NavigationTarget getNavigationTarget() {
        return null;
    }

    default JavaEditorContext getJavaEditorContext() {
        return null;
    }

    default NavigationViewState captureNavigationViewState() {
        return NavigationViewState.EMPTY;
    }

    default void restoreNavigationViewState(NavigationViewState state) {
    }

    default boolean canClose() {
        return true;
    }

    default void dispose() {
    }
}
