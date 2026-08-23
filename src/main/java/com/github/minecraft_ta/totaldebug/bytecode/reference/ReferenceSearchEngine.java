package com.github.minecraft_ta.totaldebug.bytecode.reference;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Searches physical class directories and archives without loading the classes. */
public final class ReferenceSearchEngine {
    private final List<Path> classSources;
    private final int parallelism;

    public ReferenceSearchEngine(Collection<Path> classSources) {
        this(classSources, Runtime.getRuntime().availableProcessors());
    }

    ReferenceSearchEngine(Collection<Path> classSources, int parallelism) {
        this.classSources = List.copyOf(Objects.requireNonNull(classSources, "classSources"));
        if (parallelism < 1) {
            throw new IllegalArgumentException("parallelism must be positive");
        }
        this.parallelism = parallelism;
    }

    public ReferenceSearchResult search(ReferenceQuery query) throws IOException {
        return search(query, ReferenceSearchMonitor.NONE);
    }

    public ReferenceSearchResult search(ReferenceQuery query, ReferenceSearchMonitor monitor) throws IOException {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(monitor, "monitor");

        try (PreparedClassFileSources sources = PreparedClassFileSources.prepare(this.classSources)) {
            int totalClassFiles = sources.classFileCount();
            CancellationState cancellation = new CancellationState(monitor);
            MemberResolutionIndex memberResolution = null;
            if (!(query instanceof ReferenceQuery.ClassReference)) {
                monitor.onProgress(new ReferenceSearchProgress(
                        ReferenceSearchPhase.RESOLVING_MEMBER_OWNERS,
                        0,
                        totalClassFiles
                ));
                MemberResolutionIndex.BuildResult buildResult = MemberResolutionIndex.build(
                        query,
                        sources,
                        cancellation::isRequested,
                        processed -> monitor.onProgress(new ReferenceSearchProgress(
                                ReferenceSearchPhase.RESOLVING_MEMBER_OWNERS,
                                processed,
                                totalClassFiles
                        ))
                );
                if (buildResult.cancelled()) {
                    return new ReferenceSearchResult(List.of(), 0, totalClassFiles, true);
                }
                if (buildResult.processedClassFiles() != totalClassFiles) {
                    throw new IOException("Runtime class sources changed while resolving member owners");
                }
                memberResolution = buildResult.index();
            }

            monitor.onProgress(new ReferenceSearchProgress(
                    ReferenceSearchPhase.SCANNING_REFERENCES,
                    0,
                    totalClassFiles
            ));

            ThreadFactoryName threadNames = new ThreadFactoryName();
            try (ExecutorService executor = Executors.newFixedThreadPool(
                    this.parallelism,
                    runnable -> Thread.ofPlatform()
                            .daemon(true)
                            .name(threadNames.next())
                            .unstarted(runnable)
            )) {
                SearchState state = new SearchState(
                        query,
                        memberResolution,
                        monitor,
                        cancellation,
                        totalClassFiles,
                        executor,
                        this.parallelism
                );
                sources.read(state::submit);
                state.finish();
                return state.result();
            }
        }
    }

    private static final class SearchState {
        private final ReferenceQuery query;
        private final MemberResolutionIndex memberResolution;
        private final ReferenceSearchMonitor monitor;
        private final CancellationState cancellation;
        private final int totalClassFiles;
        private final int parallelism;
        private final CompletionService<ClassScanOutcome> completionService;
        private final Set<ReferenceLocation> locations = new TreeSet<>();
        private int submitted;
        private int completed;

        private SearchState(
                ReferenceQuery query,
                MemberResolutionIndex memberResolution,
                ReferenceSearchMonitor monitor,
                CancellationState cancellation,
                int totalClassFiles,
                ExecutorService executor,
                int parallelism
        ) {
            this.query = query;
            this.memberResolution = memberResolution;
            this.monitor = monitor;
            this.cancellation = cancellation;
            this.totalClassFiles = totalClassFiles;
            this.parallelism = parallelism;
            this.completionService = new ExecutorCompletionService<>(executor);
        }

        private boolean submit(String origin, byte[] bytes) throws IOException {
            if (isCancellationRequested()) {
                return false;
            }

            this.completionService.submit(() -> {
                try {
                    return new ClassScanOutcome(
                            BytecodeReferenceScanner.scan(bytes, this.query, this.memberResolution)
                    );
                } catch (RuntimeException exception) {
                    throw new ClassScanFailure(origin, exception);
                }
            });
            this.submitted++;

            if (this.submitted - this.completed >= this.parallelism) {
                completeOne();
            }
            return !isCancellationRequested();
        }

        private void finish() throws IOException {
            while (this.completed < this.submitted) {
                completeOne();
            }
        }

        private void completeOne() throws IOException {
            try {
                ClassScanOutcome outcome = this.completionService.take().get();
                this.locations.addAll(outcome.locations());
                this.completed++;
                this.monitor.onProgress(new ReferenceSearchProgress(
                        ReferenceSearchPhase.SCANNING_REFERENCES,
                        this.completed,
                        this.totalClassFiles
                ));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Reference search was interrupted", exception);
            } catch (ExecutionException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof ClassScanFailure failure) {
                    throw new IOException("Unable to scan class file " + failure.origin(), failure.getCause());
                }
                throw new IOException("Reference-search worker failed", cause);
            }
        }

        private ReferenceSearchResult result() throws IOException {
            boolean cancelled = this.completed < this.totalClassFiles && isCancellationRequested();
            if (!cancelled && this.completed != this.totalClassFiles) {
                throw new IOException("Runtime class sources changed during reference search");
            }
            return new ReferenceSearchResult(
                    List.copyOf(this.locations),
                    this.completed,
                    this.totalClassFiles,
                    cancelled
            );
        }

        private boolean isCancellationRequested() {
            return this.cancellation.isRequested();
        }
    }

    private static final class CancellationState {
        private final ReferenceSearchMonitor monitor;
        private boolean requested;

        private CancellationState(ReferenceSearchMonitor monitor) {
            this.monitor = monitor;
        }

        private boolean isRequested() {
            this.requested |= this.monitor.isCancelled();
            return this.requested;
        }
    }

    private record ClassScanOutcome(Set<ReferenceLocation> locations) {
    }

    private static final class ClassScanFailure extends RuntimeException {
        private final String origin;

        private ClassScanFailure(String origin, RuntimeException cause) {
            super(cause);
            this.origin = origin;
        }

        private String origin() {
            return this.origin;
        }
    }

    private static final class ThreadFactoryName {
        private int next;

        private synchronized String next() {
            return "totaldebug-reference-search-" + this.next++;
        }
    }
}
