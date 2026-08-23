package com.github.minecraft_ta.totalDebugCompanion.bytecode.reference;

/** Receives search progress and allows a caller to request cooperative cancellation. */
public interface ReferenceSearchMonitor {
    ReferenceSearchMonitor NONE = new ReferenceSearchMonitor() {
    };

    default boolean isCancelled() {
        return false;
    }

    default void onProgress(ReferenceSearchProgress progress) {
    }
}
