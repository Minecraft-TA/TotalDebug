package com.github.minecraft_ta.totalDebugCompanion.model;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.ui.EditorContext;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog.PackResourcesPanel;

import javax.swing.Icon;
import java.awt.Component;

/** A tab listing every resource of the pack as the game uses it. */
public final class PackResourcesView implements IEditorPanel {
    private final PackResourcesPanel panel;

    public PackResourcesView(EditorContext context) {
        this.panel = new PackResourcesPanel(context.project().catalog(), context.project().resources(),
                context.project().profile().workspaceDirectory(), context.navigation()::navigate);
    }

    /** Selects a kind of resource, such as {@code assets/textures}; empty selects all. */
    public void show(String category) {
        this.panel.selectCategory(category);
    }

    @Override
    public String getTitle() {
        return "Resources";
    }

    @Override
    public String getTooltip() {
        return "Resources of every mod and pack, as the game uses them";
    }

    @Override
    public Icon getIcon() {
        return Icons.RESOURCES_ROOT;
    }

    @Override
    public Component getComponent() {
        return this.panel;
    }

    @Override
    public NavigationTarget getNavigationTarget() {
        return new NavigationTarget.PackResources(this.panel.category());
    }

    @Override
    public void dispose() {
        this.panel.dispose();
    }
}
