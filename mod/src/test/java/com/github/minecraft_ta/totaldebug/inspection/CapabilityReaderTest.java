package com.github.minecraft_ta.totaldebug.inspection;

import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.capabilities.EntityCapability;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityReaderTest {
    @Test
    void capabilitiesWithoutAContextAreQueriedWhenNoSideIsSelected() {
        Class<?> voidContext = EntityCapability.createVoid(
                ResourceLocation.fromNamespaceAndPath("total_debug", "test_void"), Object.class).contextClass();

        assertTrue(CapabilityReader.queryable(voidContext, null));
        assertFalse(CapabilityReader.queryable(voidContext, Direction.NORTH));
    }

    @Test
    void sidedCapabilitiesAreQueriedForAnySideAndOthersAreSkipped() {
        assertTrue(CapabilityReader.queryable(Direction.class, null));
        assertTrue(CapabilityReader.queryable(Direction.class, Direction.UP));
        assertFalse(CapabilityReader.queryable(String.class, null));
    }
}
