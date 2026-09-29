package com.github.minecraft_ta.totalDebugCompanion.ui.components;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
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
 * read, and the same files are never read twice at once. A page loads whenever a source it follows changes; with a page
 * named, a change while the page is hidden loads once it is shown, so hidden pages do no work for what they cannot show.
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
    /** The page whose visibility decides when a followed change loads, or null to load at once. */
    private JComponent page;
    /** Whether a followed source changed while the page was hidden. */
    private boolean stale;
    /** Removes the listeners on the components whose showing this loader watches. */
    private final List<Runnable> unwatch = new ArrayList<>();
    /**
     * Whether a read for a component being shown waits for the end of the Swing step. Showing a page shows the part of it
     * chosen in the same step, and both read once.
     */
    private boolean showReadQueued;

    /** {@code show} and {@code fail} run on the Swing thread with what a read returned or why it failed. */
    public PageLoader(Read<T> read, Consumer<T> show, Consumer<Throwable> fail) {
        this.read = Objects.requireNonNull(read, "read");
        this.show = Objects.requireNonNull(show, "show");
        this.fail = Objects.requireNonNull(fail, "fail");
    }

    /**
     * Waits while {@code page} is hidden and reads every time it is shown, since what it shows may have changed while it
     * was hidden without a source telling, such as a file the game writes.
     */
    public PageLoader<T> whenShown(JComponent page) {
        this.page = Objects.requireNonNull(page, "page");
        return readsWhenShown(page);
    }

    /**
     * Waits while {@code page} is hidden: a followed change then reads once the page is shown again, and not at all when
     * none came. For pages whose sources tell every change.
     */
    public PageLoader<T> waitsWhileHidden(JComponent page) {
        this.page = Objects.requireNonNull(page, "page");
        return watch(page, false);
    }

    /**
     * Reads every time {@code component} is shown, such as a tab listing files that change without telling. It may be a
     * part of the page, which then reads when that part is chosen.
     */
    public PageLoader<T> readsWhenShown(JComponent component) {
        return watch(component, true);
    }

    /** Reads when {@code component} is shown: {@code always}, or only after a change it missed. */
    private PageLoader<T> watch(JComponent component, boolean always) {
        HierarchyListener listener = event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0 || !component.isShowing()) return;
            if (!always && !this.stale || this.showReadQueued) return;
            this.showReadQueued = true;
            SwingUtilities.invokeLater(() -> {
                this.showReadQueued = false;
                load();
            });
        };
        component.addHierarchyListener(listener);
        this.unwatch.add(() -> component.removeHierarchyListener(listener));
        return this;
    }

    /**
     * Loads whenever a source changes, or once the page is shown again. {@code subscribe} adds a listener to the source
     * and returns what removes it, as {@code catalog::addListener} does; the listener may be called on any thread.
     */
    public PageLoader<T> follow(Function<Runnable, Runnable> subscribe) {
        this.unsubscribe.add(subscribe.apply(() -> SwingUtilities.invokeLater(this::changed)));
        return this;
    }

    /** A followed source changed: loads now, or marks a hidden page to load when shown. */
    private void changed() {
        if (this.page != null && !this.page.isShowing()) {
            this.stale = true;
            return;
        }
        load();
    }

    /** Reads again, now or once the running read has finished. */
    public void load() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::load);
            return;
        }
        if (this.disposed) return;
        this.stale = false;
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
        }).whenComplete((value, failure) -> {
            // Completed off the Swing thread, so a wait on the Swing thread does not block what it waits for.
            finished.complete(null);
            SwingUtilities.invokeLater(() -> showRead(value, failure));
        });
    }

    private void showRead(T value, Throwable failure) {
        this.running = false;
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
    }

    /** The read that runs or ran last, which completes when the read has finished, before it is shown. */
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
        // The page may outlive the loader, as a subject page outlives the details of a definition it replaced.
        this.unwatch.forEach(Runnable::run);
        this.unwatch.clear();
    }
}
