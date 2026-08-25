package com.github.minecraft_ta.totalDebugCompanion.search.insight;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.insight.HierarchyPage;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.insight.HierarchyQuery;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.insight.IndexedCodeInsight;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.insight.SymbolInsight;
import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.CodeSymbol;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeSourceCatalog;
import com.github.tth05.jindex.ClassIndex;

import javax.swing.SwingUtilities;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Runs editor insight and hierarchy queries away from the Swing event thread. */
public final class CodeInsightService implements AutoCloseable {
    private final Supplier<ClassIndex> indexSupplier;
    private final RuntimeSourceCatalog sourceCatalog;
    private final ExecutorService executor;
    private final Set<Operation<?>> operations = ConcurrentHashMap.newKeySet();

    public CodeInsightService(Supplier<ClassIndex> indexSupplier, RuntimeSourceCatalog sourceCatalog) {
        this.indexSupplier = Objects.requireNonNull(indexSupplier, "indexSupplier");
        this.sourceCatalog = Objects.requireNonNull(sourceCatalog, "sourceCatalog");
        this.executor = Executors.newFixedThreadPool(2, runnable -> Thread.ofPlatform()
                .daemon(true)
                .name("totaldebug-code-insight")
                .unstarted(runnable));
    }

    public RuntimeSourceCatalog sourceCatalog() {
        return this.sourceCatalog;
    }

    public SearchHandle summarize(Collection<CodeSymbol> symbols, Listener<Map<CodeSymbol, SymbolInsight>> listener) {
        Collection<CodeSymbol> snapshot = List.copyOf(Objects.requireNonNull(symbols, "symbols"));
        return submit(() -> new IndexedCodeInsight(this.indexSupplier.get()).summarize(snapshot), listener);
    }

    public SearchHandle search(HierarchyQuery query, int limit, Listener<HierarchyPage> listener) {
        Objects.requireNonNull(query, "query");
        if (limit < 1) {
            throw new IllegalArgumentException("Hierarchy result limit must be positive");
        }
        return submit(() -> new IndexedCodeInsight(this.indexSupplier.get()).search(query, limit), listener);
    }

    private <T> SearchHandle submit(Task<T> task, Listener<T> listener) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(listener, "listener");
        if (this.executor.isShutdown()) {
            throw new IllegalStateException("Code insight service is closed");
        }
        Operation<T> operation = new Operation<>(task, listener);
        this.operations.add(operation);
        this.executor.execute(operation::run);
        return operation;
    }

    @Override
    public void close() {
        for (Operation<?> operation : this.operations) {
            operation.cancel();
        }
        this.operations.clear();
        this.executor.shutdownNow();
    }

    public interface Listener<T> {
        void onCompleted(T result);

        void onFailed(Throwable failure);
    }

    public interface SearchHandle {
        void cancel();

        boolean isDone();
    }

    @FunctionalInterface
    private interface Task<T> {
        T run();
    }

    private final class Operation<T> implements SearchHandle {
        private final Task<T> task;
        private final Listener<T> listener;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean done = new AtomicBoolean();

        private Operation(Task<T> task, Listener<T> listener) {
            this.task = task;
            this.listener = listener;
        }

        private void run() {
            try {
                T result = this.task.run();
                dispatch(() -> {
                    if (!this.cancelled.get()) {
                        this.listener.onCompleted(result);
                    }
                });
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
                operations.remove(this);
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

}
