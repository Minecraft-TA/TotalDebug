package com.github.minecraft_ta.totalDebugCompanion.ui.speedsearch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeedSearchMatcherTest {
    @Test
    void prefersAContiguousCaseInsensitiveMatch() {
        assertEquals(
                List.of(new SpeedSearch.MatchRange(8, 13)),
                SpeedSearchMatcher.match("DebuggerFramesPane", "frame")
        );
    }

    @Test
    void matchesOrderedContiguousFragmentsSeparatedByWhitespace() {
        assertEquals(
                List.of(
                        new SpeedSearch.MatchRange(6, 9),
                        new SpeedSearch.MatchRange(10, 12)
                ),
                SpeedSearchMatcher.match("build.gradle", "gra le")
        );
    }

    @Test
    void rejectsScatteredCharacters() {
        assertTrue(SpeedSearchMatcher.match("AllTheCompressed", "asd").isEmpty());
    }

    @Test
    void requiresFragmentsInOrder() {
        assertTrue(SpeedSearchMatcher.match("level.graph", "gra le").isEmpty());
    }
}
