package com.github.minecraft_ta.totalDebugCompanion.runtime;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.minecraft_ta.totalDebugCompanion.script.ScriptCompilationService;
import com.github.minecraft_ta.totaldebug.storage.InstancePaths;
import com.github.minecraft_ta.totaldebug.storage.RuntimeInventory;
import com.github.tth05.jindex.ClassIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.io.IOException;
import java.time.Duration;
import java.util.Random;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import static org.junit.jupiter.api.Assertions.*;

class LocalIndexTest {
    @TempDir Path game;

    @Test void buildsReusesAndInvalidatesTheLocalIndexWithoutInventingARuntime() throws Exception {
        Path jar = Files.createDirectories(game.resolve("mods")).resolve("sample.jar");
        writeJar(jar, "sample/Example", 1, false);
        var paths = InstancePaths.forGame(game);
        String identity;
        try (var snapshot = open()) {
            assertFalse(snapshot.isRuntime());
            assertNull(snapshot.inventoryId());
            assertNotNull(snapshot.index().findClass("sample", "Example"));
            assertFalse(Files.exists(paths.inventory()));
            assertFalse(Files.exists(paths.scripts()));
            identity = snapshot.identity().value();
        }
        FileTime written = Files.getLastModifiedTime(paths.index());
        try (var snapshot = open()) {
            assertEquals(identity, snapshot.identity().value());
            assertEquals(written, Files.getLastModifiedTime(paths.index()));
        }
        FileTime jarTime = Files.getLastModifiedTime(jar);
        writeJar(jar, "sample/Example", 2, false);
        Files.setLastModifiedTime(jar, jarTime);
        try (var snapshot = open()) { assertNotEquals(identity, snapshot.identity().value()); }
    }

    @Test void preservesAnInvalidInventoryWhileFallingBackToModFiles() throws Exception {
        Path jar = Files.createDirectories(game.resolve("mods")).resolve("sample.jar");
        writeJar(jar, "sample/Example", 1, false);
        var paths = InstancePaths.forGame(game);
        Files.createDirectories(paths.runtime());
        Files.writeString(paths.inventory(), "invalid saved inventory");
        try (var snapshot = open()) {
            assertFalse(snapshot.isRuntime());
            assertEquals("invalid saved inventory", Files.readString(paths.inventory()));
        }
    }

    @Test void cachedReopenReadsEachModArchiveOnlyOnceForFingerprinting() throws Exception {
        Path jar = Files.createDirectories(game.resolve("mods")).resolve("sample.jar");
        byte[] resource = new byte[512 * 1024];
        new Random(42).nextBytes(resource);
        try (var archive = new JarOutputStream(Files.newOutputStream(jar))) {
            archive.putNextEntry(new JarEntry("sample/Example.class"));
            archive.write(classBytes("sample/Example", 1));
            archive.closeEntry();
            archive.putNextEntry(new JarEntry("asset.bin"));
            archive.write(resource);
        }
        try (var ignored = open()) { }
        Path recordingFile = game.resolve("cached-open.jfr");
        try (var recording = new Recording()) {
            recording.enable("jdk.FileRead").withThreshold(Duration.ZERO);
            recording.start();
            try (var snapshot = open()) { assertNotNull(snapshot.index().findClass("sample", "Example")); }
            recording.stop();
            recording.dump(recordingFile);
        }
        long bytesRead = RecordingFile.readAllEvents(recordingFile).stream()
                .filter(event -> event.getEventType().getName().equals("jdk.FileRead"))
                .filter(event -> jar.equals(Path.of(event.getString("path"))))
                .mapToLong(event -> Math.max(0, event.getLong("bytesRead"))).sum();
        long size = Files.size(jar);
        assertTrue(bytesRead >= size, "The cached reopen must still fingerprint archive contents");
        assertTrue(bytesRead < 2 * size, "Expected one full read plus ZIP metadata, got " + bytesRead + " bytes for " + size);
    }

    @Test void cachedLoadRejectsAnArchiveChangedAfterTheScan() throws Exception {
        Path jar = Files.createDirectories(game.resolve("mods")).resolve("sample.jar");
        writeJar(jar, "sample/Example", 1, false);
        try (var ignored = open()) { }
        var paths = InstancePaths.forGame(game);
        FileTime written = Files.getLastModifiedTime(paths.index());
        var failure = new CompletableFuture<RuntimeIndexService.Status>();
        var discarded = new AtomicReference<ClassIndex>();
        var published = new AtomicBoolean();
        try (var service = new RuntimeIndexService(new Object(), snapshot -> {
            published.set(true);
            snapshot.close();
        }, file -> {
            var index = ClassIndex.fromFile(file);
            discarded.set(index);
            try {
                writeJar(jar, "sample/Example", 2, false);
                Files.setLastModifiedTime(jar, FileTime.fromMillis(System.currentTimeMillis() + 2000));
            } catch (Exception exception) { index.close(); throw new AssertionError(exception); }
            return index;
        })) {
            service.addStatusListener(status -> {
                if (status.phase() == RuntimeIndexService.Phase.FAILED) failure.complete(status);
            });
            service.restore(paths.home(), game);
            assertTrue(failure.get(10, TimeUnit.SECONDS).detail().contains("Mod archive changed"));
            assertFalse(published.get());
            assertTrue(discarded.get().isDestroyed());
            assertEquals(written, Files.getLastModifiedTime(paths.index()));
        }
        try (var reopened = open();
             var bytes = RuntimeSnapshotBytecodeSource.fromLocal(reopened.sources(), reopened.index(), reopened.localGuard())) {
            assertArrayEquals(classBytes("sample/Example", 2), bytes.findClassBytes("sample.Example"));
        }
    }

    @Test void bytecodeUsesEffectiveMultiReleaseEntriesAndRejectsChangedArchives() throws Exception {
        Path jar = Files.createDirectories(game.resolve("mods")).resolve("sample.jar");
        writeJar(jar, "sample/Example", 1, true);
        try (var snapshot = open();
             var bytes = RuntimeSnapshotBytecodeSource.fromLocal(snapshot.sources(), snapshot.index(), snapshot.localGuard());
             var compiler = new ScriptCompilationService(ignored -> false, ignored -> false)) {
            assertArrayEquals(classBytes("sample/Example", 21), bytes.findClassBytes("sample.Example"));
            assertNotNull(snapshot.index().findClass("sample", "Example"));
            assertThrows(IllegalArgumentException.class, () -> compiler.bind(snapshot));
            assertFalse(compiler.hasRuntime());
            writeJar(jar, "sample/Example", 2, false);
            Files.setLastModifiedTime(jar, FileTime.fromMillis(System.currentTimeMillis() + 2000));
            assertThrows(IOException.class, bytes::requireCurrent);
            assertThrows(IOException.class, () -> bytes.findClassBytes("sample.Example"));
        }
    }

    @Test void damagedPreparedRuntimeSourcesFallBackWithoutChangingTheInventory() throws Exception {
        Path jar = Files.createDirectories(game.resolve("mods")).resolve("sample.jar");
        writeJar(jar, "sample/Example", 1, false);
        Path damaged = game.resolve("damaged-prepared.jar");
        Files.copy(jar, damaged);
        var paths = InstancePaths.forGame(game);
        var module = new RuntimeInventory.RuntimeModule("sample", "Sample", RuntimeInventory.ModuleKind.MOD);
        new RuntimeInventory("damaged-runtime", "21", System.getProperty("java.home"), true,
                List.of(new RuntimeInventory.Source(RuntimeInventory.SourceKind.ARCHIVE, damaged, damaged.toUri().toString(), module))).write(paths.inventory());
        String original = Files.readString(paths.inventory());
        try (var cachedRuntime = open()) { assertTrue(cachedRuntime.isRuntime()); }
        assertTrue(Files.isRegularFile(paths.index()));
        Files.writeString(damaged, "not an archive");
        try (var snapshot = open()) {
            assertFalse(snapshot.isRuntime());
            assertNotNull(snapshot.index().findClass("sample", "Example"));
            assertEquals(original, Files.readString(paths.inventory()));
        }
    }

    @Test void duplicatesResolveInFilenameOrderAndBrokenArchivesRemainInTheCatalog() throws Exception {
        Path mods = Files.createDirectories(game.resolve("mods"));
        writeJar(mods.resolve("a.jar"), "sample/Example", 1, false);
        writeJar(mods.resolve("b.jar"), "sample/Example", 2, false);
        Files.writeString(mods.resolve("broken.jar"), "not an archive");
        try (var archive = new JarOutputStream(Files.newOutputStream(mods.resolve("invalid-class.jar")))) {
            archive.putNextEntry(new JarEntry("invalid.class"));
            archive.write(new byte[]{0});
        }
        try (var snapshot = open();
             var bytes = RuntimeSnapshotBytecodeSource.fromLocal(snapshot.sources(), snapshot.index(), snapshot.localGuard())) {
            assertArrayEquals(classBytes("sample/Example", 1), bytes.findClassBytes("sample.Example"));
            assertTrue(snapshot.sources().stream().anyMatch(source -> source.path().getFileName().toString().equals("broken.jar")));
            String detail = IndexCache.read(snapshot.indexFile()).detail();
            assertTrue(detail.contains("broken.jar"));
            assertTrue(detail.contains("invalid-class.jar"));
            assertTrue(detail.contains("duplicate"));
        }
    }

    @Test void liveInventorySupersedesALoadingLocalIndexAndRemainsAvailableOffline() throws Exception {
        Path jar = Files.createDirectories(game.resolve("mods")).resolve("sample.jar");
        writeJar(jar, "sample/Example", 1, false);
        try (var ignored = open()) { }
        var paths = InstancePaths.forGame(game);
        var loading = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var first = new AtomicBoolean(true);
        var discarded = new AtomicReference<ClassIndex>();
        var ready = new CompletableFuture<RuntimeIndexService.ReadySnapshot>();
        try (var service = new RuntimeIndexService(new Object(), ready::complete, file -> {
            var index = ClassIndex.fromFile(file);
            if (first.getAndSet(false)) {
                discarded.set(index);
                loading.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            }
            return index;
        })) {
            service.restore(paths.home(), game);
            assertTrue(loading.await(15, TimeUnit.SECONDS));
            var module = new RuntimeInventory.RuntimeModule("sample", "Sample", RuntimeInventory.ModuleKind.MOD);
            new RuntimeInventory("captured-runtime", System.getProperty("java.runtime.version"), System.getProperty("java.home"), true,
                    List.of(new RuntimeInventory.Source(RuntimeInventory.SourceKind.ARCHIVE, jar, jar.toUri().toString(), module))).write(paths.inventory());
            service.accept(paths.home(), "captured-runtime", paths.inventory());
            release.countDown();
            try (var installed = ready.get(20, TimeUnit.SECONDS)) {
                assertTrue(installed.isRuntime());
                assertEquals("captured-runtime", installed.inventoryId());
                assertTrue(discarded.get().isDestroyed());
            }
        } finally { release.countDown(); }
        try (var reopened = open()) {
            assertTrue(reopened.isRuntime());
            assertEquals("captured-runtime", reopened.inventoryId());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedRuntimePreparationDoesNotCancelALoadingLocalIndex(boolean beforeWorkerStarts) throws Exception {
        Path jar = Files.createDirectories(game.resolve("mods")).resolve("sample.jar");
        writeJar(jar, "sample/Example", 1, false);
        try (var ignored = open()) { }
        var loading = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var ready = new CompletableFuture<RuntimeIndexService.ReadySnapshot>();
        var readyStatus = new CompletableFuture<RuntimeIndexService.Status>();
        Object lifecycle = new Object();
        try (var service = new RuntimeIndexService(lifecycle, ready::complete, file -> {
            loading.countDown();
            try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            return ClassIndex.fromFile(file);
        })) {
            service.addStatusListener(status -> {
                if (status.phase() == RuntimeIndexService.Phase.READY) readyStatus.complete(status);
            });
            synchronized (lifecycle) {
                service.restore(InstancePaths.forGame(game).home(), game);
                if (beforeWorkerStarts) service.failedBeforeBuild("Runtime capture failed");
            }
            assertTrue(loading.await(15, TimeUnit.SECONDS));
            if (!beforeWorkerStarts) service.failedBeforeBuild("Runtime capture failed");
            assertTrue(service.status().active(), "Offline indexing must remain active after a runtime preparation failure");
            release.countDown();
            try (var installed = ready.get(10, TimeUnit.SECONDS)) {
                assertFalse(installed.isRuntime());
                assertNotNull(installed.index().findClass("sample", "Example"));
                assertTrue(readyStatus.get(5, TimeUnit.SECONDS).detail().contains("Runtime capture failed"));
            }
        } finally { release.countDown(); }
    }

    private RuntimeIndexService.ReadySnapshot open() throws Exception {
        var ready = new CompletableFuture<RuntimeIndexService.ReadySnapshot>();
        try (var service = new RuntimeIndexService(new Object(), ready::complete)) {
            service.addStatusListener(status -> {
                if (status.phase() == RuntimeIndexService.Phase.FAILED) ready.completeExceptionally(status.failure());
            });
            service.restore(InstancePaths.forGame(game).home(), game);
            return ready.get(20, TimeUnit.SECONDS);
        }
    }

    private static void writeJar(Path jar, String name, int value, boolean multiRelease) throws Exception {
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (multiRelease) manifest.getMainAttributes().put(Attributes.Name.MULTI_RELEASE, "true");
        try (var archive = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            archive.putNextEntry(new JarEntry(name + ".class"));
            archive.write(classBytes(name, value));
            archive.closeEntry();
            if (multiRelease) {
                archive.putNextEntry(new JarEntry("META-INF/versions/21/" + name + ".class"));
                archive.write(classBytes(name, 21));
                archive.closeEntry();
            }
        }
    }

    static byte[] classBytes(String name, int value) {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "value", "()I", null, null);
        method.visitCode();
        method.visitLdcInsn(value);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
