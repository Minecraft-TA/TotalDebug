package com.github.minecraft_ta.totalDebugCompanion.model;

import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeBinding;
import com.github.minecraft_ta.totalDebugCompanion.ui.EditorContext;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog.DefinitionDetails;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.inspection.SubjectPanel;
import com.github.minecraft_ta.totaldebug.protocol.message.InspectSubjectPayload;

import javax.swing.Icon;
import java.awt.Component;
import java.util.Objects;

/** A tab showing one block or entity selected in the game. */
public final class InspectionView implements IEditorPanel {
    private final RuntimeBinding runtimeBinding;
    private final InspectSubjectPayload subject;
    private final SubjectPanel panel;

    public InspectionView(EditorContext context, InspectSubjectPayload subject, RuntimeBinding runtimeBinding) {
        this.runtimeBinding = runtimeBinding;
        this.subject = Objects.requireNonNull(subject, "subject");
        this.panel = SubjectPanel.occurrence(subject, context.snippets(), () -> context.project().scriptFiles(),
                new DefinitionDetails.Services(context.project().catalog(), () -> context.project().sources(),
                        context.itemIcons(), context.navigation()::navigate));
    }

    public InspectSubjectPayload subject() {
        return this.subject;
    }

    /** Whether this tab shows {@code other}'s subject in the same game session, whatever occupies it now. */
    public boolean shows(InspectSubjectPayload other) {
        return this.subject.subject().equals(other.subject())
                && this.subject.gameSessionId().equals(other.gameSessionId());
    }

    public void refresh() {
        this.panel.refresh();
    }

    @Override
    public RuntimeBinding runtimeBinding() {
        return this.runtimeBinding;
    }

    @Override
    public String getTitle() {
        return this.panel.title();
    }

    @Override
    public String getTooltip() {
        return this.subject.subject();
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
        return new NavigationTarget.Inspection(this.subject);
    }

    @Override
    public void dispose() {
        this.panel.dispose();
    }
}
