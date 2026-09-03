package com.github.minecraft_ta.totalDebugCompanion.runtime;

import com.github.minecraft_ta.totaldebug.storage.RuntimeInventory;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.tth05.jindex.ClassIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeIndexServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void indexesOnlyThePublishedSourcesNotArbitraryEmbeddedJars() throws Exception {
        byte[] nested = archive(RuntimeInventoryTest.class, null);
        Path outer = Files.write(this.temporaryDirectory.resolve("outer.jar"), archive(RuntimeIndexServiceTest.class, nested));
        Path dependency = Files.write(this.temporaryDirectory.resolve("dependency.jar"), nested);
        var module = new RuntimeInventory.RuntimeModule("fixture", "Fixture", RuntimeInventory.ModuleKind.MOD);
        var outerSource = new RuntimeInventory.Source(RuntimeInventory.SourceKind.ARCHIVE, outer, outer.toUri().toString(), module);
        var dependencySource = new RuntimeInventory.Source(RuntimeInventory.SourceKind.ARCHIVE, dependency, "nested:/dependency", module);

        for (var sources : List.of(List.of(outerSource), List.of(outerSource, dependencySource))) {
            RuntimeInventory inventory = new RuntimeInventory("fixture", "21", System.getProperty("java.home"), true, sources);
            var inputs = RuntimeIndexService.prepareInputs(inventory);
            assertEquals(sources.size(), inputs.size());
            assertEquals(sources.stream().map(RuntimeInventory.Source::path).toList(),
                    inputs.stream().map(input -> input.publishedSource().path()).toList());
            try (ClassIndex index = ClassIndex.fromSources(inputs.stream().map(RuntimeIndexService.PreparedInput::indexSource).toList())) {
                assertNotNull(index.findClass(RuntimeIndexServiceTest.class.getName()));
                if (sources.size() == 1) {
                    assertNull(index.findClass(RuntimeInventoryTest.class.getName()));
                } else {
                    assertNotNull(index.findClass(RuntimeInventoryTest.class.getName()));
                }
            }
        }
    }

    private static byte[] archive(Class<?> type, byte[] nested) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            output.putNextEntry(new ZipEntry(type.getName().replace('.', '/') + ".class"));
            output.write(classBytes(type));
            output.closeEntry();
            if (nested != null) {
                output.putNextEntry(new ZipEntry("META-INF/jarjar/dependency.jar"));
                output.write(nested);
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    @Test
    void keepsTheRestoredSnapshotWhenTheLiveInventoryMatches() throws Exception {
        String inventoryId = "matching-inventory";
        Path dataDirectory = this.temporaryDirectory.resolve("data");
        Path indexFile = new com.github.minecraft_ta.totaldebug.storage.InstancePaths(dataDirectory).index();
        Path classes = Files.createDirectories(this.temporaryDirectory.resolve("classes"));
        try (ClassIndex index = ClassIndex.fromBytes(List.of(classBytes(RuntimeIndexServiceTest.class)))) {
            IndexCache.write(indexFile, index, new IndexCache.Manifest(inventoryId,
                    List.of(new RuntimeSnapshotBytecodeSource.Source(0, classes, classes.toUri().toASCIIString(),
                            new RuntimeInventory.RuntimeModule("test", "Test", RuntimeInventory.ModuleKind.MOD)))));
        }

        new RuntimeInventory(inventoryId, "21", System.getProperty("java.home"), true,
                List.of(new RuntimeInventory.Source(RuntimeInventory.SourceKind.DIRECTORY, classes, classes.toUri().toString(),
                        new RuntimeInventory.RuntimeModule("test", "Test", RuntimeInventory.ModuleKind.MOD))))
                .write(new com.github.minecraft_ta.totaldebug.storage.InstancePaths(dataDirectory).inventory());

        var cachedModified = Files.getLastModifiedTime(indexFile);
        AtomicInteger installations = new AtomicInteger();
        List<RuntimeIndexService.ReadySnapshot> snapshots = new ArrayList<>();
        CountDownLatch restored = new CountDownLatch(1);
        try (RuntimeIndexService service = new RuntimeIndexService(new Object(), snapshot -> {
            snapshots.add(snapshot);
            installations.incrementAndGet();
            restored.countDown();
        })) {
            service.restore(dataDirectory);
            assertTrue(restored.await(5, TimeUnit.SECONDS));
            assertNotNull(snapshots.getFirst().index().findClass(RuntimeIndexServiceTest.class.getName()));
            assertEquals(cachedModified, Files.getLastModifiedTime(indexFile));

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


    @Test
    void rebuildsOneIndexInPlaceAndRestoresOnlyTheCurrentInventory() throws Exception {
        Path root = this.temporaryDirectory.resolve("instance");
        var paths = new com.github.minecraft_ta.totaldebug.storage.InstancePaths(root);
        Path jar = this.temporaryDirectory.resolve("current.jar");
        var module = new RuntimeInventory.RuntimeModule("fixture", "Fixture", RuntimeInventory.ModuleKind.MOD);
        var snapshots = new java.util.concurrent.CopyOnWriteArrayList<RuntimeIndexService.ReadySnapshot>();
        try {
            for (int version = 0; version < 2; version++) {
                Class<?> type = version == 0 ? RuntimeIndexServiceTest.class : RuntimeInventoryTest.class;
                Files.write(jar, archive(type, null));
                new RuntimeInventory("runtime-" + version, "21", System.getProperty("java.home"), true,
                        List.of(new RuntimeInventory.Source(RuntimeInventory.SourceKind.ARCHIVE, jar, jar.toUri().toString(), module)))
                        .write(paths.inventory());
                try (RuntimeIndexService service = new RuntimeIndexService(new Object(), snapshots::add)) {
                    CountDownLatch settled = new CountDownLatch(1);
                    service.addStatusListener(status -> {
                        if (status.phase() == RuntimeIndexService.Phase.READY || status.phase() == RuntimeIndexService.Phase.FAILED) {
                            settled.countDown();
                        }
                    });
                    service.accept(root, "runtime-" + version, paths.inventory());
                    assertTrue(settled.await(30, TimeUnit.SECONDS));
                    assertEquals(RuntimeIndexService.Phase.READY, service.status().phase(), service.status().detail());
                    var snapshot = snapshots.getLast();
                    assertEquals(paths.index(), snapshot.indexFile());
                    assertNotNull(snapshot.index().findClass(type.getName()));
                    if (version == 1) {
                        assertNull(snapshot.index().findClass(RuntimeIndexServiceTest.class.getName()));
                    }
                }
            }
            try (var files = Files.list(paths.runtime())) {
                assertEquals(List.of(".lock", "index.jindex", "inventory.json"),
                        files.map(path -> path.getFileName().toString()).sorted().toList());
            }
            Files.delete(paths.inventory());
            try (RuntimeIndexService service = new RuntimeIndexService(new Object(),
                    ignored -> { throw new AssertionError("An index without its current inventory must not be restored"); })) {
                service.restore(root);
                assertEquals(RuntimeIndexService.Phase.WAITING, service.status().phase());
            }
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


}
