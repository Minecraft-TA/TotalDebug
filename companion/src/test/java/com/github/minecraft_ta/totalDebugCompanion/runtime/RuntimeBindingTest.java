package com.github.minecraft_ta.totalDebugCompanion.runtime;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import com.github.minecraft_ta.totalDebugCompanion.script.ScriptCompilationService;
import com.github.minecraft_ta.totalDebugCompanion.search.insight.CodeInsightService;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceQuery;
import com.github.tth05.jindex.ClassIndex;
import com.github.minecraft_ta.totaldebug.storage.RuntimeInventory;
import com.github.minecraft_ta.totalDebugCompanion.search.reference.ReferenceSearchService;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceUsagePage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeBindingTest {
    @TempDir Path directory;

    @Test
    void acceptedRuntimeDetachesConsumersBeforeClosingItsIndexAndCanCloseTwice() throws Exception {
        try (var compiler = new ScriptCompilationService(message -> false, request -> false);
             var insights = new CodeInsightService(() -> null, RuntimeSourceCatalog.empty());
             var snapshot = snapshot()) {
            var bytecode = RuntimeSnapshotBytecodeSource.fromIndexedSources(snapshot.sources(), snapshot.index());
            var binding = new RuntimeBinding(snapshot, directory, bytecode, compiler, insights);
            binding.attach();
            binding.acceptOwnership();
            assertTrue(compiler.isCurrentInventory("test"));
            binding.close();
            binding.close();
            assertFalse(compiler.isCurrentInventory("test"));
            assertTrue(snapshot.index().isDestroyed());
            assertThrows(IllegalStateException.class, () -> bytecode.hasClass("java.lang.Object"));
            assertThrows(IllegalStateException.class, () -> binding.decompiler().load("java.lang.Object"));
            assertThrows(IllegalStateException.class, () -> binding.references().search(
                    ReferenceQuery.classReference("java.lang.Object"), 1, new ReferenceSearchService.Listener() {
                        public void onCompleted(ReferenceUsagePage result) {}
                        public void onFailed(Throwable failure) {}
                    }));
            // Local editors retain this service; closing the binding must not destroy its worker.
            assertDoesNotThrow(() -> insights.rebind(() -> null, RuntimeSourceCatalog.empty()));
        }
    }

    @Test
    void rejectedCandidateLeavesIndexDisposalToTheLoader() throws Exception {
        try (var compiler = new ScriptCompilationService(message -> false, request -> false);
             var insights = new CodeInsightService(() -> null, RuntimeSourceCatalog.empty());
             var snapshot = snapshot()) {
            var binding = new RuntimeBinding(snapshot, directory,
                    RuntimeSnapshotBytecodeSource.fromIndexedSources(snapshot.sources(), snapshot.index()), compiler, insights);
            binding.close();
            assertFalse(snapshot.index().isDestroyed());
        }
    }

    @Test
    void failedPreparationClosesBytecodeSourceButNotTheLoadersIndex() throws Exception {
        try (var compiler = new ScriptCompilationService(message -> false, request -> false);
             var insights = new CodeInsightService(() -> null, RuntimeSourceCatalog.empty());
             var snapshot = snapshot()) {
            Path invalidHome = Files.writeString(directory.resolve("not-a-directory"), "x");
            var bytecode = RuntimeSnapshotBytecodeSource.fromIndexedSources(snapshot.sources(), snapshot.index());
            assertThrows(IOException.class, () -> new RuntimeBinding(snapshot, invalidHome, bytecode, compiler, insights));
            assertFalse(snapshot.index().isDestroyed());
            assertThrows(IllegalStateException.class, () -> bytecode.hasClass("java.lang.Object"));
        }
    }

    @Test
    void jdtHookDoesNotDisposeThePreviousOrCurrentIndex() throws Exception {
        try (var first = snapshot(); var second = snapshot()) {
            CompanionClassIndex.set(first.index());
            CompanionClassIndex.set(second.index());
            assertFalse(first.index().isDestroyed());
            CompanionClassIndex.clear();
            assertFalse(second.index().isDestroyed());
        } finally { CompanionClassIndex.clear(); }
    }

    private RuntimeIndexService.ReadySnapshot snapshot() throws Exception {
        Path cache = Files.createDirectories(directory.resolve("runtime"));
        Files.writeString(cache.resolve("inventory.json"), "{\"id\":\"test\"}");
        try (var input = Object.class.getResourceAsStream("Object.class")) {
            byte[] bytes = input.readAllBytes();
            Path classes = Files.createDirectories(directory.resolve("classes"));
            Files.createDirectories(classes.resolve("java/lang"));
            Files.write(classes.resolve("java/lang/Object.class"), bytes);
            var module = new RuntimeInventory.RuntimeModule("test", "Test", RuntimeInventory.ModuleKind.LIBRARY);
            var source = new RuntimeSnapshotBytecodeSource.Source(0, classes, classes.toUri().toString(), module);
            return new RuntimeIndexService.ReadySnapshot("test", "signature", cache.resolve("index.jindex"), List.of(source),
                    ClassIndex.fromBytes(List.of(bytes)));
        }
    }
}
