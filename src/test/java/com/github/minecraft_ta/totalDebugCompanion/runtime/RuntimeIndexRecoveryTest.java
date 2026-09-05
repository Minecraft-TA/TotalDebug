package com.github.minecraft_ta.totalDebugCompanion.runtime;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource.Source;
import com.github.minecraft_ta.totaldebug.storage.InstancePaths;
import com.github.minecraft_ta.totaldebug.storage.RuntimeInventory;
import com.github.tth05.jindex.ClassIndex;
import com.google.gson.JsonParser;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeIndexRecoveryTest {
    @TempDir Path temporaryDirectory;

    private static final String CURRENT_ID = "current-runtime";
    private static final RuntimeInventory.RuntimeModule MODULE = new RuntimeInventory.RuntimeModule(
            "fixture", "Fixture", RuntimeInventory.ModuleKind.MOD);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rebuildsAfterIndexedJarIsRemoved(boolean restore) throws Exception {
        InstancePaths paths = currentInventory(this.temporaryDirectory);
        Path removed = this.temporaryDirectory.resolve("mod-previous.jar");
        Files.write(removed, classBytes(CachedType.class));
        writeCache(paths, "previous-runtime", removed);
        Files.delete(removed);

        assertRebuilt(paths, restore);
    }

    enum Damage { MISSING_INDEX, TRUNCATED_ARCHIVE, INVALID_MANIFEST, UNSUPPORTED_FORMAT, INVALID_NATIVE_INDEX }

    @ParameterizedTest
    @EnumSource(Damage.class)
    void rebuildsUnusableCacheFromValidInventory(Damage damage) throws Exception {
        for (boolean restore : List.of(false, true)) {
            InstancePaths paths = currentInventory(this.temporaryDirectory.resolve(Boolean.toString(restore)));
            writeCache(paths, CURRENT_ID, paths.home().resolve("current.jar"));
            damageCache(paths.index(), damage);

            assertRebuilt(paths, restore);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingCurrentSourceRemainsAnErrorAndDoesNotReplaceTheCache(boolean restore) throws Exception {
        InstancePaths paths = currentInventory(this.temporaryDirectory);
        writeCache(paths, CURRENT_ID, paths.home().resolve("current.jar"));
        byte[] previousCache = Files.readAllBytes(paths.index());
        Files.delete(paths.home().resolve("current.jar"));

        try (RuntimeIndexService service = new RuntimeIndexService(new Object(),
                ignored -> fail("An inventory with a missing current source must not become ready"))) {
            await(service, paths, restore);
            assertEquals(RuntimeIndexService.Phase.FAILED, service.status().phase());
            assertTrue(service.status().detail().contains("Runtime source is unavailable:"), service.status().detail());
            assertArrayEquals(previousCache, Files.readAllBytes(paths.index()));
        }
    }

    private static InstancePaths currentInventory(Path root) throws Exception {
        Files.createDirectories(root);
        Path jar = root.resolve("current.jar");
        try (var output = new ZipOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new ZipEntry(CurrentType.class.getName().replace('.', '/') + ".class"));
            output.write(classBytes(CurrentType.class));
            output.closeEntry();
        }
        InstancePaths paths = new InstancePaths(root);
        new RuntimeInventory(CURRENT_ID, "21", System.getProperty("java.home"), true,
                List.of(new RuntimeInventory.Source(RuntimeInventory.SourceKind.ARCHIVE, jar,
                        jar.toUri().toString(), MODULE))).write(paths.inventory());
        return paths;
    }

    private static void writeCache(InstancePaths paths, String inventoryId, Path source) throws Exception {
        try (ClassIndex index = ClassIndex.fromBytes(List.of(classBytes(CachedType.class)))) {
            IndexCache.write(paths.index(), index, new IndexCache.Manifest(inventoryId,
                    List.of(new Source(0, source, source.toUri().toString(), MODULE)))).close();
        }
    }

    private static void damageCache(Path index, Damage damage) throws Exception {
        if (damage == Damage.MISSING_INDEX) {
            Files.delete(index);
            return;
        }
        if (damage == Damage.TRUNCATED_ARCHIVE) {
            Files.writeString(index, "truncated");
            return;
        }
        String manifest;
        try (var archive = ZipFile.builder().setPath(index).get()) {
            manifest = new String(archive.getInputStream(archive.getEntry("manifest.json")).readAllBytes(), StandardCharsets.UTF_8);
        }
        if (damage == Damage.INVALID_MANIFEST) {
            manifest = "{}";
        } else if (damage == Damage.UNSUPPORTED_FORMAT) {
            var json = JsonParser.parseString(manifest).getAsJsonObject();
            json.addProperty("format", IndexCache.FORMAT + 1);
            manifest = json.toString();
        }
        try (var output = new ZipOutputStream(Files.newOutputStream(index))) {
            output.putNextEntry(new ZipEntry("index"));
            output.write(0); // Valid ZIP metadata, but not a readable native index.
            output.closeEntry();
            output.putNextEntry(new ZipEntry("manifest.json"));
            output.write(manifest.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    private static void assertRebuilt(InstancePaths paths, boolean restore) throws Exception {
        var installed = new AtomicReference<RuntimeIndexService.ReadySnapshot>();
        try (RuntimeIndexService service = new RuntimeIndexService(new Object(), installed::set)) {
            await(service, paths, restore);
            assertEquals(RuntimeIndexService.Phase.READY, service.status().phase(), service.status().detail());
            assertNotNull(installed.get());
            assertNotNull(installed.get().index().findClass(CurrentType.class.getName()));
            assertNull(installed.get().index().findClass(CachedType.class.getName()));
            assertEquals(CURRENT_ID, IndexCache.read(paths.index()).inventoryId());
            try (ClassIndex persisted = ClassIndex.fromFile(paths.index().toString())) {
                assertNotNull(persisted.findClass(CurrentType.class.getName()));
                assertNull(persisted.findClass(CachedType.class.getName()));
            }
        } finally {
            if (installed.get() != null) installed.get().close();
        }
    }

    private static void await(RuntimeIndexService service, InstancePaths paths, boolean restore) throws Exception {
        CountDownLatch settled = new CountDownLatch(1);
        service.addStatusListener(status -> {
            if (status.phase() == RuntimeIndexService.Phase.READY || status.phase() == RuntimeIndexService.Phase.FAILED) {
                settled.countDown();
            }
        });
        if (restore) service.restore(paths.home());
        else service.accept(paths.home(), CURRENT_ID, paths.inventory());
        assertTrue(settled.await(30, TimeUnit.SECONDS), "Index preparation did not finish: " + service.status());
    }

    private static byte[] classBytes(Class<?> type) throws Exception {
        try (var input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            assertNotNull(input);
            return input.readAllBytes();
        }
    }

    static class CachedType { }
    static class CurrentType { }
}
