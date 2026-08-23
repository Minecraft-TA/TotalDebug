package com.github.minecraft_ta.totalDebugCompanion.search.reference;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceQuery;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceSearchEngine;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceSearchMonitor;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceSearchProgress;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceSearchResult;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns the single full-runtime reference scan allowed to run at a time. */
public final class ReferenceSearchService implements AutoCloseable {
    private static final long PROGRESS_INTERVAL_NANOS = 50_000_000L;

    private final Searcher searcher;
    private final ExecutorService executor;
    private SearchOperation activeSearch;

    public ReferenceSearchService(Collection<Path> runtimeSources) {
        this(new ReferenceSearchEngine(runtimeSources)::search);
    }

    ReferenceSearchService(Searcher searcher) {
        this.searcher = Objects.requireNonNull(searcher, "searcher");
        this.executor = Executors.newSingleThreadExecutor(runnable -> Thread.ofPlatform()
                .daemon(true)
                .name("totaldebug-reference-search-coordinator")
                .unstarted(runnable));
    }

    public synchronized SearchHandle search(ReferenceQuery query, Listener listener) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(listener, "listener");
        if (this.executor.isShutdown()) {
            throw new IllegalStateException("Reference-search service is closed");
        }
        if (this.activeSearch != null) {
            this.activeSearch.cancel();
        }

        SearchOperation operation = new SearchOperation(query, listener);
        this.activeSearch = operation;
        this.executor.execute(operation::run);
        return operation;
    }

    @Override
    public synchronized void close() {
        if (this.activeSearch != null) {
            this.activeSearch.cancel();
            this.activeSearch = null;
        }
        this.executor.shutdownNow();
    }

    private synchronized void finished(SearchOperation operation) {
        if (this.activeSearch == operation) {
            this.activeSearch = null;
        }
    }

    public interface Listener {
        void onProgress(ReferenceSearchProgress progress);

        void onCompleted(ReferenceSearchResult result);

        void onFailed(Throwable failure);
    }

    public interface SearchHandle {
        void cancel();

        boolean isDone();
    }

    @FunctionalInterface
    interface Searcher {
        ReferenceSearchResult search(ReferenceQuery query, ReferenceSearchMonitor monitor) throws IOException;
    }

    private final class SearchOperation implements ReferenceSearchMonitor, SearchHandle {
        private final ReferenceQuery query;
        private final Listener listener;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean done = new AtomicBoolean();
        private long lastProgressDispatch;

        private SearchOperation(ReferenceQuery query, Listener listener) {
            this.query = query;
            this.listener = listener;
        }

        private void run() {
            try {
                ReferenceSearchResult result = searcher.search(this.query, this);
                dispatch(() -> this.listener.onCompleted(result));
            } catch (Throwable failure) {
                if (!(failure instanceof InterruptedException) && !Thread.currentThread().isInterrupted()) {
                    dispatch(() -> this.listener.onFailed(failure));
                }
            } finally {
                this.done.set(true);
                finished(this);
            }
        }

        @Override
        public boolean isCancelled() {
            return this.cancelled.get();
        }

        @Override
        public void onProgress(ReferenceSearchProgress progress) {
            long now = System.nanoTime();
            if (progress.processedClassFiles() != progress.totalClassFiles()
                    && now - this.lastProgressDispatch < PROGRESS_INTERVAL_NANOS) {
                return;
            }
            this.lastProgressDispatch = now;
            dispatch(() -> this.listener.onProgress(progress));
        }

        @Override
        public void cancel() {
            this.cancelled.set(true);
        }

        @Override
        public boolean isDone() {
            return this.done.get();
        }
    }

    private static void dispatch(Runnable callback) {
        if (SwingUtilities.isEventDispatchThread()) {
            callback.run();
        } else {
            SwingUtilities.invokeLater(callback);
        }
    }
}
