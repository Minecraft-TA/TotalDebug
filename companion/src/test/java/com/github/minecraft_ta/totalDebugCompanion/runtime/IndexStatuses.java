package com.github.minecraft_ta.totalDebugCompanion.runtime;

import java.util.function.Consumer;

/** Hands a test each status of an index as it is published, the current one first. */
public final class IndexStatuses {
    private IndexStatuses() {
    }

    /** Tells {@code told} the current status and each one after; returns what stops it. */
    public static Runnable follow(RuntimeIndexService service, Consumer<RuntimeIndexService.Status> told) {
        Runnable stop = service.statusChanged().subscribe(() -> told.accept(service.status()));
        told.accept(service.status());
        return stop;
    }
}
