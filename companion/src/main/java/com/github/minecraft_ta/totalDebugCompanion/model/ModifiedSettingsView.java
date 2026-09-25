package com.github.minecraft_ta.totalDebugCompanion.model;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.ui.EditorContext;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog.ModifiedSettingsPanel;

import javax.swing.Icon;
import java.awt.Component;

/** A tab listing every setting of the pack that differs from its default. */
public final class ModifiedSettingsView implements IEditorPanel {
    private final ModifiedSettingsPanel panel;

    public ModifiedSettingsView(EditorContext context) {
        this.panel = new ModifiedSettingsPanel(context.project().catalog(), context.project().profile().workspaceDirectory(),
                context.project().configChanges(), context.navigation()::navigate);
    }

    /** Reads the configuration files again. */
    public void refresh() {
        this.panel.load();
    }

    @Override
    public String getTitle() {
        return "Modified settings";
    }

    @Override
    public String getTooltip() {
        return "Settings that differ from their default";
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
        return new NavigationTarget.ModifiedSettings();
    }

    @Override
    public void dispose() {
        this.panel.dispose();
    }
}
