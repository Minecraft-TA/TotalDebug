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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PageLoaderTest {
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
}
