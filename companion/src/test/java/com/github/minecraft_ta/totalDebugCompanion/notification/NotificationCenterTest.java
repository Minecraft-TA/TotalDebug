package com.github.minecraft_ta.totalDebugCompanion.notification;

import com.github.minecraft_ta.totalDebugCompanion.notification.NotificationCenter.Severity;
import com.github.minecraft_ta.totalDebugCompanion.notification.NotificationCenter.Source;
import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class NotificationCenterTest {
    @Test void startupReplayPriorityAndAcknowledgementAreIndependentOfHistory() {
        try (var center = new NotificationCenter()) {
            long failure = center.publish(Severity.ERROR, "Run failed", "details", Source.application("Test"));
            long success = center.publish(Severity.SUCCESS, "Formatted", "", Source.application("Test"));
            var snapshot = new AtomicReference<NotificationCenter.Snapshot>();
            var unsubscribe = center.subscribe(snapshot::set);
            assertEquals(2, snapshot.get().unread());
            assertEquals(failure, snapshot.get().preview().id());
            center.acknowledge(Set.of(failure));
            assertEquals(success, snapshot.get().preview().id());
            assertEquals(2, snapshot.get().entries().size());
            unsubscribe.run();
            long version = snapshot.get().revision();
            center.clear();
            assertEquals(version, snapshot.get().revision());
        }
    }

    @Test void saveFailureUpdatesDoNotResurrectDismissedOrReadEntries() {
        try (var center = new NotificationCenter()) {
            long id = center.publish(Severity.ERROR, "Unable to save", "first", Source.application("Script"));
            center.acknowledge(Set.of(id));
            center.update(id, "second");
            assertEquals(0, center.snapshot().unread());
            assertEquals("second", center.snapshot().entries().getFirst().details());
            center.dismiss(id);
            center.update(id, "third");
            assertTrue(center.snapshot().entries().isEmpty());
            long recoveredThenFailed = center.publish(Severity.ERROR, "Unable to save", "new episode", Source.application("Script"));
            assertNotEquals(id, recoveredThenFailed);
            center.clear();
            center.update(recoveredThenFailed, "retry");
            assertTrue(center.snapshot().entries().isEmpty());
        }
    }

    @Test void retentionAndDetailsAreBoundedAndLatePublicationIsIgnored() {
        var center = new NotificationCenter();
        long first = center.publish(Severity.ERROR, "first", "x".repeat(50_000), Source.application("Index"));
        assertTrue(center.snapshot().entries().getFirst().details().endsWith("[Truncated]"));
        for (int i = 0; i < 100; i++) center.publish(Severity.INFORMATION, "entry " + i, "", Source.application("Test"));
        assertEquals(100, center.snapshot().entries().size());
        center.update(first, "evicted");
        assertFalse(center.snapshot().entries().stream().anyMatch(entry -> entry.id() == first));
        center.close();
        assertEquals(0, center.publish(Severity.ERROR, "late", "", Source.application("Test")));
        assertTrue(center.snapshot().entries().isEmpty());
    }

    @Test void concurrentPublishAndSubscribeKeepTheNewestCompleteSnapshot() throws Exception {
        try (var center = new NotificationCenter(); var workers = Executors.newFixedThreadPool(4)) {
            var observed = new AtomicReference<NotificationCenter.Snapshot>();
            var start = new CountDownLatch(1);
            var done = new CountDownLatch(3);
            for (int i = 0; i < 3; i++) workers.submit(() -> {
                try { start.await(); for (int j = 0; j < 20; j++) center.publish(Severity.INFORMATION, "event", "", Source.application("Worker")); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { done.countDown(); }
            });
            start.countDown();
            var unsubscribe = center.subscribe(snapshot -> observed.accumulateAndGet(snapshot,
                    (previous, next) -> previous == null || next.revision() > previous.revision() ? next : previous));
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(60, observed.get().entries().size());
            assertEquals(center.snapshot(), observed.get());
            unsubscribe.run();
        }
    }
}
