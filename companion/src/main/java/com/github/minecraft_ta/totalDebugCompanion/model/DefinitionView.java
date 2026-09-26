package com.github.minecraft_ta.totalDebugCompanion.model;

import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.ui.EditorContext;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog.DefinitionDetails;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.inspection.SubjectPanel;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;

import javax.swing.Icon;
import java.awt.Component;

/** A tab showing one registered block, item or entity type. */
public final class DefinitionView implements IEditorPanel {
    private final SubjectRef.Definition subject;
    private final SubjectPanel panel;

    public DefinitionView(EditorContext context, SubjectRef.Definition subject) {
        this.subject = subject;
        this.panel = SubjectPanel.definition(subject, new DefinitionDetails.Services(context.project().catalog(),
                () -> context.project().sources(), context.itemIcons(), context.navigation()::navigate));
    }

    public SubjectRef.Definition subject() {
        return this.subject;
    }

    @Override
    public String getTitle() {
        return this.panel.title();
    }

    @Override
    public String getTooltip() {
        return this.subject.format();
    }

    @Override
    public Icon getIcon() {
        return this.panel.tabIcon();
    }

    @Override
    public Component getComponent() {
        return this.panel;
    }

    @Override
    public NavigationTarget getNavigationTarget() {
        return new NavigationTarget.Definition(this.subject);
    }

    @Override
    public void dispose() {
        this.panel.dispose();
    }
}
