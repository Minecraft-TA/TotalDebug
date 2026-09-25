package com.github.minecraft_ta.totalDebugCompanion.model;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.ui.EditorContext;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog.PackConfigurationPanel;

import javax.swing.Icon;
import java.awt.Component;

/** A tab listing the settings of every mod in the pack. */
public final class PackConfigurationView implements IEditorPanel {
    private final PackConfigurationPanel panel;

    public PackConfigurationView(EditorContext context) {
        this.panel = new PackConfigurationPanel(context.project().catalog(), context.project().profile().workspaceDirectory(),
                context.project().configChanges(), context.navigation()::navigate);
    }

    /** Reads the configuration files again. */
    public void refresh() {
        this.panel.load();
    }

    @Override
    public String getTitle() {
        return "Configuration";
    }

    @Override
    public String getTooltip() {
        return "Settings of every mod";
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
    public NavigationTarget getNavigationTarget() {
        return new NavigationTarget.PackConfiguration();
    }

    @Override
    public void dispose() {
        this.panel.dispose();
    }
}
