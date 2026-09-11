package com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics;

import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaAst;
import org.eclipse.jdt.core.dom.CompilationUnit;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

public class ASTCache {

    private final Map<String, Entry> cache = new HashMap<>();
    private final Map<String, CopyOnWriteArrayList<BiConsumer<CompilationUnit, Integer>>> listeners =
            new ConcurrentHashMap<>();

    public CompletableFuture<Void> update(String key, String className, String contents) {
        return update(key, className, contents, JavaEditorSource.identity(contents));
    }

    public CompletableFuture<Void> update(String key, String className, String editorContents, JavaEditorSource source) {
        Entry selected;
        int version;
        synchronized (cache) {
            selected = cache.computeIfAbsent(key, ignored -> new Entry());
            version = ++selected.version;
        }

        int finalVersion = version;
        return CompletableFuture.runAsync(() -> {
            synchronized (cache) {
                if (cache.get(key) != selected || selected.version != finalVersion) return;
            }
            var ast = JavaAst.parse(className, source.text());
            List<BiConsumer<CompilationUnit, Integer>> listeners;
            synchronized (cache) {
                var entry = cache.get(key);
                //There's already something newer available
                if (entry != selected || entry.version != finalVersion)
                    return;

                entry.version = finalVersion;
                entry.unit = ast;
                entry.contents = editorContents;
                entry.sourceMap = source.sourceMap();
                entry.privilegedAccess = source.privilegedAccess();
                listeners = List.copyOf(this.listeners.getOrDefault(key, new CopyOnWriteArrayList<>()));
            }
            for (var listener : listeners) {
                synchronized (cache) { if (cache.get(key) != selected) return; }
                listener.accept(ast, finalVersion);
            }
        });
    }

    public Runnable addChangeListener(String key, BiConsumer<CompilationUnit, Integer> listener) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(listener, "listener");
        var listeners = this.listeners.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>());
        listeners.add(listener);
        Entry existing;
        synchronized (cache) {
            existing = cache.get(key);
        }
        if (existing != null && existing.unit != null)
            listener.accept(existing.unit, existing.version);
        return () -> {
            listeners.remove(listener);
            if (listeners.isEmpty()) {
                this.listeners.remove(key, listeners);
            }
        };
    }

    public void removeFromCache(String key) {
        synchronized (cache) {
            cache.remove(key);
        }
        this.listeners.remove(key);
    }

    public void clear() {
        synchronized (cache) {
            cache.clear();
            this.listeners.clear();
        }
    }

    public CompilationUnit getFromCache(String key) {
        synchronized (cache) {
            var entry = cache.get(key);
            if (entry == null)
                return null;

            return entry.unit;
        }
    }

    public Snapshot getSnapshot(String key) {
        synchronized (cache) {
            Entry entry = cache.get(key);
            return entry == null || entry.unit == null ? null : new Snapshot(entry.unit, entry.contents, entry.sourceMap);
        }
    }

    public record Snapshot(CompilationUnit unit, String contents, JavaSourceMap sourceMap) {
    }

    public String getContents(String key) {
        synchronized (cache) {
            var entry = cache.get(key);
            return entry == null ? null : entry.contents;
        }
    }

    public int toGeneratedOffset(String key, int editorOffset) {
        synchronized (cache) {
            Entry entry = cache.get(key);
            return entry == null ? editorOffset : entry.sourceMap.toGeneratedOffset(editorOffset);
        }
    }

    public int toEditorOffset(String key, int generatedOffset) {
        synchronized (cache) {
            Entry entry = cache.get(key);
            return entry == null ? generatedOffset : entry.sourceMap.toEditorOffset(generatedOffset);
        }
    }

    public boolean allowsPrivilegedAccess(String key) {
        synchronized (cache) {
            Entry entry = cache.get(key);
            return entry != null && entry.privilegedAccess;
        }
    }

    public static class Entry {

        public int version;
        public CompilationUnit unit;
        public String contents;
        public JavaSourceMap sourceMap = JavaSourceMap.IDENTITY;
        public boolean privilegedAccess;
    }
}
