package com.github.minecraft_ta.totalDebugCompanion.search.insight;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.minecraft_ta.totaldebug.storage.RuntimeInventory;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeSourceCatalog;
import com.github.tth05.jindex.ClassIndex;
import com.github.tth05.jindex.IndexSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CodeInsightServiceTest {

    @Test
    void locatesTheSingleSourceOwningAQualifiedClass() throws Exception {
        var module = new RuntimeInventory.RuntimeModule(
                "sample",
                "Sample",
                RuntimeInventory.ModuleKind.MOD
        );
        List<RuntimeSnapshotBytecodeSource.Source> sources = List.of(
                source(7, "lists.jar", module),
                source(8, "maps.jar", module),
                source(9, "strings.jar", module)
        );
        try (ClassIndex index = ClassIndex.fromSources(List.of(
                IndexSource.classFile(7, classBytes(java.util.List.class)),
                IndexSource.classFile(8, classBytes(java.util.Map.class)),
                IndexSource.classFile(9, classBytes(String.class))
        )); CodeInsightService service = new CodeInsightService(() -> index, new RuntimeSourceCatalog(sources))) {
            CompletableFuture<RuntimeSnapshotBytecodeSource.Source> located = new CompletableFuture<>();

            service.locateClass("java.util.Map", listener(located));

            assertEquals(8, located.get(3, TimeUnit.SECONDS).sourceId());
        }
    }

    private static <T> CodeInsightService.Listener<T> listener(CompletableFuture<T> result) {
        return new CodeInsightService.Listener<>() {
            @Override
            public void onCompleted(T value) {
                result.complete(value);
            }

            @Override
            public void onFailed(Throwable failure) {
                result.completeExceptionally(failure);
            }
        };
    }

    private static RuntimeSnapshotBytecodeSource.Source source(
            int sourceId,
            String fileName,
            RuntimeInventory.RuntimeModule module
    ) {
        return new RuntimeSnapshotBytecodeSource.Source(
                sourceId,
                Path.of(fileName),
                "file:///" + fileName,
                module
        );
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (var stream = Objects.requireNonNull(type.getResourceAsStream(resource), resource)) {
            return stream.readAllBytes();
        }
    }
}
