package com.github.minecraft_ta.totaldebug.client.companion;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Closes the HTTP body to unblock a read when progress or the whole transfer exceeds its deadline. */
final class DownloadDeadline implements AutoCloseable {
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("TotalDebug Companion download deadline").factory());
    private final long started = System.nanoTime();
    private volatile long progressed = this.started;
    private volatile String failure;

    DownloadDeadline(InputStream body, CompanionAppInstaller.DownloadLimits limits) {
        long period = Math.clamp(Math.min(limits.idle().toNanos(), limits.total().toNanos()) / 4,
                1, TimeUnit.SECONDS.toNanos(1));
        this.timer.scheduleAtFixedRate(() -> {
            long now = System.nanoTime();
            if (now - this.started >= limits.total().toNanos()) {
                this.failure = "Companion download exceeded its total time limit of " + limits.total();
            } else if (now - this.progressed >= limits.idle().toNanos()) {
                this.failure = "Companion download made no progress for " + limits.idle();
            } else return;
            try { body.close(); }
            catch (IOException ignored) { /* The reader reports the deadline failure. */ }
            this.timer.shutdown();
        }, period, period, TimeUnit.NANOSECONDS);
    }

    void progress() { this.progressed = System.nanoTime(); }

    void check() throws IOException {
        if (this.failure != null) throw new IOException(this.failure);
    }

    @Override public void close() { this.timer.shutdownNow(); }
}
