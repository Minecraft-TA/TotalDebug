package com.github.minecraft_ta.totalDebugCompanion.model;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.CodeSymbol;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.editors.UsagesViewPanel;

import javax.swing.Icon;
import java.awt.Component;
import java.util.Objects;

public final class UsagesView implements IEditorPanel {
    private final CodeSymbol symbol;
    private final UsagesViewPanel panel;

    public UsagesView(CodeSymbol symbol) {
        this.symbol = Objects.requireNonNull(symbol, "symbol");
        this.panel = new UsagesViewPanel(symbol, CompanionApp.getReferenceSearchService());
    }

    public CodeSymbol symbol() {
        return this.symbol;
    }

    public void restartSearch() {
        this.panel.restartSearch();
    }

    @Override
    public String getTitle() {
        return "Usages: " + shortName(this.symbol);
    }

    @Override
    public String getTooltip() {
        return "Usages of " + this.symbol.displayName();
    }

    @Override
    public Icon getIcon() {
        return Icons.SEARCH_ICON;
    }

    @Override
    public Component getComponent() {
        return this.panel;
    }

    @Override
    public NavigationTarget getNavigationTarget() {
        return new NavigationTarget.SymbolUsages(this.symbol);
    }

    private static String shortName(CodeSymbol symbol) {
        return switch (symbol) {
            case CodeSymbol.ClassSymbol type -> simpleClassName(type.className());
            case CodeSymbol.FieldSymbol field -> field.name();
            case CodeSymbol.MethodSymbol method -> "<init>".equals(method.name())
                    ? simpleClassName(method.ownerClassName())
                    : method.name();
        };
    }

    private static String simpleClassName(String binaryName) {
        return binaryName.substring(binaryName.lastIndexOf('.') + 1).replace('$', '.');
    }
}
