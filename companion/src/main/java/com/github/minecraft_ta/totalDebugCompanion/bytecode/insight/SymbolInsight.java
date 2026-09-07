package com.github.minecraft_ta.totalDebugCompanion.bytecode.insight;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Usage and source-level hierarchy information for one resolved declaration. */
public record SymbolInsight(long usageCount, List<HierarchyFacet> hierarchy) {
    public static final SymbolInsight EMPTY = new SymbolInsight(0, List.of());

    public SymbolInsight {
        if (usageCount < 0) {
            throw new IllegalArgumentException("Usage count must not be negative");
        }
        hierarchy = List.copyOf(Objects.requireNonNull(hierarchy, "hierarchy"));
        EnumSet<HierarchyRelation> relations = EnumSet.noneOf(HierarchyRelation.class);
        for (HierarchyFacet facet : hierarchy) {
            if (!relations.add(facet.relation())) {
                throw new IllegalArgumentException("Duplicate hierarchy relation " + facet.relation());
            }
        }
    }

    public int count(HierarchyDirection direction) {
        return this.hierarchy.stream()
                .filter(facet -> facet.relation().direction() == direction)
                .mapToInt(HierarchyFacet::count)
                .sum();
    }

    public Optional<HierarchyFacet> descendantFacet() {
        return this.hierarchy.stream()
                .filter(facet -> facet.relation().direction() == HierarchyDirection.IMPLEMENTATIONS)
                .findFirst();
    }

    public Optional<HierarchyRelation> primaryGutterRelation() {
        if (count(HierarchyDirection.BASE_METHODS) > 0) {
            return Optional.of(count(HierarchyRelation.OVERRIDES) > 0
                    ? HierarchyRelation.OVERRIDES
                    : HierarchyRelation.IMPLEMENTS);
        }
        return descendantFacet().map(HierarchyFacet::relation);
    }

    public int count(HierarchyRelation relation) {
        return this.hierarchy.stream()
                .filter(facet -> facet.relation() == relation)
                .mapToInt(HierarchyFacet::count)
                .findFirst()
                .orElse(0);
    }
}
