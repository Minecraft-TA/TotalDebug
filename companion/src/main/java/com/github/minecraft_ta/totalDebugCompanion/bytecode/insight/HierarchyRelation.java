package com.github.minecraft_ta.totalDebugCompanion.bytecode.insight;

/** The source-level meaning of one indexed hierarchy relationship. */
public enum HierarchyRelation {
    SUBTYPES(HierarchyDirection.IMPLEMENTATIONS),
    IMPLEMENTED_BY(HierarchyDirection.IMPLEMENTATIONS),
    OVERRIDDEN_BY(HierarchyDirection.IMPLEMENTATIONS),
    IMPLEMENTS(HierarchyDirection.BASE_METHODS),
    OVERRIDES(HierarchyDirection.BASE_METHODS);

    private final HierarchyDirection direction;

    HierarchyRelation(HierarchyDirection direction) {
        this.direction = direction;
    }

    public HierarchyDirection direction() {
        return this.direction;
    }
}
