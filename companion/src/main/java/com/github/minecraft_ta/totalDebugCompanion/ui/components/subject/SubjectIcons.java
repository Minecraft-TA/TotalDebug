package com.github.minecraft_ta.totalDebugCompanion.ui.components.subject;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.navigation.ModTab;
import com.github.minecraft_ta.totaldebug.protocol.execution.FactLink;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;

import javax.swing.Icon;

/** Consistent type icons for subject navigation, page tabs and links. */
public final class SubjectIcons {
    private SubjectIcons() { }

    public static Icon tab(ModTab tab) {
        return switch (tab) {
            case OVERVIEW -> Icons.MOD;
            case CONTENT -> Icons.CONTENT;
            case CONFIGURATION -> Icons.CONFIG_FILE;
            case KEY_BINDINGS -> Icons.KEYBOARD;
            case RESOURCES -> Icons.RESOURCES_ROOT;
        };
    }

    public static Icon link(FactLink link) {
        if (link.kind() == FactLink.Kind.CLASS) return Icons.JAVA_CLASS;
        SubjectRef subject;
        try {
            subject = SubjectRef.parse(link.target());
        } catch (IllegalArgumentException unknown) {
            return null;
        }
        return switch (subject) {
            case SubjectRef.Mod ignored -> Icons.MOD;
            case SubjectRef.Definition definition -> ContentKinds.of(definition.registry()).icon();
            case SubjectRef.Block ignored -> Icons.BLOCK;
            case SubjectRef.Entity ignored -> Icons.ENTITY;
        };
    }
}
