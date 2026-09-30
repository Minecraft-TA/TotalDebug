package com.github.minecraft_ta.totalDebugCompanion.ui.components;

import com.github.minecraft_ta.totalDebugCompanion.util.Signal;

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
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Reads what a page shows off the Swing thread and shows it on that thread (docs/SYSTEMS.md, section 3). One read runs
 * at a time: asking again while one runs reads once more when it finishes, and the older read is not shown, since it may
 * predate what the new request is about, such as a write. So a page shows only what its last request read, and the same
 * files are never read twice at once.
 *
 * <p>A page names itself ({@link #page}) and the signals it follows ({@link #follows}); the loader reads when the page is
 * first shown, and after a followed signal fires, at once while the page is shown, otherwise once it is shown again. A
 * page shown again with nothing changed reads nothing, unless its last read failed. While the page holds its reads
 * ({@link #hold}), as during a save, signals wait in the same way. The page never reads in its constructor or because a
 * navigation showed it.</p>
 *
 * <p>Pages not moved to {@link #page} yet use the older modes {@link #whenShown}, {@link #waitsWhileHidden} and
 * {@link #readsWhenShown} with {@link #follow}, and read in their constructors; the last of them to move deletes those.</p>
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
    /**
     * What the page missed while it was hidden or held: for each followed change, whether it concerns the page, asked when
     * the page would read. Empty when there is nothing to read.
     */
    private final List<BooleanSupplier> missed = new ArrayList<>();
    /** Whether the page holds its reads, as while it saves. */
    private boolean held;
    /** How many reads started. */
    private int reads;
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
     * Reads for {@code page}: when it is first shown, and after a followed signal fired, once it is shown. It may be a part
     * of a larger page, which then reads when that part is chosen.
     */
    public PageLoader<T> page(JComponent page) {
        this.page = Objects.requireNonNull(page, "page");
        this.missed.add(() -> true);
        watch(page, false);
        if (page.isShowing()) SwingUtilities.invokeLater(this::resume);
        return this;
    }

    /** Reads again whenever {@code signal} fires. */
    public PageLoader<T> follows(Signal signal) {
        return follows(signal, () -> true);
    }

    /**
     * Reads again when {@code signal} fires and {@code concerns} says the change is one the page shows, such as the entry
     * of its own file among all the changes of the record; {@code concerns} is asked on the Swing thread when the page
     * would read, so a change the page made itself meanwhile does not count.
     */
    public PageLoader<T> follows(Signal signal, BooleanSupplier concerns) {
        Objects.requireNonNull(concerns, "concerns");
        this.unsubscribe.add(signal.subscribe(() -> SwingUtilities.invokeLater(() -> changed(concerns))));
        return this;
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

    /** Reads when {@code component} is shown: {@code always}, or only what the page missed. */
    private PageLoader<T> watch(JComponent component, boolean always) {
        HierarchyListener listener = event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0 || !component.isShowing()) return;
            if (!always && this.missed.isEmpty() || this.showReadQueued) return;
            this.showReadQueued = true;
            SwingUtilities.invokeLater(() -> {
                this.showReadQueued = false;
                if (always) load();
                else resume();
            });
        };
        component.addHierarchyListener(listener);
        this.unwatch.add(() -> component.removeHierarchyListener(listener));
        return this;
    }

    /**
     * Loads whenever a source changes, or once the page is shown again. {@code subscribe} adds a listener to the source
     * and returns what removes it, as {@code catalog.changed()::subscribe} does; the listener may be called on any thread.
     */
    public PageLoader<T> follow(Function<Runnable, Runnable> subscribe) {
        this.unsubscribe.add(subscribe.apply(() -> SwingUtilities.invokeLater(() -> changed(() -> true))));
        return this;
    }

    /** A followed source changed: loads now where it concerns the page, or notes it while the page is hidden or held. */
    private void changed(BooleanSupplier concerns) {
        if (this.disposed) return;
        if (waiting()) {
            this.missed.add(concerns);
            return;
        }
        if (concerns.getAsBoolean()) load();
    }

    private boolean waiting() {
        return this.held || this.page != null && !this.page.isShowing();
    }

    /** Reads what the page missed, now that it is shown and not held. */
    private void resume() {
        if (this.disposed || waiting() || this.missed.isEmpty()) return;
        boolean concerned = false;
        for (BooleanSupplier concerns : this.missed) concerned |= concerns.getAsBoolean();
        this.missed.clear();
        if (concerned) load();
    }

    /**
     * Holds reads until {@link #release()}, as while the page writes what it shows: a read under way is not shown, since it
     * may predate the write, and followed changes wait as while the page is hidden. Swing thread only.
     */
    public void hold() {
        this.held = true;
        if (this.running || this.again) {
            // What that read was for is read again once released.
            this.missed.add(() -> true);
            cancel();
        }
    }

    /** Ends {@link #hold()}, reading what the page missed meanwhile. Swing thread only. */
    public void release() {
        this.held = false;
        resume();
    }

    /** Reads again, now or once the running read has finished; while held, once released. */
    public void load() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::load);
            return;
        }
        if (this.disposed) return;
        if (this.held) {
            this.missed.add(() -> true);
            return;
        }
        this.missed.clear();
        if (this.running) {
            this.again = true;
            return;
        }
        Callable<T> task = this.read.prepare();
        if (task == null) return;
        this.running = true;
        this.cancelled = false;
        this.reads++;
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
        if (failure == null) {
            this.show.accept(value);
            return;
        }
        // A page shown again after a failed read tries once more, as when the file was being written.
        if (this.page != null) this.missed.add(() -> true);
        this.fail.accept(failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure);
    }

    /** The read that runs or ran last, which completes when the read has finished, before it is shown. */
    public CompletableFuture<?> current() {
        return this.current;
    }

    /** How many reads started, which tests count to show that a page reads once where it should. Swing thread only. */
    public int reads() {
        return this.reads;
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
