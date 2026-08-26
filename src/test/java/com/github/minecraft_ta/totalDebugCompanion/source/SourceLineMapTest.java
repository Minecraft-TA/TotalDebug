package com.github.minecraft_ta.totalDebugCompanion.source;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceLineMapTest {
    @Test
    void displayedLineLookupIsExactAndSupportsDuplicateMappings() {
        SourceLineMap lineMap = SourceLineMap.fromOriginalToDisplayed(new int[]{20, 8, 10, 4, 21, 8});

        assertTrue(lineMap.containsDisplayedLine(4));
        assertTrue(lineMap.containsDisplayedLine(8));
        assertFalse(lineMap.containsDisplayedLine(5));
        assertFalse(lineMap.containsDisplayedLine(7));
        assertFalse(lineMap.containsDisplayedLine(9));
    }

    @Test
    void emptyMapCarriesNoStaticValidityInformation() {
        SourceLineMap lineMap = SourceLineMap.empty();

        assertTrue(lineMap.isEmpty());
        assertFalse(lineMap.containsDisplayedLine(1));
    }

    @Test
    void findsTheFirstMappedLineWithinAMethodRange() {
        SourceLineMap lineMap = SourceLineMap.fromOriginalToDisplayed(
                new int[]{40, 13, 41, 18, 42, 21, 43, 21}
        );

        assertEquals(18, lineMap.firstMappedDisplayedLine(14, 20).orElseThrow());
        assertTrue(lineMap.firstMappedDisplayedLine(14, 17).isEmpty());
        assertEquals(21, lineMap.firstMappedDisplayedLine(21, 21).orElseThrow());
    }
}
