package com.github.minecraft_ta.totalDebugCompanion.runtime;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resolves JIndex source ids to the runtime module that owns the indexed class. */
public final class RuntimeSourceCatalog {
    private final Map<Integer, RuntimeInventory.RuntimeModule> modulesBySourceId;

    public RuntimeSourceCatalog(List<RuntimeSnapshotBytecodeSource.Source> sources) {
        Map<Integer, RuntimeInventory.RuntimeModule> modules = new LinkedHashMap<>();
        for (RuntimeSnapshotBytecodeSource.Source source : List.copyOf(sources)) {
            RuntimeInventory.RuntimeModule previous = modules.putIfAbsent(source.sourceId(), source.module());
            if (previous != null && !previous.equals(source.module())) {
                throw new IllegalArgumentException(
                        "Runtime source " + source.sourceId() + " has conflicting module identities"
                );
            }
        }
        this.modulesBySourceId = Map.copyOf(modules);
    }

    public RuntimeInventory.RuntimeModule moduleFor(int sourceId) {
        RuntimeInventory.RuntimeModule module = this.modulesBySourceId.get(sourceId);
        if (module == null) {
            throw new IllegalArgumentException("Unknown runtime source id " + sourceId);
        }
        return module;
    }

    public static RuntimeSourceCatalog empty() {
        return new RuntimeSourceCatalog(List.of());
    }
}
