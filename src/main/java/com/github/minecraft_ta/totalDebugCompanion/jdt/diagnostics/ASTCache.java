package com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics;

import com.github.minecraft_ta.totalDebugCompanion.jdt.impls.CompilationUnitImpl;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JdtConfiguration;
import org.eclipse.jdt.core.dom.ASTParser;
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

    public static void update(String key, String className, String contents) {
        int version = 0;
        synchronized (CACHE) {
            var existing = CACHE.get(key);
            if (existing != null)
                version = ++existing.version;
        }

        int finalVersion = version;
        CompletableFuture.runAsync(() -> {
            var ast = rawParse(className, contents);

            synchronized (CACHE) {
                var entry = CACHE.computeIfAbsent(key, (k) -> new Entry());
                //There's already something newer available
                if (entry.version > finalVersion)
                    return;

                entry.version = finalVersion;
                entry.unit = ast;
                entry.contents = contents;
            }
            notifyListeners(key, ast, finalVersion);
        });
    }

    public static CompilationUnit rawParse(String className, String contents) {
        ASTParser parser = JdtConfiguration.createParser();
        parser.setSource(new CompilationUnitImpl(className, contents));
        parser.setResolveBindings(true);
        parser.setStatementsRecovery(true);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        return (CompilationUnit) parser.createAST(null);
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
        if (existing != null)
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

    public static CompilationUnit getFromCache(String key) {
        synchronized (CACHE) {
            var entry = CACHE.get(key);
            if (entry == null)
                return null;

            return entry.unit;
        }
    }

    public static String getContents(String key) {
        synchronized (CACHE) {
            var entry = CACHE.get(key);
            return entry == null ? null : entry.contents;
        }
    }

    public static List<CompilationUnit> cachedUnits() {
        synchronized (CACHE) {
            return CACHE.values().stream()
                    .map(entry -> entry.unit)
                    .filter(Objects::nonNull)
                    .toList();
        }
    }

    private static void notifyListeners(String key, CompilationUnit ast, int version) {
        var listeners = LISTENERS.get(key);
        if (listeners == null) {
            return;
        }
        for (var listener : listeners) {
            listener.accept(ast, version);
        }
    }

    public static class Entry {

        public int version;
        public CompilationUnit unit;
        public String contents;
    }
}
