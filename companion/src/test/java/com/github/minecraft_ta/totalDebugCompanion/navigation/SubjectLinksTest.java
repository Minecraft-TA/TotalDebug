package com.github.minecraft_ta.totalDebugCompanion.navigation;

import com.github.minecraft_ta.totaldebug.protocol.execution.FactLink;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubjectLinksTest {
    @Test
    void linksOpenClassesModsAndDefinitions() {
        assertEquals(new NavigationTarget.RuntimeClass("a.B"), SubjectLinks.target(FactLink.toClass("a.B")));
        assertEquals(new NavigationTarget.ModPage("mekanism", ModTab.OVERVIEW, ""),
                SubjectLinks.target(FactLink.toSubject(new SubjectRef.Mod("mekanism"))));
        SubjectRef.Definition item = new SubjectRef.Definition(SubjectRef.DefinitionKind.ITEM, "minecraft:stone");
        assertEquals(new NavigationTarget.Definition(item), SubjectLinks.target(FactLink.toSubject(item)));
    }

    @Test
    void aWorldSubjectCannotBeOpenedFromALink() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> SubjectLinks.target(new FactLink(FactLink.Kind.SUBJECT, "block minecraft:overworld 1 2 3")));
        assertTrue(failure.getMessage().contains("select it in the game with F6"), failure.getMessage());
    }

    @Test
    void catalogPagesDoNotDependOnTheRuntime() {
        assertFalse(NavigationEntry.requiresRuntime(new NavigationTarget.ModPage("mekanism")));
        assertFalse(NavigationEntry.requiresRuntime(new NavigationTarget.Definition(
                new SubjectRef.Definition(SubjectRef.DefinitionKind.BLOCK, "minecraft:stone"))));
        assertFalse(NavigationEntry.requiresRuntime(new NavigationTarget.RuntimeModuleNode("mekanism")));
    }
}
