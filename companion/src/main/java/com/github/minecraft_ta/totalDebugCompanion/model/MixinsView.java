package com.github.minecraft_ta.totalDebugCompanion.model;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.ui.EditorContext;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog.MixinsPanel;

import javax.swing.Icon;
import java.awt.Component;

/** A tab listing the mixins of every mod by the members they change. */
public final class MixinsView implements IEditorPanel {
    private final MixinsPanel panel;

    public MixinsView(EditorContext context) {
        this.panel = new MixinsPanel(context.project().catalog(), context.navigation()::navigate);
    }

    @Override
    public String getTitle() {
        return "Mixins";
    }

    @Override
    public String getTooltip() {
        return "The mixins of every mod, by the members they change";
    }

    @Override
    public Icon getIcon() {
        return Icons.MIXIN;
    }

    @Override
    public Component getComponent() {
        return this.panel;
    }

    @Override
    public NavigationTarget getNavigationTarget() {
        return new NavigationTarget.Mixins();
    }

    @Override
    public void dispose() {
        this.panel.dispose();
    }
}
