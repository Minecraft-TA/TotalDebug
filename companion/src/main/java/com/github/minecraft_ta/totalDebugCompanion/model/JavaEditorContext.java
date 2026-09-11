package com.github.minecraft_ta.totalDebugCompanion.model;

import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.ASTCache;
import java.util.function.IntConsumer;

/** Read-only access to the Java model state owned by an editor. */
public interface JavaEditorContext {
    ASTCache astCache();

    String astKey();

    int caretOffset();

    /** Registers a caret listener and returns a removal action. */
    Runnable addCaretOffsetListener(IntConsumer listener);
}
