package com.github.minecraft_ta.totalDebugCompanion.bytecode.insight;

import java.util.Objects;

/** A non-empty group of hierarchy targets with the same source-level relationship. */
public record HierarchyFacet(HierarchyRelation relation, int count) {
    public HierarchyFacet {
        Objects.requireNonNull(relation, "relation");
        if (count < 1) {
            throw new IllegalArgumentException("Hierarchy facet count must be positive");
        }
    }
}
