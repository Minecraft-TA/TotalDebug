package com.github.minecraft_ta.totalDebugCompanion.ui.components;

import com.github.minecraft_ta.totalDebugCompanion.util.Signal;
import com.github.minecraft_ta.totalDebugCompanion.util.UIUtils;
import com.github.minecraft_ta.totalDebugCompanion.util.WindowFocus;
import com.github.minecraft_ta.totalDebugCompanion.util.Workers;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

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
 * navigation showed it. A page whose files others write and only it reads, such as the logs, reads whenever it is shown
 * and when the user comes back to Companion while it is shown ({@link #readsWhenShown}); work that only redraws from
 * memory waits the same way ({@link #updates}).</p>
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
    /** A change the page reads whatever it is, such as its first read or one it asked for. */
    private static final BooleanSupplier ALWAYS = () -> true;

    /**
     * What the page missed while it was hidden or held: for each followed source that changed, whether that concerns the
     * page, asked when the page would read; once per source however often it changed. Empty when there is nothing to read.
     */
    private final Set<BooleanSupplier> missed = new LinkedHashSet<>();
    /** The followed signals fired since the last read started, which that read's preparation may ask about. */
    private final Set<Signal> fired = new HashSet<>();
    /** Whether the page holds its reads, as while it saves. */
    private boolean held;
    /** Whether no read runs while the page is hidden, whoever asks for it: set by {@link #page}. */
    private boolean waitsForShow;
    /** How many reads started. */
    private int reads;
    /** Removes the listeners on the components whose showing this loader watches. */
    private final List<Runnable> unwatch = new ArrayList<>();
    /**
     * Whether a read for a component being shown waits for the end of the Swing step. Showing a page shows the part of it
     * chosen in the same step, and both read once.
     */
    private boolean showReadQueued;

    /** A loader for {@code page}, which reads nothing and only redraws from memory ({@link #updates}). */
    public static PageLoader<Void> redraws(JComponent page) {
        return new PageLoader<Void>(() -> null, nothing -> { }, failure -> { }).page(page);
    }

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
        this.waitsForShow = true;
        this.missed.add(ALWAYS);
        watch(page, false);
        if (page.isShowing()) SwingUtilities.invokeLater(this::resume);
        return this;
    }

    /** Reads again whenever {@code signal} fires. */
    public PageLoader<T> follows(Signal signal) {
        return follows(signal, ALWAYS);
    }

    /**
     * Reads again when {@code signal} fires and {@code concerns} says the change is one the page shows, such as the entry
     * of its own file among all the changes of the record; {@code concerns} is asked on the Swing thread when the page
     * would read, so a change the page made itself meanwhile does not count.
     */
    public PageLoader<T> follows(Signal signal, BooleanSupplier concerns) {
        Objects.requireNonNull(concerns, "concerns");
        this.unsubscribe.add(signal.subscribe(() -> SwingUtilities.invokeLater(() -> {
            this.fired.add(signal);
            changed(concerns);
        })));
        return this;
    }

    /**
     * Whether {@code signal} fired since the last read started, for {@link Read#prepare} to tell what a read is for, such
     * as a pack change that may move a tab to another pack, as against an edit elsewhere. Swing thread only.
     */
    public boolean fired(Signal signal) {
        return this.fired.contains(signal);
    }

    /**
     * Reads every time {@code component} is shown, and when the user comes back to Companion from another program while it
     * is shown, such as a tab listing files that change without telling. It may be a part of the page, which then reads
     * when that part is chosen.
     */
    public PageLoader<T> readsWhenShown(JComponent component) {
        this.unsubscribe.add(WindowFocus.returned().subscribe(() -> UIUtils.onEdt(() -> {
            if (!this.disposed) readShown(component, true);
        })));
        return watch(component, true);
    }

    /** Reads when {@code component} is shown: {@code always}, or only what the page missed. */
    private PageLoader<T> watch(JComponent component, boolean always) {
        HierarchyListener listener = event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) readShown(component, always);
        };
        component.addHierarchyListener(listener);
        this.unwatch.add(() -> component.removeHierarchyListener(listener));
        return this;
    }

    /**
     * Reads at the end of the Swing step while {@code component} is shown: {@code always}, or only what the page missed.
     * Whatever asks in the same step, as the window shown again and taking the focus, reads once. Swing thread.
     */
    private void readShown(JComponent component, boolean always) {
        if (!component.isShowing() || !always && this.missed.isEmpty() || this.showReadQueued) return;
        this.showReadQueued = true;
        SwingUtilities.invokeLater(() -> {
            this.showReadQueued = false;
            if (always) load();
            else resume();
        });
    }

    /**
     * Runs {@code update}, which only redraws from memory, such as icons again after new ones came, when {@code signal}
     * fires: at once while the page is shown, otherwise once it is shown again, once however often it fired. Swing thread.
     */
    public PageLoader<T> updates(Signal signal, Runnable update) {
        Objects.requireNonNull(update, "update");
        boolean[] missed = {false};
        this.unsubscribe.add(signal.subscribe(() -> SwingUtilities.invokeLater(() -> {
            if (this.disposed) return;
            if (this.page != null && !this.page.isShowing()) missed[0] = true;
            else update.run();
        })));
        if (this.page != null) {
            JComponent shown = this.page;
            HierarchyListener listener = event -> {
                if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0 || !shown.isShowing() || !missed[0]) return;
                missed[0] = false;
                if (!this.disposed) update.run();
            };
            shown.addHierarchyListener(listener);
            this.unwatch.add(() -> shown.removeHierarchyListener(listener));
        }
        return this;
    }

    /**
     * Runs {@code redraw} on the Swing thread whenever {@code signal} fires, shown or not, for what shows outside the page
     * from memory, such as the title of its tab.
     */
    public PageLoader<T> retitles(Signal signal, Runnable redraw) {
        Objects.requireNonNull(redraw, "redraw");
        this.unsubscribe.add(signal.subscribe(() -> SwingUtilities.invokeLater(() -> {
            if (!this.disposed) redraw.run();
        })));
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
            this.missed.add(ALWAYS);
            cancel();
        }
    }

    /** Ends {@link #hold()}, reading what the page missed meanwhile. Swing thread only. */
    public void release() {
        this.held = false;
        resume();
    }

    /**
     * Reads again, now or once the running read has finished. A page named with {@link #page} reads only while it is
     * shown and not held, whoever asks, the page itself too, as to check a read against a later change: otherwise it reads
     * once it is shown and released.
     */
    public void load() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::load);
            return;
        }
        if (this.disposed) return;
        if (this.held || this.waitsForShow && !this.page.isShowing()) {
            this.missed.add(ALWAYS);
            return;
        }
        this.missed.clear();
        if (this.running) {
            this.again = true;
            return;
        }
        Callable<T> task = this.read.prepare();
        this.fired.clear();
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
        }, Workers.files()).whenComplete((value, failure) -> {
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
        if (this.page != null) this.missed.add(ALWAYS);
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
