package com.github.minecraft_ta.totalDebugCompanion.decompile;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.minecraft_ta.totalDebugCompanion.decompiler.DecompilationResult;
import com.github.minecraft_ta.totalDebugCompanion.decompiler.JavaDecompiler;
import com.github.tth05.jindex.ClassIndex;
import com.github.tth05.jindex.IndexSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeTestSources.bytecodeSource;

class CompanionDecompilationServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void reusesTheAuthoritativeDecompiledSourceCache() throws Exception {
        byte[] bytes = classBytes(CacheFixture.class);
        Path classes = writeClass(CacheFixture.class, bytes);
        try (ClassIndex index = ClassIndex.fromSources(List.of(IndexSource.classFile(0, bytes)))) {
            RuntimeSnapshotBytecodeSource bytecodeSource = bytecodeSource(List.of(classes), index);
            AtomicInteger firstRuns = new AtomicInteger();
            Path firstOutput;
            try (CompanionDecompilationService service = service(bytecodeSource, firstRuns, "first")) {
                firstOutput = service.decompile(CacheFixture.class.getName()).join();
                assertEquals(firstOutput, service.decompile(CacheFixture.class.getName()).join());
            }
            assertEquals(1, firstRuns.get());
            assertTrue(firstOutput.startsWith(this.temporaryDirectory.resolve("data/cache/decompiled")));
            assertEquals(CacheFixture.class.getName() + ".java", firstOutput.getFileName().toString());
            assertTrue(Files.readString(firstOutput).contains("first"));

            AtomicInteger secondRuns = new AtomicInteger();
            try (CompanionDecompilationService service = service(bytecodeSource(List.of(classes), index), secondRuns, "second")) {
                assertEquals(firstOutput, service.decompile(CacheFixture.class.getName()).join());
            }
            assertEquals(0, secondRuns.get());
            assertTrue(Files.readString(firstOutput).contains("first"));
        }
    }

    @Test
    void changingRuntimeInvalidatesTheVisibleDecompiledSources() throws Exception {
        byte[] bytes = classBytes(CacheFixture.class);
        Path classes = writeClass(CacheFixture.class, bytes);
        try (ClassIndex index = ClassIndex.fromSources(List.of(IndexSource.classFile(0, bytes)))) {
            RuntimeSnapshotBytecodeSource bytecodeSource = bytecodeSource(List.of(classes), index);
            Path output;
            try (CompanionDecompilationService service = service(
                    "first-runtime",
                    bytecodeSource,
                    new AtomicInteger(),
                    "first"
            )) {
                output = service.decompile(CacheFixture.class.getName()).join();
                assertTrue(Files.isRegularFile(output));
            }

            try (CompanionDecompilationService ignored = service(
                    "second-runtime",
                    bytecodeSource(List.of(classes), index),
                    new AtomicInteger(),
                    "second"
            )) {
                assertTrue(!Files.exists(output));
                assertTrue(ignored.cachedClasses().isEmpty());
            }
        }
    }

    @Test
    void retiredTreeReaderDoesNotReadTheReplacementRuntimesCache() throws Exception {
        byte[] bytes = classBytes(CacheFixture.class);
        Path classes = writeClass(CacheFixture.class, bytes);
        try (ClassIndex index = ClassIndex.fromSources(List.of(IndexSource.classFile(0, bytes)))) {
            CompanionDecompilationService retired = service("old-runtime", bytecodeSource(List.of(classes), index),
                    new AtomicInteger(), "old");
            try (retired) {
                retired.decompile(CacheFixture.class.getName()).join();
            }
            try (CompanionDecompilationService current = service("new-runtime",
                    bytecodeSource(List.of(classes), index), new AtomicInteger(), "current")) {
                current.decompile(CacheFixture.class.getName()).join();
                assertTrue(retired.cachedClasses().isEmpty());
                assertEquals(List.of(CacheFixture.class.getName()), current.cachedClasses());
            }
        }
    }

    @Test
    void cachedClassDoesNotWaitBehindAColdDecompilation() throws Exception {
        byte[] cachedBytes = classBytes(CacheFixture.class);
        byte[] coldBytes = classBytes(ColdFixture.class);
        Path classes = writeClass(CacheFixture.class, cachedBytes);
        writeClass(ColdFixture.class, coldBytes);
        try (ClassIndex index = ClassIndex.fromSources(List.of(
                IndexSource.classFile(0, cachedBytes),
                IndexSource.classFile(0, coldBytes)
        ))) {
            RuntimeSnapshotBytecodeSource bytecodeSource = bytecodeSource(List.of(classes), index);
            try (CompanionDecompilationService service = service(
                    bytecodeSource,
                    new AtomicInteger(),
                    "cached"
            )) {
                service.decompile(CacheFixture.class.getName()).join();
            }

            CountDownLatch coldStarted = new CountDownLatch(1);
            CountDownLatch releaseCold = new CountDownLatch(1);
            JavaDecompiler blockingDecompiler = (binaryName, source) -> {
                if (binaryName.equals(CacheFixture.class.getName())) {
                    throw new AssertionError("A cached class reached the decompiler");
                }
                coldStarted.countDown();
                try {
                    if (!releaseCold.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Timed out waiting to release the cold decompilation");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Cold decompilation was interrupted", exception);
                }
                return completeSource("cold");
            };

            try (CompanionDecompilationService service = new CompanionDecompilationService(
                    "runtime-signature",
                    this.temporaryDirectory.resolve("data"),
                    bytecodeSource(List.of(classes), index),
                    blockingDecompiler
            )) {
                CompletableFuture<Path> cold = service.decompile(ColdFixture.class.getName());
                assertTrue(coldStarted.await(1, TimeUnit.SECONDS));
                try {
                    Path cached = service.decompile(CacheFixture.class.getName()).get(1, TimeUnit.SECONDS);
                    assertTrue(Files.readString(cached).contains("cached"));
                } finally {
                    releaseCold.countDown();
                }
                cold.join();
            }
        }
    }

    @Test
    void closeCancelsQueuedWorkAndPreventsLatePublication() throws Exception {
        byte[] bytes = classBytes(CacheFixture.class);
        Path classes = writeClass(CacheFixture.class, bytes);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(1);
        try (ClassIndex index = ClassIndex.fromSources(List.of(IndexSource.classFile(0, bytes)))) {
            JavaDecompiler delayed = (name, source) -> {
                started.countDown();
                try {
                    while (true) {
                        try {
                            release.await();
                            return completeSource("late");
                        } catch (InterruptedException ignored) {
                            // Model third-party decompilation that does not stop immediately on interruption.
                        }
                    }
                } finally {
                    exited.countDown();
                }
            };
            var service = new CompanionDecompilationService("old", this.temporaryDirectory.resolve("data"),
                    bytecodeSource(List.of(classes), index), delayed);
            try {
                var running = service.load(CacheFixture.class.getName());
                assertTrue(started.await(5, TimeUnit.SECONDS));
                var queued = service.load(ColdFixture.class.getName());
                service.close();
                assertTrue(running.isCancelled());
                assertTrue(queued.isCancelled());
                index.close();
                release.countDown();
                assertTrue(exited.await(5, TimeUnit.SECONDS));
                assertTrue(service.cachedClasses().isEmpty());
            } finally {
                release.countDown();
                service.close();
            }
        }
    }

    private CompanionDecompilationService service(
            RuntimeSnapshotBytecodeSource bytecodeSource,
            AtomicInteger runs,
            String marker
    ) throws IOException {
        return service("runtime-signature", bytecodeSource, runs, marker);
    }

    private CompanionDecompilationService service(
            String runtimeSignature,
            RuntimeSnapshotBytecodeSource bytecodeSource,
            AtomicInteger runs,
            String marker
    ) throws IOException {
        JavaDecompiler decompiler = (binaryName, source) -> {
            runs.incrementAndGet();
            return completeSource(marker);
        };
        return new CompanionDecompilationService(
                runtimeSignature,
                this.temporaryDirectory.resolve("data"),
                bytecodeSource,
                decompiler
        );
    }

    private static DecompilationResult completeSource(String marker) {
        return new DecompilationResult(
                "package fixture; public class CacheFixture { String value = \"" + marker + "\"; }",
                DecompilationResult.Status.COMPLETE,
                List.of()
        );
    }

    private Path writeClass(Class<?> type, byte[] bytes) throws IOException {
        Path classes = this.temporaryDirectory.resolve("classes");
        Path classFile = classes.resolve(type.getName().replace('.', '/') + ".class");
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, bytes);
        return classes;
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        String resourceName = type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getClassLoader().getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IOException("Missing test class bytes for " + type.getName());
            }
            return input.readAllBytes();
        }
    }

    private static final class CacheFixture {
    }

    private static final class ColdFixture {
    }
}
