package com.github.minecraft_ta.totaldebug.storage;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/** Optional JFR spans and diagnostic timings; never controls application lifecycle. */
@Name("com.github.minecraft_ta.totaldebug.RuntimePhase")
@Label("TotalDebug runtime phase")
@Category("TotalDebug")
@StackTrace(false)
public final class RuntimePhase extends Event implements AutoCloseable {
    @Label("Phase")
    public final String phase;
    private final transient long started = System.nanoTime();
    private final transient boolean log = Boolean.getBoolean("totaldebug.profileTimings");
    private transient boolean finished;

    private RuntimePhase(String phase) {
        this.phase = phase;
        begin();
    }

    public static RuntimePhase start(String phase) {
        return new RuntimePhase(phase);
    }

    public static void run(String phase, Runnable action) {
        try (var span = start(phase)) {
            action.run();
        }
    }

    @Override
    public void close() {
        if (this.finished) {
            return;
        }
        this.finished = true;
        end();
        commit();
        if (this.log) {
            System.err.printf(java.util.Locale.ROOT, "[TotalDebug timing] %s %.3f ms%n",
                    this.phase, (System.nanoTime() - this.started) / 1_000_000.0);
        }
    }
}
