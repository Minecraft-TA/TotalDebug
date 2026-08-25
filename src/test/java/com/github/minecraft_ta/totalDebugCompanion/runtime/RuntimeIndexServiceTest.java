package com.github.minecraft_ta.totalDebugCompanion.runtime;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.tth05.jindex.ClassIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeIndexServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void keepsTheRestoredSnapshotWhenTheLiveInventoryMatches() throws Exception {
        String inventoryId = "matching-inventory";
        Path dataDirectory = this.temporaryDirectory.resolve("data");
        Path cacheDirectory = dataDirectory.resolve("index");
        Path indexFile = cacheDirectory.resolve("index");
        Path sourcesFile = cacheDirectory.resolve(PreparedRuntimeSources.FILE_NAME);
        Path classes = Files.createDirectories(this.temporaryDirectory.resolve("classes"));
        Files.createDirectories(cacheDirectory);

        try (ClassIndex index = ClassIndex.fromBytes(List.of(classBytes(RuntimeIndexServiceTest.class)))) {
            index.saveToFile(indexFile.toString());
        }
        PreparedRuntimeSources.write(sourcesFile, List.of(new RuntimeSnapshotBytecodeSource.Source(
                0,
                classes,
                classes.toUri().toASCIIString(),
                new RuntimeInventory.RuntimeModule("test", "Test")
        )));
        writeIndexMetadata(cacheDirectory.resolve("index.properties"), inventoryId);

        AtomicInteger installations = new AtomicInteger();
        List<RuntimeIndexService.ReadySnapshot> snapshots = new ArrayList<>();
        CountDownLatch restored = new CountDownLatch(1);
        try (RuntimeIndexService service = new RuntimeIndexService(snapshot -> {
            snapshots.add(snapshot);
            installations.incrementAndGet();
            restored.countDown();
        })) {
            service.restore(dataDirectory);
            assertTrue(restored.await(5, TimeUnit.SECONDS));

            AtomicBoolean inventoryAccepted = new AtomicBoolean();
            CountDownLatch settled = new CountDownLatch(1);
            service.addStatusListener(status -> {
                if (inventoryAccepted.get()
                        && (status.phase() == RuntimeIndexService.Phase.READY
                        || status.phase() == RuntimeIndexService.Phase.FAILED)) {
                    settled.countDown();
                }
            });

            inventoryAccepted.set(true);
            service.accept(dataDirectory, inventoryId, dataDirectory.resolve("missing-inventory.properties"));

            assertTrue(settled.await(5, TimeUnit.SECONDS));
            assertEquals(RuntimeIndexService.Phase.READY, service.status().phase());
            assertEquals(1, installations.get());
        } finally {
            snapshots.forEach(RuntimeIndexService.ReadySnapshot::close);
        }
    }

    private static byte[] classBytes(Class<?> type) throws Exception {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Missing class resource " + resource);
            }
            return input.readAllBytes();
        }
    }

    private static void writeIndexMetadata(Path file, String inventoryId) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("format", "1");
        properties.setProperty("inventory.id", inventoryId);
        try (var output = Files.newOutputStream(file)) {
            properties.store(output, "test runtime index");
        }
    }
}
