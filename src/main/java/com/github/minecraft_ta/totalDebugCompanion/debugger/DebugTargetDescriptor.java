package com.github.minecraft_ta.totalDebugCompanion.debugger;

import java.util.Objects;

public record DebugTargetDescriptor(String id, String displayName, long processId) {
    public DebugTargetDescriptor {
        if (Objects.requireNonNull(id, "id").isBlank()) {
            throw new IllegalArgumentException("Debug target id is blank");
        }
        if (Objects.requireNonNull(displayName, "displayName").isBlank()) {
            throw new IllegalArgumentException("Debug target display name is blank");
        }
        if (processId < 1) {
            throw new IllegalArgumentException("Debug target process id must be positive");
        }
    }
}
