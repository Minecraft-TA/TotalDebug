package com.github.minecraft_ta.totalDebugCompanion.bytecode.insight;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class SymbolInsightTest {
    @Test
    void prioritizesConcreteBaseWithoutDiscardingOtherRelationships() {
        SymbolInsight insight = new SymbolInsight(3, List.of(
                new HierarchyFacet(HierarchyRelation.OVERRIDDEN_BY, 4),
                new HierarchyFacet(HierarchyRelation.IMPLEMENTS, 2),
                new HierarchyFacet(HierarchyRelation.OVERRIDES, 1)
        ));

        assertEquals(4, insight.count(HierarchyDirection.IMPLEMENTATIONS));
        assertEquals(3, insight.count(HierarchyDirection.BASE_METHODS));
        assertEquals(HierarchyRelation.OVERRIDES, insight.primaryGutterRelation().orElseThrow());
    }

    @Test
    void rejectsDuplicateRelationshipGroups() {
        assertThrows(IllegalArgumentException.class, () -> new SymbolInsight(0, List.of(
                new HierarchyFacet(HierarchyRelation.IMPLEMENTS, 1),
                new HierarchyFacet(HierarchyRelation.IMPLEMENTS, 2)
        )));
    }
}
