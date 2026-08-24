package com.github.minecraft_ta.totalDebugCompanion.search.reference;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.IndexedReferenceSearch;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceUsagePage;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceQuery;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeSourceCatalog;
import com.github.tth05.jindex.ClassIndex;

import javax.swing.SwingUtilities;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Runs bounded reference-index queries away from the Swing event thread. */
public final class ReferenceSearchService implements AutoCloseable {
    private final Searcher searcher;
    private final ExecutorService executor;
    private final RuntimeSourceCatalog sourceCatalog;
    private SearchOperation activeSearch;

    public ReferenceSearchService(ClassIndex index) {
        this(singleIndex(index));
    }

    public ReferenceSearchService(Supplier<ClassIndex> indexSupplier) {
        this(indexSupplier, RuntimeSourceCatalog.empty());
    }

    public ReferenceSearchService(
            Supplier<ClassIndex> indexSupplier,
            RuntimeSourceCatalog sourceCatalog
    ) {
        Objects.requireNonNull(indexSupplier, "indexSupplier");
        this.searcher = (query, limit) -> new IndexedReferenceSearch(indexSupplier.get()).search(query, limit);
        this.sourceCatalog = Objects.requireNonNull(sourceCatalog, "sourceCatalog");
        this.executor = newExecutor();
    }

    ReferenceSearchService(Searcher searcher) {
        this.searcher = Objects.requireNonNull(searcher, "searcher");
        this.sourceCatalog = RuntimeSourceCatalog.empty();
        this.executor = newExecutor();
    }

    public RuntimeSourceCatalog sourceCatalog() {
        return this.sourceCatalog;
    }

    public synchronized SearchHandle search(ReferenceQuery query, int limit, Listener listener) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(listener, "listener");
        if (limit <= 0) {
            throw new IllegalArgumentException("Reference-search limit must be positive");
        }
        if (this.executor.isShutdown()) {
            throw new IllegalStateException("Reference-search service is closed");
        }
        if (this.activeSearch != null) {
            this.activeSearch.cancel();
        }

        SearchOperation operation = new SearchOperation(query, limit, listener);
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
        void onCompleted(ReferenceUsagePage result);

        void onFailed(Throwable failure);
    }

    public interface SearchHandle {
        void cancel();

        boolean isDone();
    }

    @FunctionalInterface
    interface Searcher {
        ReferenceUsagePage search(ReferenceQuery query, int limit);
    }

    private final class SearchOperation implements SearchHandle {
        private final ReferenceQuery query;
        private final int limit;
        private final Listener listener;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean done = new AtomicBoolean();

        private SearchOperation(ReferenceQuery query, int limit, Listener listener) {
            this.query = query;
            this.limit = limit;
            this.listener = listener;
        }

        private void run() {
            try {
                ReferenceUsagePage result = searcher.search(this.query, this.limit);
                if (!this.cancelled.get()) {
                    dispatch(() -> {
                        if (!this.cancelled.get()) {
                            this.listener.onCompleted(result);
                        }
                    });
                }
            } catch (RuntimeException failure) {
                if (!this.cancelled.get() && !Thread.currentThread().isInterrupted()) {
                    dispatch(() -> {
                        if (!this.cancelled.get()) {
                            this.listener.onFailed(failure);
                        }
                    });
                }
            } finally {
                this.done.set(true);
                finished(this);
            }
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

    private static ExecutorService newExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> Thread.ofPlatform()
                .daemon(true)
                .name("totaldebug-reference-search")
                .unstarted(runnable));
    }

    private static Supplier<ClassIndex> singleIndex(ClassIndex index) {
        Objects.requireNonNull(index, "index");
        return () -> index;
    }
}
