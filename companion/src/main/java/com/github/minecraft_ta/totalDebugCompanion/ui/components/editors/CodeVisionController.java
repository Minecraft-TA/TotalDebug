package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.insight.SymbolInsight;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.ASTCache;
import com.github.minecraft_ta.totalDebugCompanion.jdt.insight.SourceDeclaration;
import com.github.minecraft_ta.totalDebugCompanion.jdt.insight.SourceDeclarationAnalyzer;
import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.CodeSymbol;
import com.github.minecraft_ta.totalDebugCompanion.search.insight.CodeInsightService;

import javax.swing.JComponent;
import javax.swing.JLayer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Keeps parsed declarations, native summaries, painted hints and gutter icons on the same version. */
final class CodeVisionController implements AutoCloseable {
    private final String editorIdentifier;
    private final CodeInsightService service;
    private final CodeVisionLayerUI layerUI;
    private final JLayer<JComponent> layer;
    private final HierarchyGutterMarkers gutterMarkers;

    private final Runnable unsubscribeAst;
    private CodeInsightService.SearchHandle activeAnalysis;
    private long generation;
    private boolean closed;

    CodeVisionController(
            String editorIdentifier,
            CodeInsightService service,
            CodeVisionLayerUI layerUI,
            JLayer<JComponent> layer,
            HierarchyGutterMarkers gutterMarkers
    ) {
        this.editorIdentifier = Objects.requireNonNull(editorIdentifier, "editorIdentifier");
        this.service = Objects.requireNonNull(service, "service");
        this.layerUI = Objects.requireNonNull(layerUI, "layerUI");
        this.layer = Objects.requireNonNull(layer, "layer");
        this.gutterMarkers = Objects.requireNonNull(gutterMarkers, "gutterMarkers");
        this.unsubscribeAst = ASTCache.addChangeListener(this.editorIdentifier, (unit, version) -> {
            String source = ASTCache.getContents(this.editorIdentifier);
            if (source != null) {
                analyze(SourceDeclarationAnalyzer.analyze(unit, source));
            }
        });
    }

    private synchronized void analyze(List<SourceDeclaration> declarations) {
        if (this.closed) {
            return;
        }
        if (this.activeAnalysis != null) {
            this.activeAnalysis.cancel();
        }
        long currentGeneration = ++this.generation;
        LinkedHashSet<CodeSymbol> symbols = new LinkedHashSet<>();
        declarations.forEach(declaration -> symbols.add(declaration.symbol()));
        this.activeAnalysis = this.service.summarize(symbols, new CodeInsightService.Listener<>() {
            @Override
            public void onCompleted(Map<CodeSymbol, SymbolInsight> result) {
                apply(currentGeneration, declarations, result);
            }

            @Override
            public void onFailed(Throwable failure) {
                fail(currentGeneration, failure);
            }
        });
    }

    private synchronized void apply(
            long completedGeneration,
            List<SourceDeclaration> declarations,
            Map<CodeSymbol, SymbolInsight> summaries
    ) {
        if (this.closed || completedGeneration != this.generation) {
            return;
        }
        this.activeAnalysis = null;
        List<CodeVisionEntry> entries = declarations.stream()
                .map(declaration -> new CodeVisionEntry(
                        declaration,
                        summaries.getOrDefault(declaration.symbol(), SymbolInsight.EMPTY)
                ))
                .filter(entry -> entry.insight().usageCount() > 0
                        || !entry.insight().hierarchy().isEmpty())
                .toList();
        this.layerUI.setEntries(entries, this.layer);
        this.gutterMarkers.setEntries(entries);
    }

    private synchronized void fail(long failedGeneration, Throwable failure) {
        if (this.closed || failedGeneration != this.generation) {
            return;
        }
        this.activeAnalysis = null;
        this.layerUI.setEntries(List.of(), this.layer);
        this.gutterMarkers.setEntries(List.of());
        failure.printStackTrace(System.err);
    }

    @Override
    public synchronized void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.unsubscribeAst.run();
        if (this.activeAnalysis != null) {
            this.activeAnalysis.cancel();
            this.activeAnalysis = null;
        }
        this.layerUI.setEntries(List.of(), this.layer);
        this.gutterMarkers.dispose();
    }
}
