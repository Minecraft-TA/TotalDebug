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
    void matchesCamelCaseWordStarts() {
        assertEquals(
                List.of(
                        new SpeedSearch.MatchRange(0, 1),
                        new SpeedSearch.MatchRange(8, 9),
                        new SpeedSearch.MatchRange(13, 14)
                ),
                SpeedSearchMatcher.match("DebuggerValueTree", "dvt")
        );
    }

    @Test
    void fallsBackToAnOrderedSubsequence() {
        assertEquals(
                List.of(
                        new SpeedSearch.MatchRange(0, 1),
                        new SpeedSearch.MatchRange(3, 4),
                        new SpeedSearch.MatchRange(5, 6)
                ),
                SpeedSearchMatcher.match("packet", "pkt")
        );
    }

    @Test
    void rejectsOutOfOrderCharacters() {
        assertTrue(SpeedSearchMatcher.match("frames", "sf").isEmpty());
    }
}
