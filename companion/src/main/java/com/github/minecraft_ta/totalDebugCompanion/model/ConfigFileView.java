package com.github.minecraft_ta.totalDebugCompanion.model;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigSources;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationViewState;
import com.github.minecraft_ta.totalDebugCompanion.ui.EditorContext;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog.ConfigFilePanel;

import javax.swing.Icon;
import java.awt.Component;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/** A tab editing one of a mod's configuration files as text. */
public final class ConfigFileView implements IEditorPanel {
    private final ConfigFilePanel panel;
    private final EditorLocation location;

    /** Reads the configuration file to open it, on file work. */
    public static String read(Path file) throws IOException {
        return ConfigFilePanel.read(file);
    }

    /** Shows {@code text}, as {@link #read} read it, and reads the file again whenever the tab is shown. */
    public ConfigFileView(EditorContext context, Path file, ConfigSources.Owner owner, String text) {
        this.panel = new ConfigFilePanel(file, owner, context.project().configSettings(), text);
        this.location = EditorLocation.forFile(file, context.project().profile().workspaceDirectory());
    }

    public Path getPath() {
        return this.panel.file();
    }

    /** Places the caret at {@code offset} in the text shown, if {@code stillWanted} still holds then. */
    public CompletableFuture<Void> navigateToOffset(int offset, BooleanSupplier stillWanted) {
        return this.panel.navigateToOffset(offset, stillWanted);
    }

    @Override
    public NavigationViewState captureNavigationViewState() {
        return this.panel.captureNavigationViewState();
    }

    @Override
    public void restoreNavigationViewState(NavigationViewState state) {
        this.panel.restoreNavigationViewState(state);
    }

    @Override
    public void dispose() {
        this.panel.dispose();
    }

    @Override
    public String getTitle() {
        return this.panel.file().getFileName().toString();
    }

    @Override
    public String getTooltip() {
        return this.location.tooltip();
    }

    @Override
    public Icon getIcon() {
        return Icons.CONFIG_FILE;
    }

    @Override
    public Component getComponent() {
        return this.panel;
    }

    @Override
    public EditorLocation getLocation() {
        return this.location;
    }

    @Override
    public NavigationTarget getNavigationTarget() {
        return new NavigationTarget.LocalFile(this.panel.file());
    }

    @Override
    public boolean canClose() {
        return this.panel.canClose();
    }
}
