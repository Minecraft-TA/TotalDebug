package com.github.minecraft_ta.totalDebugCompanion.model;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.ui.EditorContext;
import com.github.minecraft_ta.totalDebugCompanion.ui.Tooltip;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog.PackPanel;

import javax.swing.Icon;
import java.awt.Component;
import java.nio.file.Path;

/** A tab showing one resource pack or datapack of its own folder or zip file. */
public final class PackView implements IEditorPanel {
    private final PackPanel panel;

    public PackView(EditorContext context, Path file) {
        this.panel = new PackPanel(file, context.navigation()::navigate);
    }

    public Path file() {
        return this.panel.file();
    }

    @Override
    public String getTitle() {
        return this.panel.title();
    }

    @Override
    public String getTooltip() {
        return Tooltip.shortPath(this.panel.file());
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
        return new NavigationTarget.Pack(this.panel.file());
    }

    @Override
    public void dispose() {
        this.panel.dispose();
    }
}
