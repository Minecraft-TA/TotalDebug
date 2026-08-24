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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionDecompilationServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void reusesThePersistentSignatureAndBytecodeCache() throws Exception {
        byte[] bytes = classBytes(CacheFixture.class);
        Path classes = writeClass(bytes);
        try (ClassIndex index = ClassIndex.fromSources(List.of(IndexSource.classFile(0, bytes)))) {
            RuntimeSnapshotBytecodeSource bytecodeSource = new RuntimeSnapshotBytecodeSource(
                    List.of(classes),
                    index
            );
            AtomicInteger firstRuns = new AtomicInteger();
            Path firstOutput;
            try (CompanionDecompilationService service = service(bytecodeSource, firstRuns, "first")) {
                firstOutput = service.decompile(CacheFixture.class.getName()).join();
                assertEquals(firstOutput, service.decompile(CacheFixture.class.getName()).join());
            }
            assertEquals(1, firstRuns.get());
            assertTrue(Files.readString(firstOutput).contains("first"));

            AtomicInteger secondRuns = new AtomicInteger();
            try (CompanionDecompilationService service = service(bytecodeSource, secondRuns, "second")) {
                assertEquals(firstOutput, service.decompile(CacheFixture.class.getName()).join());
            }
            assertEquals(0, secondRuns.get());
            assertTrue(Files.readString(firstOutput).contains("first"));

            writeClass(new byte[]{9, 8, 7});
            AtomicInteger changedRuns = new AtomicInteger();
            try (CompanionDecompilationService service = service(bytecodeSource, changedRuns, "changed")) {
                assertEquals(firstOutput, service.decompile(CacheFixture.class.getName()).join());
            }
            assertEquals(1, changedRuns.get());
            assertTrue(Files.readString(firstOutput).contains("changed"));
        }
    }

    private CompanionDecompilationService service(
            RuntimeSnapshotBytecodeSource bytecodeSource,
            AtomicInteger runs,
            String marker
    ) throws IOException {
        JavaDecompiler decompiler = (binaryName, source) -> {
            runs.incrementAndGet();
            return new DecompilationResult(
                    "package fixture; public class CacheFixture { String value = \"" + marker + "\"; }",
                    DecompilationResult.Status.COMPLETE,
                    List.of()
            );
        };
        return new CompanionDecompilationService(
                "runtime-signature",
                this.temporaryDirectory.resolve("data"),
                bytecodeSource,
                decompiler
        );
    }

    private Path writeClass(byte[] bytes) throws IOException {
        Path classes = this.temporaryDirectory.resolve("classes");
        Path classFile = classes.resolve(CacheFixture.class.getName().replace('.', '/') + ".class");
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
}
