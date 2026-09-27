package com.github.minecraft_ta.totalDebugCompanion.model;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.ui.EditorContext;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog.LogsPanel;

import javax.swing.Icon;
import java.awt.Component;
import java.nio.file.Path;

/** A tab listing the game's logs and crash reports. */
public final class LogsView implements IEditorPanel {
    private final LogsPanel panel;

    public LogsView(EditorContext context) {
        this.panel = new LogsPanel(context.project().catalog(), context.project().profile().workspaceDirectory(),
                context.navigation()::navigate);
    }

    /** Lists the logs and crash reports again, selecting {@code file} when it is not null. */
    public void show(Path file) {
        this.panel.select(file);
        this.panel.load();
    }

    @Override
    public String getTitle() {
        return "Logs";
    }

    @Override
    public String getTooltip() {
        return "The game's logs and crash reports";
    }

    @Override
    public Icon getIcon() {
        return Icons.TEXT_FILE;
    }

    @Override
    public Component getComponent() {
        return this.panel;
    }

    @Override
    public NavigationTarget getNavigationTarget() {
        return new NavigationTarget.Logs();
    }

    @Override
    public void dispose() {
        this.panel.dispose();
    }
}
