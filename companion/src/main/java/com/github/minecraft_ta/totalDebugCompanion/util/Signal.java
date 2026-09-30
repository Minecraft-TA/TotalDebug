package com.github.minecraft_ta.totalDebugCompanion.util;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tells that a part of an owner's state changed (docs/SYSTEMS.md, section 1). A signal carries nothing: a follower asks the
 * owner for the value. The owner fires it after the value changed, never while holding its own lock.
 */
public final class Signal {
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    /** Runs {@code listener} after each change, on the thread that fired it; returns what removes it. */
    public Runnable subscribe(Runnable listener) {
        // A wrapper of its own, so removing it never removes another subscription of the same listener.
        Runnable subscription = Objects.requireNonNull(listener, "listener")::run;
        this.listeners.add(subscription);
        return () -> this.listeners.remove(subscription);
    }

    /** Tells every listener, in the order they subscribed. */
    public void fire() {
        this.listeners.forEach(Runnable::run);
    }
}
