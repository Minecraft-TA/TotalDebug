package com.github.minecraft_ta.totalDebugCompanion.search.reference;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceLocation;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceQuery;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceSearchPhase;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceSearchProgress;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceSearchResult;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferenceSearchServiceTest {
    @Test
    void deliversProgressAndCompletionOnTheSwingEventThread() throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicBoolean progressOnEdt = new AtomicBoolean();
        AtomicBoolean completionOnEdt = new AtomicBoolean();
        ReferenceSearchResult expected = new ReferenceSearchResult(
                List.of(ReferenceLocation.method("example.Use", "run", "()V")),
                1,
                1,
                false
        );

        try (var service = new ReferenceSearchService((query, monitor) -> {
            monitor.onProgress(new ReferenceSearchProgress(ReferenceSearchPhase.SCANNING_REFERENCES, 1, 1));
            return expected;
        })) {
            service.search(ReferenceQuery.classReference("example.Target"), new ReferenceSearchService.Listener() {
                @Override
                public void onProgress(ReferenceSearchProgress progress) {
                    progressOnEdt.set(SwingUtilities.isEventDispatchThread());
                }

                @Override
                public void onCompleted(ReferenceSearchResult result) {
                    assertEquals(expected, result);
                    completionOnEdt.set(SwingUtilities.isEventDispatchThread());
                    completed.countDown();
                }

                @Override
                public void onFailed(Throwable failure) {
                    throw new AssertionError(failure);
                }
            });

            assertTrue(completed.await(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS));
            assertTrue(progressOnEdt.get());
            assertTrue(completionOnEdt.get());
        }
    }

    @Test
    void aNewSearchCooperativelyCancelsThePreviousOne() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch firstCompleted = new CountDownLatch(1);
        CountDownLatch secondCompleted = new CountDownLatch(1);
        AtomicReference<ReferenceSearchResult> firstResult = new AtomicReference<>();

        try (var service = new ReferenceSearchService((query, monitor) -> {
            if (query instanceof ReferenceQuery.ClassReference type && type.className().equals("example.First")) {
                firstStarted.countDown();
                while (!monitor.isCancelled()) {
                    Thread.onSpinWait();
                }
                return new ReferenceSearchResult(List.of(), 0, 1, true);
            }
            return new ReferenceSearchResult(List.of(), 1, 1, false);
        })) {
            service.search(
                    ReferenceQuery.classReference("example.First"),
                    listener(firstResult, firstCompleted)
            );
            assertTrue(firstStarted.await(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS));

            service.search(
                    ReferenceQuery.classReference("example.Second"),
                    listener(new AtomicReference<>(), secondCompleted)
            );

            assertTrue(firstCompleted.await(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS));
            assertTrue(secondCompleted.await(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS));
            assertTrue(firstResult.get().cancelled());
        }
    }

    private static ReferenceSearchService.Listener listener(
            AtomicReference<ReferenceSearchResult> result,
            CountDownLatch completed
    ) {
        return new ReferenceSearchService.Listener() {
            @Override
            public void onProgress(ReferenceSearchProgress progress) {
            }

            @Override
            public void onCompleted(ReferenceSearchResult value) {
                result.set(value);
                completed.countDown();
            }

            @Override
            public void onFailed(Throwable failure) {
                throw new AssertionError(failure);
            }
        };
    }
}
