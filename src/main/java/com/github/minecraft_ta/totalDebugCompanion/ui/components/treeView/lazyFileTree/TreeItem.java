package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree;

import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryText;

import javax.swing.*;

public class TreeItem {

    private final boolean isHiddenRoot;
    private final String name;
    private PrimarySecondaryText presentation;
    private Icon icon;

    protected TreeItem(String name) {
        this(name, false);
    }

    TreeItem(String name, boolean isHiddenRoot) {
        this.name = name;
        this.isHiddenRoot = isHiddenRoot;
    }

    public void delete() {

    }

    public void dispose() {

    }

    public boolean isHiddenRoot() {
        return isHiddenRoot;
    }

    public boolean isDirectory() {
        return false;
    }

    public String getName() {
        return name;
    }

    public void setPresentation(PrimarySecondaryText presentation) {
        this.presentation = presentation;
    }

    public PrimarySecondaryText getPresentation() {
        return this.presentation == null ? PrimarySecondaryText.primary(this.name) : this.presentation;
    }

    public String getTooltip() {
        return this.name;
    }

    public void setIcon(Icon icon) {
        this.icon = icon;
    }

    public Icon getIcon() {
        return this.icon;
    }
}
