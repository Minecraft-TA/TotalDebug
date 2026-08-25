package com.github.minecraft_ta.totalDebugCompanion.bytecode.insight;

import java.util.List;
import java.util.Objects;

public record HierarchyPage(List<HierarchyResult> results, boolean truncated) {
    public HierarchyPage {
        results = List.copyOf(Objects.requireNonNull(results, "results"));
    }
}
