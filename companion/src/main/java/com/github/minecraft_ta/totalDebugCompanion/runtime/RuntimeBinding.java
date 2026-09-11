package com.github.minecraft_ta.totalDebugCompanion.runtime;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.minecraft_ta.totalDebugCompanion.decompile.CompanionDecompilationService;
import com.github.minecraft_ta.totalDebugCompanion.script.ScriptCompilationService;
import com.github.minecraft_ta.totalDebugCompanion.search.insight.CodeInsightService;
import com.github.minecraft_ta.totalDebugCompanion.search.reference.ReferenceSearchService;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.stream.Collectors;

/** Prepared runtime resources. The loader owns the index until acceptOwnership completes the handoff. */
public final class RuntimeBinding implements AutoCloseable {
    private final RuntimeIndexService.ReadySnapshot snapshot;
    private final RuntimeSourceCatalog sources;
    private final CompanionDecompilationService decompiler;
    private final ReferenceSearchService references;
    private final String classpath;
    // Open local editors retain these application-lived services; only their bindings change here.
    private final ScriptCompilationService compiler;
    private final CodeInsightService insights;
    private boolean attached;
    private boolean ownsIndex;
    private boolean closed;

    public RuntimeBinding(RuntimeIndexService.ReadySnapshot snapshot, Path dataDirectory,
                          RuntimeSnapshotBytecodeSource bytecode, ScriptCompilationService compiler,
                          CodeInsightService insights) throws IOException {
        this.snapshot = snapshot;
        this.compiler = compiler;
        this.insights = insights;
        this.sources = new RuntimeSourceCatalog(snapshot.sources());
        this.classpath = snapshot.sources().stream().map(source -> source.path().toString())
                .collect(Collectors.joining(File.pathSeparator));
        try {
            this.decompiler = new CompanionDecompilationService(snapshot.signature(), dataDirectory, bytecode);
        } catch (IOException | RuntimeException failure) {
            bytecode.close();
            throw failure;
        }
        this.references = new ReferenceSearchService(snapshot::index, this.sources);
    }

    /** Called after the previous runtime has detached, under the application lifecycle lock. */
    public void attach() {
        if (closed || attached) throw new IllegalStateException("Runtime binding cannot be attached");
        attached = true;
        compiler.bind(snapshot);
        insights.rebind(snapshot::index, sources);
    }

    /** Last, non-failing step of publication. No fallible follow-up may precede returning to the loader. */
    public void acceptOwnership() {
        ownsIndex = true;
    }

    public RuntimeIndexService.ReadySnapshot snapshot() { return snapshot; }
    public RuntimeSourceCatalog sources() { return sources; }
    public CompanionDecompilationService decompiler() { return decompiler; }
    public ReferenceSearchService references() { return references; }
    public String classpath() { return classpath; }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            if (attached) {
                try { compiler.bind(null); }
                finally {
                    insights.rebind(() -> { throw new IllegalStateException("Runtime class index is not ready"); },
                            RuntimeSourceCatalog.empty());
                }
            }
        } finally {
            try { decompiler.close(); }
            finally {
                try { references.close(); }
                finally { if (ownsIndex) snapshot.close(); }
            }
        }
    }
}
