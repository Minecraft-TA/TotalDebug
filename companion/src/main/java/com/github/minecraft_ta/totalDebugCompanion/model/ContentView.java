package com.github.minecraft_ta.totalDebugCompanion.model;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.navigation.ModTab;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.ui.EditorContext;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog.ContentPanel;

import javax.swing.Icon;
import java.awt.Component;

/** A tab listing every block, item and entity type of the pack. */
public final class ContentView implements IEditorPanel {
    private final ContentPanel panel;

    public ContentView(EditorContext context) {
        this.panel = new ContentPanel(context.project().catalog(), context.itemIcons(), context.navigation()::navigate);
    }

    public void show(ModTab tab) {
        this.panel.show(tab);
    }

    @Override
    public String getTitle() {
        return "Content";
    }

    @Override
    public String getTooltip() {
        return "Blocks, items and entity types of every mod";
    }

    @Override
    public Icon getIcon() {
        return Icons.BLOCK;
    }

    @Override
    public Component getComponent() {
        return this.panel;
    }

    @Override
    public NavigationTarget getNavigationTarget() {
        return new NavigationTarget.Content(this.panel.selectedTab());
    }

    @Override
    public void dispose() {
        this.panel.dispose();
    }
}
