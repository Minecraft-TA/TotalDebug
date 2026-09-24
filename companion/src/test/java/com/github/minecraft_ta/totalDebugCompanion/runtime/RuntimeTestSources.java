package com.github.minecraft_ta.totalDebugCompanion.runtime;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeIndexService.PreparedInput;
import com.github.minecraft_ta.totaldebug.storage.InstancePaths;
import com.github.minecraft_ta.totaldebug.storage.RuntimeInventory;
import com.github.tth05.jindex.ClassIndex;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class RuntimeTestSources {
    private RuntimeTestSources() {
    }

    /** Seeds cache-loading tests with their fixture classes, without indexing the JDK. */
    public static void writeRuntimeCache(InstancePaths paths) throws IOException {
        var inventory = RuntimeInventory.read(paths.inventory());
        var inputs = RuntimeIndexService.prepareInputs(inventory);
        try (var index = ClassIndex.fromSources(inputs.stream().map(PreparedInput::indexSource).toList())) {
            var sources = inputs.stream().map(PreparedInput::publishedSource).distinct().toList();
            IndexCache.write(paths.index(), index, new IndexCache.Manifest(inventory.id(), sources)).close();
        }
    }

    public static void writeLocalCache(Path game) throws IOException {
        var scan = LocalModSources.scan(game, () -> { });
        var prepared = LocalModSources.prepare(scan, () -> { });
        try (var index = ClassIndex.fromSources(prepared.inputs().stream().map(PreparedInput::indexSource).toList())) {
            IndexCache.write(InstancePaths.forGame(game).index(), index,
                    new IndexCache.Manifest(scan.identity(), scan.sources(), prepared.detail())).close();
        }
    }

    public static RuntimeSnapshotBytecodeSource bytecodeSource(List<Path> paths, ClassIndex index) {
        List<RuntimeSnapshotBytecodeSource.Source> sources = new ArrayList<>(paths.size());
        for (int sourceId = 0; sourceId < paths.size(); sourceId++) {
            sources.add(librarySource(sourceId, paths.get(sourceId)));
        }
        return RuntimeSnapshotBytecodeSource.fromIndexedSources(sources, index);
    }

    public static RuntimeSnapshotBytecodeSource.Source librarySource(int sourceId, Path path) {
        String fileName = path.getFileName().toString();
        return new RuntimeSnapshotBytecodeSource.Source(
                sourceId,
                path,
                path.toUri().toASCIIString(),
                new RuntimeInventory.RuntimeModule(
                        fileName,
                        fileName,
                        RuntimeInventory.ModuleKind.LIBRARY
                )
        );
    }
}
