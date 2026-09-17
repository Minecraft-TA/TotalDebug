package com.github.minecraft_ta.totalDebugCompanion.model;

import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.ASTCache;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.JavaAnalysis;
import java.util.function.IntConsumer;

/** Read-only access to the Java model state owned by an editor. */
public interface JavaEditorContext {
    ASTCache astCache();

    String astKey();

    int caretOffset();

    default JavaAnalysis currentSnapshot() { return astCache().getSnapshot(astKey()); }

    /** Registers a caret listener and returns a removal action. */
    Runnable addCaretOffsetListener(IntConsumer listener);
}
