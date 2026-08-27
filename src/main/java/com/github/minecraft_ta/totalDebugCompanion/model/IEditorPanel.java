package com.github.minecraft_ta.totalDebugCompanion.model;

import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.BottomInformationBar;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationViewState;

import javax.swing.*;
import java.awt.*;
import java.util.concurrent.CompletableFuture;

public interface IEditorPanel {

    String getTitle();

    String getTooltip();

    Icon getIcon();

    Component getComponent();

    default CompletableFuture<Void> ready() {
        return CompletableFuture.completedFuture(null);
    }

    default EditorLocation getLocation() {
        return EditorLocation.empty();
    }

    default BottomInformationBar getInformationBar() {
        return null;
    }

    default NavigationTarget getNavigationTarget() {
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
