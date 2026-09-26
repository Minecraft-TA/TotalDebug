package com.github.minecraft_ta.totaldebug.protocol.inspection;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SubjectIdentityTest {
    @Test
    void aTranslatedNameIsTheTitle() {
        assertEquals("Furnace", block("minecraft:furnace", "Furnace").title());
    }

    @Test
    void anUntranslatedOrMissingNameIsMadeOfTheRegistryPath() {
        assertEquals("Basic Energy Cube",
                block("mekanism:basic_energy_cube", "block.mekanism.basic_energy_cube").title());
        assertEquals("Basic Energy Cube", block("mekanism:basic_energy_cube", "").title());
        assertEquals("Wandering Trader", new SubjectIdentity(SubjectIdentity.Kind.ENTITY, "minecraft:wandering_trader",
                "entity.minecraft.wandering_trader", "Minecraft", List.of(), "").title());
        assertEquals("Iron Widget", new SubjectIdentity(SubjectIdentity.Kind.ITEM, "example:iron_widget",
                "item.example.iron_widget", "Example", List.of(), "").title(), "an item's key starts with item.");
    }

    private static SubjectIdentity block(String id, String displayName) {
        return new SubjectIdentity(SubjectIdentity.Kind.BLOCK, id, displayName, "", List.of(), "");
    }
}
