package com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics;

import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaAst;
import org.eclipse.jdt.core.dom.CompilationUnit;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

public class ASTCache {

    private static final Map<String, Entry> CACHE = new HashMap<>();
    private static final Map<String, CopyOnWriteArrayList<BiConsumer<CompilationUnit, Integer>>> LISTENERS =
            new ConcurrentHashMap<>();

    public static CompletableFuture<Void> update(String key, String className, String contents) {
        return update(key, className, contents, JavaEditorSource.identity(contents));
    }

    public static CompletableFuture<Void> update(String key, String className, String editorContents, JavaEditorSource source) {
        Entry selected;
        int version;
        synchronized (CACHE) {
            selected = CACHE.computeIfAbsent(key, ignored -> new Entry());
            version = ++selected.version;
        }

        int finalVersion = version;
        return CompletableFuture.runAsync(() -> {
            synchronized (CACHE) {
                if (CACHE.get(key) != selected || selected.version != finalVersion) return;
            }
            var ast = JavaAst.parse(className, source.text());
            List<BiConsumer<CompilationUnit, Integer>> listeners;
            synchronized (CACHE) {
                var entry = CACHE.get(key);
                //There's already something newer available
                if (entry != selected || entry.version != finalVersion)
                    return;

                entry.version = finalVersion;
                entry.unit = ast;
                entry.contents = editorContents;
                entry.sourceMap = source.sourceMap();
                entry.privilegedAccess = source.privilegedAccess();
                listeners = List.copyOf(LISTENERS.getOrDefault(key, new CopyOnWriteArrayList<>()));
            }
            for (var listener : listeners) {
                synchronized (CACHE) { if (CACHE.get(key) != selected) return; }
                listener.accept(ast, finalVersion);
            }
        });
    }

    public static Runnable addChangeListener(String key, BiConsumer<CompilationUnit, Integer> listener) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(listener, "listener");
        var listeners = LISTENERS.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>());
        listeners.add(listener);
        Entry existing;
        synchronized (CACHE) {
            existing = CACHE.get(key);
        }
        if (existing != null && existing.unit != null)
            listener.accept(existing.unit, existing.version);
        return () -> {
            listeners.remove(listener);
            if (listeners.isEmpty()) {
                LISTENERS.remove(key, listeners);
            }
        };
    }

    public static void removeFromCache(String key) {
        synchronized (CACHE) {
            CACHE.remove(key);
        }
        LISTENERS.remove(key);
    }

    public static void clear() {
        synchronized (CACHE) {
            CACHE.clear();
            LISTENERS.clear();
        }
    }

    public static CompilationUnit getFromCache(String key) {
        synchronized (CACHE) {
            var entry = CACHE.get(key);
            if (entry == null)
                return null;

            return entry.unit;
        }
    }

    public static Snapshot getSnapshot(String key) {
        synchronized (CACHE) {
            Entry entry = CACHE.get(key);
            return entry == null || entry.unit == null ? null : new Snapshot(entry.unit, entry.contents, entry.sourceMap);
        }
    }

    public record Snapshot(CompilationUnit unit, String contents, JavaSourceMap sourceMap) {
    }

    public static String getContents(String key) {
        synchronized (CACHE) {
            var entry = CACHE.get(key);
            return entry == null ? null : entry.contents;
        }
    }

    public static int toGeneratedOffset(String key, int editorOffset) {
        synchronized (CACHE) {
            Entry entry = CACHE.get(key);
            return entry == null ? editorOffset : entry.sourceMap.toGeneratedOffset(editorOffset);
        }
    }

    public static int toEditorOffset(String key, int generatedOffset) {
        synchronized (CACHE) {
            Entry entry = CACHE.get(key);
            return entry == null ? generatedOffset : entry.sourceMap.toEditorOffset(generatedOffset);
        }
    }

    public static boolean allowsPrivilegedAccess(String key) {
        synchronized (CACHE) {
            Entry entry = CACHE.get(key);
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
