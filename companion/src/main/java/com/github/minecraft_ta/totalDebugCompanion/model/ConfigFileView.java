package com.github.minecraft_ta.totalDebugCompanion.model;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigSources;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.ui.EditorContext;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog.ConfigFilePanel;

import javax.swing.Icon;
import java.awt.Component;
import java.nio.file.Path;

/** A tab editing one of a mod's configuration files as text. */
public final class ConfigFileView implements IEditorPanel {
    private final ConfigFilePanel panel;
    private final EditorLocation location;

    public ConfigFileView(EditorContext context, Path file, ConfigSources.Owner owner) {
        this.panel = new ConfigFilePanel(file, owner, context.project().configChanges());
        this.location = EditorLocation.forFile(file, context.project().profile().workspaceDirectory());
    }

    public Path getPath() {
        return this.panel.file();
    }

    public void navigateToOffset(int offset) {
        this.panel.navigateToOffset(offset);
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
