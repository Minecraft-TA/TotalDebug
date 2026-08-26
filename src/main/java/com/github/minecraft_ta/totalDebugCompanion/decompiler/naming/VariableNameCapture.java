package com.github.minecraft_ta.totalDebugCompanion.decompiler.naming;

import com.github.minecraft_ta.totalDebugCompanion.source.SourceVariableNames;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Collects renames from Vineflower's naming plugin while one class is being decompiled. */
public final class VariableNameCapture implements AutoCloseable {
    private static final Map<String, List<VariableNameCapture>> ACTIVE = new LinkedHashMap<>();

    private final String internalClassName;
    private final Map<String, Set<String>> candidates = new LinkedHashMap<>();
    private boolean closed;

    private VariableNameCapture(String internalClassName) {
        this.internalClassName = internalClassName;
    }

    public static VariableNameCapture open(String internalClassName) {
        VariableNameCapture capture = new VariableNameCapture(internalClassName);
        synchronized (ACTIVE) {
            ACTIVE.computeIfAbsent(internalClassName, ignored -> new ArrayList<>()).add(capture);
        }
        return capture;
    }

    static void record(String owner, String runtimeName, String displayedName) {
        if (runtimeName == null
                || !GeneratedVariableNames.matches(runtimeName)
                || displayedName == null
                || displayedName.isBlank()) {
            return;
        }
        List<VariableNameCapture> captures;
        synchronized (ACTIVE) {
            captures = List.copyOf(ACTIVE.getOrDefault(owner, List.of()));
        }
        for (VariableNameCapture capture : captures) {
            capture.add(runtimeName, displayedName);
        }
    }

    public synchronized SourceVariableNames result() {
        Map<String, String> unambiguous = new LinkedHashMap<>();
        this.candidates.forEach((runtimeName, displayedNames) -> {
            if (displayedNames.size() == 1) {
                unambiguous.put(runtimeName, displayedNames.iterator().next());
            }
        });
        return SourceVariableNames.of(unambiguous);
    }

    private synchronized void add(String runtimeName, String displayedName) {
        if (!this.closed) {
            this.candidates.computeIfAbsent(runtimeName, ignored -> new LinkedHashSet<>()).add(displayedName);
        }
    }

    @Override
    public void close() {
        synchronized (ACTIVE) {
            if (this.closed) {
                return;
            }
            this.closed = true;
            List<VariableNameCapture> captures = ACTIVE.get(this.internalClassName);
            if (captures != null) {
                captures.remove(this);
                if (captures.isEmpty()) {
                    ACTIVE.remove(this.internalClassName);
                }
            }
        }
    }
}
