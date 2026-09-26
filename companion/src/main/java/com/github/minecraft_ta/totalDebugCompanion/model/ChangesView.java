package com.github.minecraft_ta.totalDebugCompanion.model;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.ui.EditorContext;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog.ChangesPanel;

import javax.swing.Icon;
import java.awt.Component;

/** A tab listing what Companion changed in the pack. */
public final class ChangesView implements IEditorPanel {
    private final ChangesPanel panel;

    public ChangesView(EditorContext context) {
        this.panel = new ChangesPanel(context.project().catalog(), context.project().configChanges(),
                context.project().keyBindings(), context.project().resources(), context.navigation()::navigate);
    }

    /** Reads the changed files again. */
    public void refresh() {
        this.panel.load();
    }

    @Override
    public String getTitle() {
        return "Changes";
    }

    @Override
    public String getTooltip() {
        return "What Companion changed in the pack";
    }

    @Override
    public Icon getIcon() {
        return Icons.CHANGES;
    }

    @Override
    public Component getComponent() {
        return this.panel;
    }

    @Override
    public NavigationTarget getNavigationTarget() {
        return new NavigationTarget.Changes();
    }

    @Override
    public void dispose() {
        this.panel.dispose();
    }
}
