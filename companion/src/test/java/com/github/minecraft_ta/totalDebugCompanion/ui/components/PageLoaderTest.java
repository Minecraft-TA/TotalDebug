package com.github.minecraft_ta.totalDebugCompanion.ui.components;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static com.github.minecraft_ta.totalDebugCompanion.testui.UiTestScope.onEdt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PageLoaderTest {
    private final List<Runnable> source = new CopyOnWriteArrayList<>();
    private final AtomicInteger prepared = new AtomicInteger();

    @Test
    void requestsDuringAReadReadOnceMoreAndOnlyThatIsShown() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger running = new AtomicInteger();
        AtomicInteger overlapping = new AtomicInteger();
        List<Integer> shown = new CopyOnWriteArrayList<>();
        PageLoader<Integer> loader = new PageLoader<>(() -> () -> {
            if (running.incrementAndGet() > 1) overlapping.incrementAndGet();
            int read = reads.incrementAndGet();
            if (read == 1) release.await(5, TimeUnit.SECONDS);
            running.decrementAndGet();
            return read;
        }, value -> {
            shown.add(value);
            done.countDown();
        }, failure -> { });

        SwingUtilities.invokeAndWait(() -> {
            loader.load();
            loader.load();
            loader.load();
        });
        release.countDown();

        assertTrue(done.await(5, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(List.of(2), shown, "three requests during one read make one more read; the first may predate them and is not shown");
        assertEquals(2, reads.get());
        assertEquals(0, overlapping.get(), "reads never run at the same time");
    }

    @Test
    void aCancelledReadIsNotShown() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        List<String> shown = new CopyOnWriteArrayList<>();
        PageLoader<String> loader = new PageLoader<>(() -> () -> {
            release.await(5, TimeUnit.SECONDS);
            return "old file";
        }, shown::add, failure -> { });

        SwingUtilities.invokeAndWait(() -> {
            loader.load();
            loader.cancel();
        });
        release.countDown();
        loader.current().get(5, TimeUnit.SECONDS);
        SwingUtilities.invokeAndWait(() -> { });

        assertEquals(List.of(), shown, "the page moved on to another file while the old one was read");
    }

    @Test
    void aFailureIsReportedWithItsCauseAndNothingIsShownAfterDisposing() throws Exception {
        CountDownLatch failed = new CountDownLatch(1);
        AtomicReference<Throwable> cause = new AtomicReference<>();
        PageLoader<String> failing = new PageLoader<>(() -> () -> {
            throw new IOException("level.dat is locked");
        }, value -> { }, failure -> {
            cause.set(failure);
            failed.countDown();
        });
        SwingUtilities.invokeAndWait(failing::load);
        assertTrue(failed.await(5, TimeUnit.SECONDS));
        assertEquals("level.dat is locked", cause.get().getMessage(), "the read's own exception, not its wrapper");

        AtomicInteger shows = new AtomicInteger();
        AtomicInteger listeners = new AtomicInteger();
        PageLoader<String> disposed = new PageLoader<String>(() -> () -> "read", value -> shows.incrementAndGet(), failure -> { })
                .follow(listener -> {
                    listeners.incrementAndGet();
                    return listeners::decrementAndGet;
                });
        assertEquals(1, listeners.get());
        SwingUtilities.invokeAndWait(() -> {
            disposed.load();
            disposed.dispose();
        });
        SwingUtilities.invokeAndWait(() -> { });
        Thread.sleep(100);
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(0, shows.get(), "a read that finishes after the page closed shows nothing");
        assertEquals(0, listeners.get(), "disposing removes the listeners on the sources");
    }


    @Test
    void aChangeWhileThePageIsHiddenIsReadOnceItIsShown() throws Exception {
        ShowablePage page = new ShowablePage();
        PageLoader<String> loader = onEdt(() -> loader().waitsWhileHidden(page).follow(subscribe()));

        changed();
        assertEquals(0, this.prepared.get(), "a hidden page does not read");
        show(page, loader, true);
        assertEquals(1, this.prepared.get(), "shown again, it reads the change it missed");
        show(page, loader, false);
        show(page, loader, true);
        assertEquals(1, this.prepared.get(), "shown again without a change, it reads nothing");

        changed();
        settle(loader);
        assertEquals(2, this.prepared.get(), "a shown page reads a change at once");
    }

    @Test
    void aPageReadWheneverShownWaitsWhileHidden() throws Exception {
        ShowablePage page = new ShowablePage();
        PageLoader<String> loader = onEdt(() -> loader().whenShown(page).follow(subscribe()));

        changed();
        assertEquals(0, this.prepared.get(), "a hidden page does not read");
        show(page, loader, true);
        show(page, loader, false);
        show(page, loader, true);
        assertEquals(2, this.prepared.get(), "it reads every time it is shown, for what changed without telling");
    }

    @Test
    void aPartOfThePageReadsWhenItIsChosenAndChangesFollowWhileThePageIsShown() throws Exception {
        ShowablePage page = new ShowablePage();
        ShowablePage tab = new ShowablePage();
        PageLoader<String> loader = onEdt(() -> loader().waitsWhileHidden(page).readsWhenShown(tab).follow(subscribe()));

        show(page, loader, true);
        changed();
        settle(loader);
        assertEquals(1, this.prepared.get(), "a change reads while the page is shown, though its tab is not chosen");
        show(tab, loader, true);
        assertEquals(2, this.prepared.get(), "choosing the tab reads what changed without telling, such as a folder");

        // Hidden with its tab, a change missed, and shown again with its tab in one event: one read.
        SwingUtilities.invokeAndWait(() -> {
            page.setShown(false);
            tab.setShown(false);
        });
        changed();
        SwingUtilities.invokeAndWait(() -> {
            page.setShown(true);
            tab.setShown(true);
        });
        settle(loader);
        assertEquals(3, this.prepared.get(), "the page and its tab shown together read once");
    }

    @Test
    void aLoaderWithoutAPageReadsEveryChange() throws Exception {
        PageLoader<String> loader = onEdt(() -> loader().follow(subscribe()));
        changed();
        settle(loader);
        assertEquals(1, this.prepared.get());
    }

    private PageLoader<String> loader() {
        return new PageLoader<>(() -> {
            this.prepared.incrementAndGet();
            return () -> "read";
        }, read -> { }, failure -> { });
    }

    private Function<Runnable, Runnable> subscribe() {
        return listener -> {
            this.source.add(listener);
            return () -> this.source.remove(listener);
        };
    }

    private void changed() throws Exception {
        this.source.forEach(Runnable::run);
        SwingUtilities.invokeAndWait(() -> { });
    }

    private static void show(ShowablePage page, PageLoader<?> loader, boolean shown) throws Exception {
        SwingUtilities.invokeAndWait(() -> page.setShown(shown));
        settle(loader);
    }

    /** Waits for the running read and for it to be shown. */
    private static void settle(PageLoader<?> loader) throws Exception {
        loader.current().get(5, TimeUnit.SECONDS);
        SwingUtilities.invokeAndWait(() -> { });
    }
}
