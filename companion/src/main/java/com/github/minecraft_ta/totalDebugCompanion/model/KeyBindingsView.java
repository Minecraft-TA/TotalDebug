package com.github.minecraft_ta.totalDebugCompanion.model;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.ui.EditorContext;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog.KeyBindingsPanel;

import javax.swing.Icon;
import java.awt.Component;

/** A tab listing the pack's key bindings. */
public final class KeyBindingsView implements IEditorPanel {
    private final KeyBindingsPanel panel;

    public KeyBindingsView(EditorContext context) {
        this.panel = new KeyBindingsPanel(context.project().catalog(), context.project().keyBindings(), "",
                context.navigation()::navigate);
    }

    /** Reads the keys again. */
    public void refresh() {
        this.panel.load();
    }

    /** Shows a binding, such as {@code key.jump}; an empty name shows none. */
    public void show(String binding) {
        if (!binding.isEmpty()) this.panel.select(binding);
    }

    @Override
    public String getTitle() {
        return "Key bindings";
    }

    @Override
    public String getTooltip() {
        return "Key bindings of every mod";
    }

    @Override
    public Icon getIcon() {
        return Icons.KEYBOARD;
    }

    @Override
    public Component getComponent() {
        return this.panel;
    }

    @Override
    public NavigationTarget getNavigationTarget() {
        return new NavigationTarget.KeyBindings("");
    }

    @Override
    public void dispose() {
        this.panel.dispose();
    }
}
