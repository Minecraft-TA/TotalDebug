package com.github.minecraft_ta.totaldebug.client.companion;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Tells Companion a piece of the game's state whenever it changes, or again after Companion connects: {@code capture}
 * is read once a second on the client thread, and {@code publish} runs when it differs from what was told last.
 */
public final class ChangePublisher<T> {
    private static final int CHECK_TICKS = 20;

    private final Supplier<T> capture;
    private final Consumer<T> publish;
    private T published;
    private int ticks;

    public ChangePublisher(Supplier<T> capture, Consumer<T> publish) {
        this.capture = Objects.requireNonNull(capture, "capture");
        this.publish = Objects.requireNonNull(publish, "publish");
    }

    /** Publishes again at the next check, such as for a newly connected Companion. */
    public synchronized void republish() {
        this.published = null;
        this.ticks = CHECK_TICKS;
    }

    /** Publishes {@code value} now, for a change the next check could miss. Client thread only. */
    public void publish(T value) {
        synchronized (this) {
            if (value.equals(this.published)) return;
            this.published = value;
        }
        this.publish.accept(value);
    }

    /** Client thread only. */
    public void tick() {
        synchronized (this) {
            if (++this.ticks < CHECK_TICKS) return;
            this.ticks = 0;
        }
        T current = this.capture.get();
        synchronized (this) {
            if (current.equals(this.published)) return;
            this.published = current;
        }
        this.publish.accept(current);
    }
}
