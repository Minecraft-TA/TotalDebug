package com.github.minecraft_ta.totalDebugCompanion.model;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeBinding;
import com.github.minecraft_ta.totalDebugCompanion.ui.EditorContext;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.inspection.InspectionPanel;
import com.github.minecraft_ta.totaldebug.protocol.message.InspectSubjectPayload;

import javax.swing.Icon;
import java.awt.Component;
import java.util.Objects;

/** A tab showing one block or entity selected in the game. */
public final class InspectionView implements IEditorPanel {
    private final RuntimeBinding runtimeBinding;
    private final InspectSubjectPayload subject;
    private final InspectionPanel panel;

    public InspectionView(EditorContext context, InspectSubjectPayload subject, RuntimeBinding runtimeBinding) {
        this.runtimeBinding = runtimeBinding;
        this.subject = Objects.requireNonNull(subject, "subject");
        this.panel = new InspectionPanel(subject, context.snippets(), context.navigation()::navigate);
    }

    public InspectSubjectPayload subject() {
        return this.subject;
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
        return this.subject.displayName().isBlank() ? this.subject.registryId() : this.subject.displayName();
    }

    @Override
    public String getTooltip() {
        return this.subject.subject();
    }

    @Override
    public Icon getIcon() {
        return Icons.EVALUATE_EXPRESSION;
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
