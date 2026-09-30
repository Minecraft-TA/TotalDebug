package com.github.minecraft_ta.totalDebugCompanion.change;

import com.github.minecraft_ta.totalDebugCompanion.util.Strand;
import com.github.minecraft_ta.totalDebugCompanion.util.Workers;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * The project's writes to the game's and the packs' files, one at a time, so writes to the same file never interleave
 * (docs/SYSTEMS.md, section 5). The change pipeline writes through it; the project finishes it before its change record
 * closes. It refuses work once closed.
 */
public final class WriteQueue implements Executor, AutoCloseable {
    private final Strand strand = Workers.fileStrand();
    private boolean closed;

    @Override
    public synchronized void execute(Runnable write) {
        if (this.closed) throw new RejectedExecutionException("The project is closing");
        this.strand.execute(write);
    }

    /**
     * Stops taking writes and waits until those already taken have finished, so every file written is also recorded
     * before the change record closes. An interruption does not cut the wait short; it is kept for the caller.
     */
    @Override
    public void close() {
        CompletableFuture<Void> drained = new CompletableFuture<>();
        synchronized (this) {
            this.closed = true;
            this.strand.execute(() -> drained.complete(null));
        }
        boolean interrupted = false;
        while (true) {
            try {
                drained.get();
                break;
            } catch (InterruptedException interruption) {
                interrupted = true;
            } catch (ExecutionException impossible) {
                break;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
