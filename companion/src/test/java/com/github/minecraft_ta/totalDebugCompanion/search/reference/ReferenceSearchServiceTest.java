package com.github.minecraft_ta.totalDebugCompanion.search.reference;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceLocation;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceUsagePage;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceQuery;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.editors.UsagesViewPanel;
import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.CodeSymbol;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceUsage;
import com.github.tth05.jindex.ReferenceKind;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferenceSearchServiceTest {
    @Test
    void disposingAnUnattachedUsagePanelCancelsItsSearch() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var service = new ReferenceSearchService((query, limit) -> {
            calls.incrementAndGet();
            started.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            return new ReferenceUsagePage(List.of(), false);
        })) {
            var panel = new AtomicReference<UsagesViewPanel>();
            var handle = new AtomicReference<ReferenceSearchService.SearchHandle>();
            SwingUtilities.invokeAndWait(() -> {
                panel.set(new UsagesViewPanel(
                        new CodeSymbol.ClassSymbol("example.Target"), service, ignored -> {}));
                panel.get().restartSearch();
                try {
                    var field = panel.get().getClass().getDeclaredField("activeSearch");
                    field.setAccessible(true);
                    handle.set((ReferenceSearchService.SearchHandle) field.get(panel.get()));
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                panel.get().dispose();
                // Cancellation stops delivery; it does not pretend the running query has finished.
                assertFalse(handle.get().isDone());
                try {
                    var cancelled = handle.get().getClass().getDeclaredField("cancelled");
                    cancelled.setAccessible(true);
                    assertTrue(((AtomicBoolean) cancelled.get(handle.get())).get());
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                panel.get().restartSearch();
            });
            assertEquals(1, calls.get());
        } finally { release.countDown(); }
    }

    @Test
    void deliversBoundedResultsOnTheSwingEventThread() throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicBoolean completionOnEdt = new AtomicBoolean();
        AtomicInteger receivedLimit = new AtomicInteger();
        ReferenceUsagePage expected = new ReferenceUsagePage(
                List.of(usage(ReferenceLocation.method("example.Use", "run", "()V"))),
                true
        );

        try (var service = new ReferenceSearchService((query, limit) -> {
            receivedLimit.set(limit);
            return expected;
        })) {
            service.search(
                    ReferenceQuery.classReference("example.Target"),
                    200,
                    listener(result -> {
                        assertEquals(expected, result);
                        completionOnEdt.set(SwingUtilities.isEventDispatchThread());
                        completed.countDown();
                    })
            );

            assertTrue(completed.await(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS));
            assertEquals(200, receivedLimit.get());
            assertTrue(completionOnEdt.get());
        }
    }

    @Test
    void aNewSearchSuppressesThePreviousResult() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch firstCompleted = new CountDownLatch(1);
        CountDownLatch secondCompleted = new CountDownLatch(1);
        AtomicReference<ReferenceUsagePage> secondResult = new AtomicReference<>();

        try (var service = new ReferenceSearchService((query, limit) -> {
            if (query instanceof ReferenceQuery.ClassReference type && type.className().equals("example.First")) {
                firstStarted.countDown();
                try {
                    if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Timed out waiting to release the first query");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            }
            return new ReferenceUsagePage(
                    List.of(usage(ReferenceLocation.classDeclaration(
                            ((ReferenceQuery.ClassReference) query).className()
                    ))),
                    false
            );
        })) {
            service.search(
                    ReferenceQuery.classReference("example.First"),
                    200,
                    listener(ignored -> firstCompleted.countDown())
            );
            assertTrue(firstStarted.await(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS));

            service.search(
                    ReferenceQuery.classReference("example.Second"),
                    200,
                    listener(result -> {
                        secondResult.set(result);
                        secondCompleted.countDown();
                    })
            );
            releaseFirst.countDown();

            assertTrue(secondCompleted.await(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS));
            assertFalse(firstCompleted.await(100, TimeUnit.MILLISECONDS));
            assertEquals(
                    List.of(ReferenceLocation.classDeclaration("example.Second")),
                    secondResult.get().usages().stream().map(ReferenceUsage::location).toList()
            );
        }
    }

    @Test
    void rejectsNonPositiveLimits() {
        try (var service = new ReferenceSearchService((query, limit) -> new ReferenceUsagePage(List.of(), false))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> service.search(ReferenceQuery.classReference("example.Target"), 0, listener(ignored -> {
                    }))
            );
        }
    }

    private static ReferenceSearchService.Listener listener(java.util.function.Consumer<ReferenceUsagePage> result) {
        return new ReferenceSearchService.Listener() {
            @Override
            public void onCompleted(ReferenceUsagePage value) {
                result.accept(value);
            }

            @Override
            public void onFailed(Throwable failure) {
                throw new AssertionError(failure);
            }
        };
    }

    private static ReferenceUsage usage(ReferenceLocation location) {
        return new ReferenceUsage(1, location, 0, java.util.Set.of(ReferenceKind.METHOD_INVOKE), 1);
    }
}
