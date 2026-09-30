package com.github.minecraft_ta.totalDebugCompanion.project;

import com.github.minecraft_ta.totalDebugCompanion.util.Signal;

import javax.swing.SwingUtilities;
import java.util.Objects;
import java.util.function.Function;

/**
 * The project Companion shows now (docs/SYSTEMS.md, section 1). The window follows a signal of whichever project is
 * current through {@link #follows}, which moves to the next project on a switch and tells nothing of one no longer
 * current, so no follower checks which project a change came from.
 */
public final class CurrentProject {
    private final Signal changed = new Signal();
    private volatile ProjectScope scope;

    /** The current project, or null without one. */
    public ProjectScope scope() {
        return this.scope;
    }

    /** Fires after another project became current, or none, on the thread that switched. */
    public Signal changed() {
        return this.changed;
    }

    /** Makes {@code scope} the current project, or none when null, and tells the followers where it changed. */
    public void set(ProjectScope scope) {
        if (this.scope == scope) return;
        this.scope = scope;
        this.changed.fire();
    }

    /**
     * Runs {@code told} on the Swing thread whenever the signal {@code signal} names of the current project fires, while
     * that project is still current; follows the next project's after a switch. Returns what stops it.
     */
    public Runnable follows(Function<ProjectScope, Signal> signal, Runnable told) {
        Objects.requireNonNull(signal, "signal");
        Objects.requireNonNull(told, "told");
        Object lock = new Object();
        Runnable[] fromScope = {() -> { }};
        Runnable move = () -> {
            synchronized (lock) {
                fromScope[0].run();
                ProjectScope now = this.scope;
                fromScope[0] = now == null ? () -> { } : signal.apply(now).subscribe(() -> SwingUtilities.invokeLater(() -> {
                    if (this.scope == now) told.run();
                }));
            }
        };
        move.run();
        Runnable fromProject = this.changed.subscribe(move);
        return () -> {
            fromProject.run();
            synchronized (lock) {
                fromScope[0].run();
                fromScope[0] = () -> { };
            }
        };
    }
}
