package com.github.minecraft_ta.totalDebugCompanion.model;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.navigation.WorldTab;
import com.github.minecraft_ta.totalDebugCompanion.ui.EditorContext;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog.WorldPanel;

import javax.swing.Icon;
import java.awt.Component;

/** A tab showing the current world: the one the game has open, or the one played last. */
public final class WorldView implements IEditorPanel {
    private final WorldPanel panel;

    public WorldView(EditorContext context) {
        this.panel = new WorldPanel(context.project().profile().workspaceDirectory(), context.project().catalog(),
                context.itemIcons(), context.navigation()::navigate);
    }

    public void show(WorldTab tab) {
        this.panel.show(tab);
    }

    @Override
    public String getTitle() {
        return this.panel.title();
    }

    @Override
    public String getTooltip() {
        return "The current world";
    }

    @Override
    public Icon getIcon() {
        return Icons.WORLD;
    }

    @Override
    public Component getComponent() {
        return this.panel;
    }

    @Override
    public NavigationTarget getNavigationTarget() {
        return new NavigationTarget.World(this.panel.selectedTab());
    }

    @Override
    public void dispose() {
        this.panel.dispose();
    }
}
