package com.github.minecraft_ta.totalDebugCompanion.model;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.ui.EditorContext;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog.ModPanel;

import javax.swing.Icon;
import java.awt.Component;

/** A tab showing one installed mod. */
public final class ModView implements IEditorPanel {
    private final ModPanel panel;

    public ModView(EditorContext context, NavigationTarget.ModPage page) {
        this.panel = new ModPanel(page.modId(), context.project().catalog(), () -> context.project().sources(),
                context.itemIcons(), context.project().profile().workspaceDirectory(), context.project().configChanges(),
                context.navigation()::navigate);
        this.panel.show(page);
    }

    public String modId() {
        return this.panel.modId();
    }

    public void show(NavigationTarget.ModPage page) {
        this.panel.show(page);
    }

    @Override
    public String getTitle() {
        return this.panel.title();
    }

    @Override
    public String getTooltip() {
        return "mod " + this.panel.modId();
    }

    @Override
    public Icon getIcon() {
        return Icons.MOD;
    }

    @Override
    public Component getComponent() {
        return this.panel;
    }

    @Override
    public NavigationTarget getNavigationTarget() {
        return this.panel.target();
    }

    @Override
    public void dispose() {
        this.panel.dispose();
    }
}
