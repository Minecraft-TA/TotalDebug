package com.github.minecraft_ta.totalDebugCompanion.navigation;

import java.util.Objects;

record NavigationEntry(
        NavigationTarget target,
        String runtimeSignature,
        NavigationViewState viewState
) {
    NavigationEntry {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(viewState, "viewState");
        if (requiresRuntime(target) && (runtimeSignature == null || runtimeSignature.isBlank())) {
            throw new IllegalArgumentException("Runtime navigation requires a runtime signature");
        }
        if (!requiresRuntime(target) && runtimeSignature != null) {
            throw new IllegalArgumentException("Non-runtime navigation must not carry a runtime signature");
        }
    }

    boolean isValidForRuntime(String currentRuntimeSignature) {
        return this.runtimeSignature == null || this.runtimeSignature.equals(currentRuntimeSignature);
    }

    static boolean requiresRuntime(NavigationTarget target) {
        return switch (target) {
            case NavigationTarget.LocalFile ignored -> false;
            case NavigationTarget.LocalDirectory ignored -> false;
            case NavigationTarget.ArchiveEntry ignored -> false;
            case NavigationTarget.ArchiveDirectory ignored -> false;
            default -> true;
        };
    }
}
