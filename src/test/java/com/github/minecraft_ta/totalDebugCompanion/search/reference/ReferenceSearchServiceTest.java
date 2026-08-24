package com.github.minecraft_ta.totalDebugCompanion.search.reference;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceLocation;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceLocationPage;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceQuery;
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
    void deliversBoundedResultsOnTheSwingEventThread() throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicBoolean completionOnEdt = new AtomicBoolean();
        AtomicInteger receivedLimit = new AtomicInteger();
        ReferenceLocationPage expected = new ReferenceLocationPage(
                List.of(ReferenceLocation.method("example.Use", "run", "()V")),
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
        AtomicReference<ReferenceLocationPage> secondResult = new AtomicReference<>();

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
            return new ReferenceLocationPage(
                    List.of(ReferenceLocation.classDeclaration(
                            ((ReferenceQuery.ClassReference) query).className()
                    )),
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
                    secondResult.get().locations()
            );
        }
    }

    @Test
    void rejectsNonPositiveLimits() {
        try (var service = new ReferenceSearchService((query, limit) -> new ReferenceLocationPage(List.of(), false))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> service.search(ReferenceQuery.classReference("example.Target"), 0, listener(ignored -> {
                    }))
            );
        }
    }

    private static ReferenceSearchService.Listener listener(java.util.function.Consumer<ReferenceLocationPage> result) {
        return new ReferenceSearchService.Listener() {
            @Override
            public void onCompleted(ReferenceLocationPage value) {
                result.accept(value);
            }

            @Override
            public void onFailed(Throwable failure) {
                throw new AssertionError(failure);
            }
        };
    }
}
