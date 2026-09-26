package com.github.minecraft_ta.totaldebug.client.resource;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReloadProblemsTest {
    @Test
    void aResourceIsKnownByItsPathAndTheLocationsTheGameLogs() {
        assertEquals(Set.of("testmod/models/block/gear.json", "testmod:models/block/gear.json",
                        "testmod:models/block/gear", "testmod:block/gear"),
                ReloadProblems.names("assets/testmod/models/block/gear.json"));
        assertEquals(Set.of("testmod/recipe.json", "testmod:recipe.json", "testmod:recipe"),
                ReloadProblems.names("data/testmod/recipe.json"));
    }

    @Test
    void aNameCountsOnlyWhole() {
        assertTrue(ReloadProblems.mentions("unable to load model: 'testmod:block/gear'", "testmod:block/gear"));
        assertTrue(ReloadProblems.mentions("missing testmod:block/gear.", "testmod:block/gear"));
        assertFalse(ReloadProblems.mentions("unable to load model: testmod:block/gear_box", "testmod:block/gear"));
        assertFalse(ReloadProblems.mentions("testmod:block/gear/inner", "testmod:block/gear"));
    }
}
