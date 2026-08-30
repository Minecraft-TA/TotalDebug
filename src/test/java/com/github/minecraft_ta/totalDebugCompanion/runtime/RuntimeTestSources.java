package com.github.minecraft_ta.totalDebugCompanion.runtime;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.tth05.jindex.ClassIndex;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class RuntimeTestSources {
    private RuntimeTestSources() {
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
