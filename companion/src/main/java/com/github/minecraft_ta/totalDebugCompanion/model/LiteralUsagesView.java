package com.github.minecraft_ta.totalDebugCompanion.model;

import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeBinding;
import com.github.minecraft_ta.totalDebugCompanion.ui.EditorContext;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceQuery;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.editors.UsagesViewPanel;

import javax.swing.Icon;
import java.awt.Component;
import java.util.Objects;

public final class LiteralUsagesView implements IEditorPanel {
    private final RuntimeBinding runtimeBinding;
    @Override public RuntimeBinding runtimeBinding() { return runtimeBinding; }

    private final String literal;
    private final UsagesViewPanel panel;

    public LiteralUsagesView(EditorContext context, String literal, RuntimeBinding runtimeBinding) {
        if (runtimeBinding == null) throw new IllegalStateException("Reference search is unavailable");
        this.runtimeBinding = runtimeBinding;
        this.literal = Objects.requireNonNull(literal, "literal");
        this.panel = new UsagesViewPanel(
                ReferenceQuery.stringLiteral(literal),
                quotedPreview(literal),
                Icons.VALUE,
                runtimeBinding.references(), context.navigation()::navigate
        );
    }

    public String literal() {
        return this.literal;
    }

    public void restartSearch() {
        this.panel.restartSearch();
    }

    @Override
    public void dispose() {
        this.panel.dispose();
    }

    @Override
    public String getTitle() {
        return "Text: " + quotedPreview(this.literal);
    }

    @Override
    public String getTooltip() {
        return "Occurrences of the indexed string literal " + quotedPreview(this.literal);
    }

    @Override
    public Icon getIcon() {
        return Icons.VALUE;
    }

    @Override
    public Component getComponent() {
        return this.panel;
    }

    @Override
    public NavigationTarget getNavigationTarget() {
        return new NavigationTarget.LiteralUsages(this.literal);
    }

    private static String quotedPreview(String value) {
        String singleLine = value.replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
        if (singleLine.length() > 48) {
            singleLine = singleLine.substring(0, 47) + '…';
        }
        return '"' + singleLine + '"';
    }
}
