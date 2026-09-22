package com.github.minecraft_ta.totalDebugCompanion.navigation;

import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeBinding;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.nio.file.Path;
import java.util.function.UnaryOperator;
import com.github.minecraft_ta.totalDebugCompanion.script.ScriptFiles;

/** History belongs to a project, including while its runtime is temporarily unavailable. */
public final class NavigationState {
    final NavigationHistory history = new NavigationHistory(100);
    record Traversal(RuntimeBinding runtime) { }
    final AtomicReference<Traversal> traversal = new AtomicReference<>();
    final AtomicLong revision = new AtomicLong();
    volatile NavigationEntry currentEntry;
    void invalidatePending() {
        revision.incrementAndGet();
        traversal.set(null);
    }
    public void relocateFiles(Path from, Path to) {
        invalidatePending();
        UnaryOperator<NavigationEntry> remap = entry -> {
            if (entry == null) return null;
            NavigationTarget target = entry.target();
            Path path = target instanceof NavigationTarget.LocalFile file ? file.path()
                    : target instanceof NavigationTarget.LocalDirectory directory ? directory.path() : null;
            if (path == null || !path.startsWith(from)) return entry;
            if (to == null) return null;
            Path moved = ScriptFiles.relocated(path, from, to);
            NavigationTarget replacement = target instanceof NavigationTarget.LocalFile file
                    ? new NavigationTarget.LocalFile(moved, file.offset()) : new NavigationTarget.LocalDirectory(moved);
            return new NavigationEntry(replacement, entry.runtimeSignature(), entry.viewState());
        };
        history.remap(remap);
        currentEntry = remap.apply(currentEntry);
    }
}
