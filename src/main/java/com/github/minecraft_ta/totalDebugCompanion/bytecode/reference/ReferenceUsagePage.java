package com.github.minecraft_ta.totalDebugCompanion.bytecode.reference;

import java.util.List;
import java.util.Objects;

/** A bounded page of indexed reference usages. */
public record ReferenceUsagePage(List<ReferenceUsage> usages, boolean truncated) {
    public ReferenceUsagePage {
        usages = List.copyOf(Objects.requireNonNull(usages, "usages"));
    }
}
