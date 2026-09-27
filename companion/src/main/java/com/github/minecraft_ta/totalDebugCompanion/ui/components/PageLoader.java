package com.github.minecraft_ta.totalDebugCompanion.ui.components;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.event.HierarchyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Reads what a page shows off the Swing thread and shows it on that thread (docs/UI_GUIDE.md, Building and checking).
 * One read runs at a time: asking again while one runs reads once more when it finishes, and the older read is not
 * shown, since it may predate what the new request is about, such as a write. So a page shows only what its last request
 * read, and the same files are never read twice at once. A page loads when it is shown and whenever a source it follows
 * changes.
 */
public final class PageLoader<T> {
    /** What to read: prepared on the Swing thread, where the page's state is captured, then run off it. */
    @FunctionalInterface
    public interface Read<T> {
        /** The read to run, or null when there is nothing to read and the page already shows why. */
        Callable<T> prepare();
    }

    private final Read<T> read;
    private final Consumer<T> show;
    private final Consumer<Throwable> fail;
    private final List<Runnable> unsubscribe = new ArrayList<>();
    private boolean running;
    private boolean again;
    /** Whether the running read is not to be shown, since what it read for no longer applies. */
    private boolean cancelled;
    private CompletableFuture<Void> current = CompletableFuture.completedFuture(null);
    private boolean disposed;

    /** {@code show} and {@code fail} run on the Swing thread with what a read returned or why it failed. */
    public PageLoader(Read<T> read, Consumer<T> show, Consumer<Throwable> fail) {
        this.read = Objects.requireNonNull(read, "read");
        this.show = Objects.requireNonNull(show, "show");
        this.fail = Objects.requireNonNull(fail, "fail");
    }

    /** Loads whenever {@code page} becomes visible, since what it shows may have changed while it was hidden. */
    public PageLoader<T> whenShown(JComponent page) {
        page.addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && page.isShowing()) load();
        });
        return this;
    }

    /**
     * Loads whenever a source changes. {@code subscribe} adds a listener to the source and returns what removes it, as
     * {@code catalog::addListener} does; the listener may be called on any thread.
     */
    public PageLoader<T> follow(Function<Runnable, Runnable> subscribe) {
        this.unsubscribe.add(subscribe.apply(() -> SwingUtilities.invokeLater(this::load)));
        return this;
    }

    /** Reads again, now or once the running read has finished. */
    public void load() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::load);
            return;
        }
        if (this.disposed) return;
        if (this.running) {
            this.again = true;
            return;
        }
        Callable<T> task = this.read.prepare();
        if (task == null) return;
        this.running = true;
        this.cancelled = false;
        CompletableFuture<Void> finished = new CompletableFuture<>();
        this.current = finished;
        CompletableFuture.supplyAsync(() -> {
            try {
                return task.call();
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }).whenComplete((value, failure) -> SwingUtilities.invokeLater(() -> {
            this.running = false;
            try {
                if (this.disposed) return;
                if (this.again) {
                    // A newer request came in while this read ran; its read replaces this one unseen.
                    this.again = false;
                    load();
                    return;
                }
                if (this.cancelled) return;
                if (failure == null) this.show.accept(value);
                else this.fail.accept(failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure);
            } finally {
                finished.complete(null);
            }
        }));
    }

    /** The read that runs or ran last, which completes once it has been shown, replaced or dropped. */
    public CompletableFuture<?> current() {
        return this.current;
    }

    /** Drops the running read and any request waiting for it, as when the page moved on to something else. */
    public void cancel() {
        this.again = false;
        this.cancelled = true;
    }

    /** Stops loading and removes the listeners on the sources it followed. */
    public void dispose() {
        this.disposed = true;
        this.unsubscribe.forEach(Runnable::run);
        this.unsubscribe.clear();
    }
}
