package com.github.minecraft_ta.totalDebugCompanion.ui;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.insight.HierarchyRelation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class HierarchyPresentationTest {
    @Test
    void distinguishesImplementationsFromOverrides() {
        assertEquals("2 implementations", HierarchyPresentation.codeVisionCount(
                HierarchyRelation.IMPLEMENTED_BY,
                2
        ));
        assertEquals("1 override", HierarchyPresentation.codeVisionCount(
                HierarchyRelation.OVERRIDDEN_BY,
                1
        ));
        assertEquals("Is overridden in 4 subclasses", HierarchyPresentation.previewTitle(
                HierarchyRelation.OVERRIDDEN_BY,
                4,
                false
        ));
        assertEquals("Overrides", HierarchyPresentation.previewTitle(HierarchyRelation.OVERRIDES, 2, false));
        assertEquals(
                "Overrides and implements",
                HierarchyPresentation.previewTitle(HierarchyRelation.OVERRIDES, 2, true)
        );
    }
}
