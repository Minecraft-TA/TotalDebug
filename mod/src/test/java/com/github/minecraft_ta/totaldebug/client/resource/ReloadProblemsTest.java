package com.github.minecraft_ta.totaldebug.client.resource;

import com.github.minecraft_ta.totaldebug.protocol.message.ReloadResultPayload;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
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
    void aProblemBelongsToEveryEditedFileItsNameStandsFor() {
        ReloadProblems problems = ReloadProblems.open(List.of("data/ns/recipe/gear.json", "data/ns/loot_table/gear.json",
                "data/ns/recipe/cog.json"));
        try {
            problems.append(Log4jLogEvent.newBuilder().setLevel(Level.ERROR).setMessage(new SimpleMessage("Couldn't parse ns:gear")).build());
        } finally {
            problems.close();
        }
        assertEquals(List.of(new ReloadResultPayload.Problem("data/ns/recipe/gear.json", "Couldn't parse ns:gear"),
                        new ReloadResultPayload.Problem("data/ns/loot_table/gear.json", "Couldn't parse ns:gear")),
                problems.problems(), "a recipe and a loot table share the id ns:gear; the log line cannot tell them apart");
    }

    @Test
    void collectorsOfReloadsRunningAtOnceEachHearTheLog() {
        ReloadProblems resources = ReloadProblems.open(List.of("assets/ns/models/block/gear.json"));
        ReloadProblems data = ReloadProblems.open(List.of("data/ns/recipe/cog.json"));
        try {
            LogManager.getLogger("reloads").warn("Unable to load model ns:block/gear, and recipe ns:cog failed");
            data.close();
            LogManager.getLogger("reloads").warn("Unable to load model ns:block/gear again");
        } finally {
            resources.close();
            data.close();
        }
        assertEquals(2, resources.problems().size(), "closing the data reload's collector leaves the other listening");
        assertEquals(List.of(new ReloadResultPayload.Problem("data/ns/recipe/cog.json",
                "Unable to load model ns:block/gear, and recipe ns:cog failed")), data.problems());
    }

    @Test
    void aNameCountsOnlyWhole() {
        assertTrue(ReloadProblems.mentions("unable to load model: 'testmod:block/gear'", "testmod:block/gear"));
        assertTrue(ReloadProblems.mentions("missing testmod:block/gear.", "testmod:block/gear"));
        assertFalse(ReloadProblems.mentions("unable to load model: testmod:block/gear_box", "testmod:block/gear"));
        assertFalse(ReloadProblems.mentions("testmod:block/gear/inner", "testmod:block/gear"));
        assertFalse(ReloadProblems.mentions("unable to load model: othertestmod:block/gear", "testmod:block/gear"));
        assertTrue(ReloadProblems.mentions("missing assets/testmod/models/block/gear.json", "testmod/models/block/gear.json"));
    }
}
