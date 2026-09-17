package com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics;

import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Registry of current editor analyses. Owners publish or revoke complete results. */
public final class ASTCache {
    private final Map<String, Registration> editors = new HashMap<>();
    private final Map<String, CopyOnWriteArrayList<Consumer<JavaAnalysis>>> listeners = new HashMap<>();

    public synchronized Registration register(String key, Runnable refresh) {
        if (editors.containsKey(key)) throw new IllegalStateException("An analysis owner already exists for " + key);
        var registration = new Registration(key, refresh);
        editors.put(key, registration);
        return registration;
    }

    public synchronized JavaAnalysis getSnapshot(String key) {
        var editor = editors.get(key);
        var result = editor == null ? null : editor.snapshot;
        return result != null && result.environment() == CompanionClassIndex.identity() ? result : null;
    }

    /** Null revokes current semantic access; it is not an empty successful analysis. */
    public synchronized Runnable addChangeListener(String key, Consumer<JavaAnalysis> listener) {
        var selected = listeners.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>());
        selected.add(listener);
        var current = getSnapshot(key);
        if (current != null) listener.accept(current);
        return () -> {
            synchronized (ASTCache.this) {
                selected.remove(listener);
                if (selected.isEmpty()) listeners.remove(key, selected);
            }
        };
    }

    public void refreshEnvironment() {
        List<Registration> current;
        synchronized (this) { current = List.copyOf(editors.values()); }
        current.forEach(editor -> editor.refresh.run());
    }

    public synchronized void clear() {
        editors.clear();
        listeners.clear();
    }

    public final class Registration implements AutoCloseable {
        private final String key;
        private final Runnable refresh;
        private JavaAnalysis snapshot;

        private Registration(String key, Runnable refresh) { this.key = key; this.refresh = refresh; }

        public boolean isOpen() {
            synchronized (ASTCache.this) { return editors.get(key) == this; }
        }

        public void publish(JavaAnalysis result) {
            synchronized (ASTCache.this) {
                if (!isOpen()) return;
                snapshot = result;
                for (var listener : List.copyOf(listeners.getOrDefault(key, new CopyOnWriteArrayList<>()))) {
                    if (!isOpen() || snapshot != result) break;
                    listener.accept(result);
                }
            }
        }

        @Override public void close() {
            synchronized (ASTCache.this) {
                if (!isOpen()) return;
                publish(null);
                editors.remove(key, this);
                listeners.remove(key);
            }
        }
    }
}
