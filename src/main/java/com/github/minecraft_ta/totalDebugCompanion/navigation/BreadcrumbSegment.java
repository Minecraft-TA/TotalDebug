package com.github.minecraft_ta.totalDebugCompanion.navigation;

import java.util.Objects;

/** One rendered breadcrumb and its optional semantic destination. */
public record BreadcrumbSegment(String label, NavigationTarget target, String tooltip) {
    public BreadcrumbSegment {
        if (Objects.requireNonNull(label, "label").isBlank()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        tooltip = Objects.requireNonNullElse(tooltip, "");
    }
}
