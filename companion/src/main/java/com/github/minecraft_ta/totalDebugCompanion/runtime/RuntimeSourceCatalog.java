package com.github.minecraft_ta.totalDebugCompanion.runtime;

import com.github.minecraft_ta.totaldebug.storage.RuntimeInventory;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Resolves JIndex source ids to the runtime module that owns the indexed class. */
public final class RuntimeSourceCatalog {
    private final Map<Integer, RuntimeSnapshotBytecodeSource.Source> sourcesById;
    private final Map<Integer, RuntimeInventory.RuntimeModule> modulesBySourceId;
    private final List<RuntimeInventory.RuntimeModule> modules;
    private final Map<String, int[]> sourceIdsByModuleId;
    private final Map<String, List<RuntimeSnapshotBytecodeSource.Source>> sourcesByModuleId;

    public RuntimeSourceCatalog(List<RuntimeSnapshotBytecodeSource.Source> sources) {
        Map<Integer, RuntimeSnapshotBytecodeSource.Source> indexedSources = new LinkedHashMap<>();
        Map<Integer, RuntimeInventory.RuntimeModule> modules = new LinkedHashMap<>();
        Map<String, RuntimeInventory.RuntimeModule> modulesById = new LinkedHashMap<>();
        Map<String, List<Integer>> sourceIdsByModuleId = new LinkedHashMap<>();
        for (RuntimeSnapshotBytecodeSource.Source source : List.copyOf(sources)) {
            RuntimeSnapshotBytecodeSource.Source previousSource = indexedSources.putIfAbsent(source.sourceId(), source);
            if (previousSource != null && !previousSource.equals(source)) {
                throw new IllegalArgumentException("Runtime source " + source.sourceId() + " is defined twice");
            }
            RuntimeInventory.RuntimeModule previous = modules.putIfAbsent(source.sourceId(), source.module());
            if (previous != null && !previous.equals(source.module())) {
                throw new IllegalArgumentException(
                        "Runtime source " + source.sourceId() + " has conflicting module identities"
                );
            }
            RuntimeInventory.RuntimeModule previousModule = modulesById.putIfAbsent(
                    source.module().id(),
                    source.module()
            );
            if (previousModule != null && !previousModule.equals(source.module())) {
                throw new IllegalArgumentException(
                        "Runtime module " + source.module().id() + " has conflicting metadata"
                );
            }
            sourceIdsByModuleId.computeIfAbsent(source.module().id(), ignored -> new ArrayList<>())
                    .add(source.sourceId());
        }
        this.sourcesById = Map.copyOf(indexedSources);
        this.modulesBySourceId = Map.copyOf(modules);
        this.modules = modulesById.values().stream()
                .sorted(Comparator.comparingInt(RuntimeSourceCatalog::presentationPriority)
                        .thenComparing(RuntimeInventory.RuntimeModule::displayName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(RuntimeInventory.RuntimeModule::id))
                .toList();
        Map<String, int[]> frozenSourceIds = new LinkedHashMap<>();
        Map<String, List<RuntimeSnapshotBytecodeSource.Source>> frozenSources = new LinkedHashMap<>();
        for (Map.Entry<String, List<Integer>> entry : sourceIdsByModuleId.entrySet()) {
            frozenSourceIds.put(
                    entry.getKey(),
                    entry.getValue().stream().mapToInt(Integer::intValue).sorted().distinct().toArray()
            );
            frozenSources.put(
                    entry.getKey(),
                    entry.getValue().stream()
                            .sorted()
                            .map(indexedSources::get)
                            .toList()
            );
        }
        this.sourceIdsByModuleId = Map.copyOf(frozenSourceIds);
        this.sourcesByModuleId = Map.copyOf(frozenSources);
    }

    public RuntimeInventory.RuntimeModule moduleFor(int sourceId) {
        RuntimeInventory.RuntimeModule module = this.modulesBySourceId.get(sourceId);
        if (module == null) {
            throw new IllegalArgumentException("Unknown runtime source id " + sourceId);
        }
        return module;
    }

    public RuntimeSnapshotBytecodeSource.Source sourceFor(int sourceId) {
        RuntimeSnapshotBytecodeSource.Source source = this.sourcesById.get(sourceId);
        if (source == null) {
            throw new IllegalArgumentException("Unknown runtime source id " + sourceId);
        }
        return source;
    }

    public List<RuntimeInventory.RuntimeModule> modules() {
        return this.modules;
    }

    public List<RuntimeInventory.RuntimeModule> modules(RuntimeInventory.ModuleKind kind) {
        RuntimeInventory.ModuleKind requestedKind = Objects.requireNonNull(kind, "kind");
        return this.modules.stream().filter(module -> module.kind() == requestedKind).toList();
    }

    public List<RuntimeSnapshotBytecodeSource.Source> sourcesForModule(String moduleId) {
        List<RuntimeSnapshotBytecodeSource.Source> sources = this.sourcesByModuleId.get(
                Objects.requireNonNull(moduleId, "moduleId")
        );
        if (sources == null) {
            throw new IllegalArgumentException("Unknown runtime module id " + moduleId);
        }
        return sources;
    }

    public int[] sourceIdsForModules(Set<String> moduleIds) {
        Set<String> selected = new LinkedHashSet<>(Objects.requireNonNull(moduleIds, "moduleIds"));
        return selected.stream()
                .map(moduleId -> {
                    int[] sourceIds = this.sourceIdsByModuleId.get(moduleId);
                    if (sourceIds == null) {
                        throw new IllegalArgumentException("Unknown runtime module id " + moduleId);
                    }
                    return sourceIds;
                })
                .flatMapToInt(java.util.Arrays::stream)
                .sorted()
                .distinct()
                .toArray();
    }

    public static RuntimeSourceCatalog empty() {
        return new RuntimeSourceCatalog(List.of());
    }

    private static int presentationPriority(RuntimeInventory.RuntimeModule module) {
        return switch (module.kind()) {
            case PLATFORM -> 0;
            case MOD -> 1;
            case LIBRARY -> 2;
            case JAVA_RUNTIME -> 3;
        };
    }
}
