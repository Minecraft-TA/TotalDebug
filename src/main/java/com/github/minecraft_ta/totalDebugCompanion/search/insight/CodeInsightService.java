package com.github.minecraft_ta.totalDebugCompanion.search.insight;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.insight.HierarchyPage;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.insight.HierarchyQuery;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.insight.IndexedCodeInsight;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.insight.SymbolInsight;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
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
    private final ExecutorService executor;
    private final Set<Operation<?>> operations = ConcurrentHashMap.newKeySet();
    private volatile RuntimeBinding binding;

    public CodeInsightService(Supplier<ClassIndex> indexSupplier, RuntimeSourceCatalog sourceCatalog) {
        this.binding = new RuntimeBinding(indexSupplier, sourceCatalog);
        this.executor = Executors.newFixedThreadPool(2, runnable -> Thread.ofPlatform()
                .daemon(true)
                .name("totaldebug-code-insight")
                .unstarted(runnable));
    }

    public RuntimeSourceCatalog sourceCatalog() {
        return this.binding.sourceCatalog();
    }

    public synchronized void rebind(Supplier<ClassIndex> indexSupplier, RuntimeSourceCatalog sourceCatalog) {
        ensureOpen();
        for (Operation<?> operation : this.operations) {
            operation.cancel();
        }
        this.binding = new RuntimeBinding(indexSupplier, sourceCatalog);
    }

    public SearchHandle summarize(Collection<CodeSymbol> symbols, Listener<Map<CodeSymbol, SymbolInsight>> listener) {
        Collection<CodeSymbol> snapshot = List.copyOf(Objects.requireNonNull(symbols, "symbols"));
        return submit(binding -> new IndexedCodeInsight(binding.indexSupplier().get()).summarize(snapshot), listener);
    }

    public SearchHandle search(HierarchyQuery query, int limit, Listener<HierarchyPage> listener) {
        Objects.requireNonNull(query, "query");
        if (limit < 1) {
            throw new IllegalArgumentException("Hierarchy result limit must be positive");
        }
        return submit(binding -> new IndexedCodeInsight(binding.indexSupplier().get()).search(query, limit), listener);
    }

    public SearchHandle locateClass(
            String binaryClassName,
            Listener<RuntimeSnapshotBytecodeSource.Source> listener
    ) {
        String requestedClass = Objects.requireNonNull(binaryClassName, "binaryClassName");
        int separator = requestedClass.lastIndexOf('.');
        if (separator < 1 || separator == requestedClass.length() - 1) {
            throw new IllegalArgumentException("A binary class name must include a package and simple name");
        }
        String packageName = requestedClass.substring(0, separator);
        String simpleName = requestedClass.substring(separator + 1);
        return submit(binding -> {
            var indexedClass = binding.indexSupplier().get().findClass(packageName, simpleName);
            return indexedClass == null
                    ? null
                    : binding.sourceCatalog().sourceFor(indexedClass.getSourceId());
        }, listener);
    }

    private synchronized <T> SearchHandle submit(Task<T> task, Listener<T> listener) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(listener, "listener");
        ensureOpen();
        Operation<T> operation = new Operation<>(this.binding, task, listener);
        this.operations.add(operation);
        this.executor.execute(operation::run);
        return operation;
    }

    @Override
    public synchronized void close() {
        for (Operation<?> operation : this.operations) {
            operation.cancel();
        }
        this.operations.clear();
        this.executor.shutdownNow();
    }

    private void ensureOpen() {
        if (this.executor.isShutdown()) {
            throw new IllegalStateException("Code insight service is closed");
        }
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
        T run(RuntimeBinding binding);
    }

    private final class Operation<T> implements SearchHandle {
        private final RuntimeBinding binding;
        private final Task<T> task;
        private final Listener<T> listener;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean done = new AtomicBoolean();

        private Operation(RuntimeBinding binding, Task<T> task, Listener<T> listener) {
            this.binding = binding;
            this.task = task;
            this.listener = listener;
        }

        private void run() {
            try {
                if (this.cancelled.get()) {
                    return;
                }
                T result = this.task.run(this.binding);
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

    private record RuntimeBinding(
            Supplier<ClassIndex> indexSupplier,
            RuntimeSourceCatalog sourceCatalog
    ) {
        private RuntimeBinding {
            Objects.requireNonNull(indexSupplier, "indexSupplier");
            Objects.requireNonNull(sourceCatalog, "sourceCatalog");
        }
    }

}
