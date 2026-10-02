package com.github.minecraft_ta.totalDebugCompanion.ui.components;

import java.util.concurrent.atomic.AtomicBoolean;
import com.github.minecraft_ta.totalDebugCompanion.util.Signal;
import com.github.minecraft_ta.totalDebugCompanion.util.WindowFocus;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.minecraft_ta.totalDebugCompanion.testui.UiTestScope.onEdt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PageLoaderTest {
    private final Signal source = new Signal();
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
        PageLoader<String> disposed = new PageLoader<String>(() -> () -> "read", value -> shows.incrementAndGet(), failure -> { })
                .follows(this.source);
        SwingUtilities.invokeAndWait(() -> {
            disposed.load();
            disposed.dispose();
        });
        SwingUtilities.invokeAndWait(() -> { });
        Thread.sleep(100);
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(0, shows.get(), "a read that finishes after the page closed shows nothing");
        changed();
        assertEquals(1, (int) onEdt(disposed::reads), "a disposed page follows its signals no more");
    }


    @Test
    void aChangeWhileThePageIsHiddenIsReadOnceItIsShown() throws Exception {
        ShowablePage page = new ShowablePage();
        PageLoader<String> loader = onEdt(() -> loader().page(page).follows(this.source));

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
        PageLoader<String> loader = onEdt(() -> loader().page(page).readsWhenShown(page).follows(this.source));

        changed();
        assertEquals(0, this.prepared.get(), "a hidden page does not read");
        show(page, loader, true);
        show(page, loader, false);
        show(page, loader, true);
        assertEquals(2, this.prepared.get(), "it reads every time it is shown, for what changed without telling");
    }

    @Test
    void aPageReadWheneverShownReadsWhenTheUserComesBackWhileItIsShown() throws Exception {
        ShowablePage page = new ShowablePage();
        PageLoader<String> loader = onEdt(() -> loader().page(page).readsWhenShown(page));
        try {
            fire(WindowFocus.returned());
            assertEquals(0, this.prepared.get(), "a hidden page does not read");
            show(page, loader, true);
            assertEquals(1, this.prepared.get());

            // An editor wrote the page's file while the user was in it.
            fire(WindowFocus.returned());
            settle(loader);
            assertEquals(2, this.prepared.get(), "coming back reads the shown page again");

            // Shown again and taking the focus in one step, as a window restored from the taskbar.
            SwingUtilities.invokeAndWait(() -> page.setShown(false));
            SwingUtilities.invokeAndWait(() -> {
                page.setShown(true);
                WindowFocus.returned().fire();
            });
            SwingUtilities.invokeAndWait(() -> { });
            settle(loader);
            assertEquals(3, this.prepared.get(), "showing and coming back at once read once");

            onEdt(loader::dispose);
            fire(WindowFocus.returned());
            assertEquals(3, this.prepared.get(), "a disposed page reads no more");
        } finally {
            onEdt(loader::dispose);
        }
    }

    @Test
    void aPartOfThePageReadsWhenItIsChosenAndChangesFollowWhileThePageIsShown() throws Exception {
        ShowablePage page = new ShowablePage();
        ShowablePage tab = new ShowablePage();
        PageLoader<String> loader = onEdt(() -> loader().page(page).readsWhenShown(tab).follows(this.source));

        show(page, loader, true);
        assertEquals(1, this.prepared.get(), "the page reads when first shown");
        changed();
        settle(loader);
        assertEquals(2, this.prepared.get(), "a change reads while the page is shown, though its tab is not chosen");
        show(tab, loader, true);
        assertEquals(3, this.prepared.get(), "choosing the tab reads what changed without telling, such as a folder");

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
        assertEquals(4, this.prepared.get(), "the page and its tab shown together read once");
    }

    @Test
    void aLoaderWithoutAPageReadsEveryChange() throws Exception {
        PageLoader<String> loader = onEdt(() -> loader().follows(this.source));
        changed();
        settle(loader);
        assertEquals(1, this.prepared.get());
    }

    @Test
    void aPageReadsWhenFirstShownAndThenOnlyAfterItsSignals() throws Exception {
        ShowablePage page = new ShowablePage();
        Signal signal = new Signal();
        PageLoader<String> loader = onEdt(() -> loader().page(page).follows(signal));

        assertEquals(0, this.prepared.get(), "a page does not read before it is shown");
        show(page, loader, true);
        assertEquals(1, this.prepared.get(), "shown, it reads");
        show(page, loader, false);
        show(page, loader, true);
        assertEquals(1, this.prepared.get(), "shown again without a change, it reads nothing");

        show(page, loader, false);
        fire(signal);
        fire(signal);
        assertEquals(1, this.prepared.get(), "a hidden page does not read");
        show(page, loader, true);
        assertEquals(2, this.prepared.get(), "shown again, it reads what it missed once");

        fire(signal);
        awaitPrepared(3, "a shown page reads a change at once");
    }

    @Test
    void aChangeThatDoesNotConcernThePageReadsNothing() throws Exception {
        ShowablePage page = new ShowablePage();
        Signal signal = new Signal();
        AtomicBoolean concerns = new AtomicBoolean();
        PageLoader<String> loader = onEdt(() -> loader().page(page).follows(signal, concerns::get));
        show(page, loader, true);

        fire(signal);
        settle(loader);
        assertEquals(1, this.prepared.get(), "another file's change, say, leaves the page alone");
        concerns.set(true);
        fire(signal);
        awaitPrepared(2, "a change that concerns the page reads it");
    }

    @Test
    void aHeldPageReadsWhatItMissedOnceReleased() throws Exception {
        ShowablePage page = new ShowablePage();
        Signal signal = new Signal();
        AtomicBoolean concerns = new AtomicBoolean(true);
        PageLoader<String> loader = onEdt(() -> loader().page(page).follows(signal, concerns::get));
        show(page, loader, true);

        SwingUtilities.invokeAndWait(loader::hold);
        fire(signal);
        SwingUtilities.invokeAndWait(loader::load);
        assertEquals(1, this.prepared.get(), "a page holding its reads, as while it saves, reads nothing");
        SwingUtilities.invokeAndWait(loader::release);
        awaitPrepared(2, "released, it reads what it missed once");
        settle(loader);

        // The change came from the page's own save: by the time it is released, the change does not concern it.
        SwingUtilities.invokeAndWait(loader::hold);
        fire(signal);
        concerns.set(false);
        SwingUtilities.invokeAndWait(loader::release);
        settle(loader);
        assertEquals(2, this.prepared.get(), "whether a change concerns the page is asked when it would read");
    }

    @Test
    void aReadUnderWayWhenThePageHoldsIsNotShownAndIsReadAgain() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        List<Integer> shown = new CopyOnWriteArrayList<>();
        PageLoader<Integer> loader = new PageLoader<>(() -> () -> {
            int read = reads.incrementAndGet();
            if (read == 1) release.await(5, TimeUnit.SECONDS);
            return read;
        }, shown::add, failure -> { });

        SwingUtilities.invokeAndWait(() -> {
            loader.load();
            loader.hold();
        });
        release.countDown();
        loader.current().get(5, TimeUnit.SECONDS);
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(List.of(), shown, "a read that may predate the write is not shown");
        SwingUtilities.invokeAndWait(loader::release);
        settle(loader);
        assertEquals(List.of(2), shown, "what it was for is read again after the write");
    }

    @Test
    void aPageWhoseReadFailedReadsAgainWhenShownAgain() throws Exception {
        ShowablePage page = new ShowablePage();
        AtomicBoolean failing = new AtomicBoolean(true);
        AtomicInteger failures = new AtomicInteger();
        PageLoader<String> loader = onEdt(() -> new PageLoader<String>(() -> {
            this.prepared.incrementAndGet();
            return () -> {
                if (failing.get()) throw new IOException("written in parts");
                return "read";
            };
        }, read -> { }, failure -> failures.incrementAndGet()).page(page));

        show(page, loader, true);
        settle(loader);
        assertEquals(1, failures.get());
        failing.set(false);
        show(page, loader, false);
        show(page, loader, true);
        assertEquals(2, this.prepared.get(), "shown again, a page whose read failed tries once more");
        show(page, loader, false);
        show(page, loader, true);
        assertEquals(2, this.prepared.get(), "and once it read, shown again without a change it reads nothing");
    }

    @Test
    void aReadCanTellWhichOfItsSignalsLedToIt() throws Exception {
        ShowablePage page = new ShowablePage();
        Signal packs = new Signal();
        Signal record = new Signal();
        List<String> causes = new CopyOnWriteArrayList<>();
        PageLoader<String>[] loader = new PageLoader[1];
        loader[0] = onEdt(() -> new PageLoader<String>(() -> {
            causes.add((loader[0].fired(packs) ? "packs" : "") + (loader[0].fired(record) ? "record" : ""));
            return () -> "read";
        }, read -> { }, failure -> { }).page(page).follows(packs).follows(record));
        show(page, loader[0], true);
        fire(record);
        settle(loader[0]);
        fire(packs);
        settle(loader[0]);
        assertEquals(List.of("", "record", "packs"), causes, "each read knows what it is for, and no more");
    }

    @Test
    void aPageHiddenWhileItReadsReadsNothingMoreUntilShownWhoeverAsks() throws Exception {
        ShowablePage page = new ShowablePage();
        Signal signal = new Signal();
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        PageLoader<String> loader = onEdt(() -> new PageLoader<String>(() -> {
            this.prepared.incrementAndGet();
            return () -> {
                if (reads.incrementAndGet() == 1) release.await(5, TimeUnit.SECONDS);
                return "read";
            };
        }, read -> { }, failure -> { }).page(page).follows(signal));
        SwingUtilities.invokeAndWait(() -> page.setShown(true));
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(1, this.prepared.get(), "the first read runs");

        SwingUtilities.invokeAndWait(() -> page.setShown(false));
        fire(signal);
        // As a page checking its read against a later change asks for another, and a request during the read does.
        SwingUtilities.invokeAndWait(loader::load);
        release.countDown();
        settle(loader);
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(1, this.prepared.get(), "hidden, the page reads nothing more, whoever asked");

        show(page, loader, true);
        settle(loader);
        assertEquals(2, this.prepared.get(), "shown, it reads once for all that came meanwhile");
    }

    @Test
    void manyChangesWhileHiddenAreAskedAboutOncePerSource() throws Exception {
        ShowablePage page = new ShowablePage();
        Signal signal = new Signal();
        AtomicInteger asked = new AtomicInteger();
        PageLoader<String> loader = onEdt(() -> loader().page(page).follows(signal, () -> {
            asked.incrementAndGet();
            return false;
        }));
        show(page, loader, true);
        show(page, loader, false);
        for (int change = 0; change < 1_000; change++) signal.fire();
        SwingUtilities.invokeAndWait(() -> { });
        show(page, loader, true);
        assertEquals(1, asked.get(), "a hidden page keeps one question per source, however often it changed");
    }

    @Test
    void anUpdateFromMemoryWaitsWhileThePageIsHiddenAndRunsOnceWhenShown() throws Exception {
        AtomicInteger updates = new AtomicInteger();
        ShowablePage page = new ShowablePage();
        PageLoader<Void> redraws = onEdt(() -> PageLoader.withoutRead(page).updates(this.source, updates::incrementAndGet));

        changed();
        changed();
        assertEquals(0, updates.get(), "a hidden page does not update");
        SwingUtilities.invokeAndWait(() -> page.setShown(true));
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(1, updates.get(), "shown, it updates once for the changes it missed");
        SwingUtilities.invokeAndWait(() -> {
            page.setShown(false);
            page.setShown(true);
        });
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(1, updates.get(), "shown again without a change, it does not update");
        changed();
        assertEquals(2, updates.get(), "a shown page updates at once");

        // A change queued before the page closed does not update it.
        SwingUtilities.invokeAndWait(() -> {
            this.source.fire();
            redraws.dispose();
        });
        SwingUtilities.invokeAndWait(() -> { });
        changed();
        assertEquals(2, updates.get());
    }

    @Test
    void workThePageRunsItselfWaitsWhileThePageIsHiddenAndStartsOnceWhenShown() throws Exception {
        AtomicInteger started = new AtomicInteger();
        ShowablePage page = new ShowablePage();
        PageLoader<Void> loader = onEdt(() -> PageLoader.withoutRead(page).starts(this.source, started::incrementAndGet));

        changed();
        changed();
        assertEquals(0, started.get(), "a hidden page starts nothing");
        int[] inShowingStep = {-1};
        SwingUtilities.invokeAndWait(() -> {
            page.setShown(true);
            inShowingStep[0] = started.get();
        });
        assertEquals(0, inShowingStep[0], "the step that shows the page, as a navigation reading it anew, finishes first");
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(1, started.get(), "shown, it starts once for the requests it missed");
        changed();
        assertEquals(2, started.get(), "a shown page starts at once");
        assertEquals(0, onEdt(loader::reads), "the work does not run as the loader's read");

        SwingUtilities.invokeAndWait(() -> page.setShown(false));
        changed();
        onEdt(loader::dispose);
        SwingUtilities.invokeAndWait(() -> page.setShown(true));
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(2, started.get(), "a closed page starts nothing it missed");
    }

    @Test
    void aTitleIsRedrawnWhileThePageIsHidden() throws Exception {
        AtomicInteger titles = new AtomicInteger();
        ShowablePage page = new ShowablePage();
        PageLoader<Void> redraws = onEdt(() -> PageLoader.withoutRead(page).retitles(this.source, titles::incrementAndGet));
        changed();
        assertEquals(1, titles.get(), "the tab shows its title though the page is hidden");
        onEdt(() -> {
            redraws.dispose();
            return null;
        });
        changed();
        assertEquals(1, titles.get());
    }

    /** Waits until {@code expected} reads were prepared, as a read that a slow machine starts late. */
    private void awaitPrepared(int expected, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (this.prepared.get() < expected && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals(expected, this.prepared.get(), message);
    }

    private static void fire(Signal signal) throws Exception {
        signal.fire();
        SwingUtilities.invokeAndWait(() -> { });
    }

    private PageLoader<String> loader() {
        return new PageLoader<>(() -> {
            this.prepared.incrementAndGet();
            return () -> "read";
        }, read -> { }, failure -> { });
    }

    private void changed() throws Exception {
        fire(this.source);
    }

    /** Shows or hides {@code page}, and waits for a read that showing starts, which runs a Swing step later. */
    private static void show(ShowablePage page, PageLoader<?> loader, boolean shown) throws Exception {
        SwingUtilities.invokeAndWait(() -> page.setShown(shown));
        SwingUtilities.invokeAndWait(() -> { });
        settle(loader);
    }

    /** Waits for the running read and for it to be shown. */
    private static void settle(PageLoader<?> loader) throws Exception {
        loader.current().get(5, TimeUnit.SECONDS);
        SwingUtilities.invokeAndWait(() -> { });
    }
}
