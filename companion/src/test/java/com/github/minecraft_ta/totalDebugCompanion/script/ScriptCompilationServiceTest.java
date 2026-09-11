package com.github.minecraft_ta.totalDebugCompanion.script;

import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeIndexService.ReadySnapshot;
import com.github.minecraft_ta.totaldebug.evaluation.InMemoryCompilationException;
import com.github.minecraft_ta.totaldebug.evaluation.InMemoryJavaCompiler;
import com.github.minecraft_ta.totaldebug.evaluation.ScriptClassLoader;
import com.github.minecraft_ta.totaldebug.evaluation.ServerManifest;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ServerManifestMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ServerSourceRequestMessage;
import java.util.HashMap;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResult;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionStatus;
import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptExecutionEnvironment;
import com.github.minecraft_ta.totaldebug.protocol.scnet.RunScriptMessage;
import com.github.minecraft_ta.totaldebug.storage.CacheFiles;
import com.github.tth05.jindex.ClassIndex;
import com.github.tth05.jindex.IndexSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeTestSources.librarySource;
import static org.junit.jupiter.api.Assertions.*;

class ScriptCompilationServiceTest {
    @TempDir Path directory;

    private static final String SOURCE = """
            import fixture.*;
            public class Probe extends ScriptProgram {
                record Result(String text) {}
                public Object run() {
                    return new Result(new Api().value("ok") + Api.pick(new int[0], new int[0]) + Api.secret);
                }
            }
            """;

    @Test
    void compilesNamedClassesWithoutScriptProgramOrExecutionTransport() throws Exception {
        try (ReadySnapshot snapshot = fixture(); var compiler = service()) {
            compiler.bind(snapshot);
            var compiled = compiler.compile("""
                    package behavior;
                    import fixture.Api;
                    public final class ItemBehavior extends Api {
                        public record Result(String text) {}
                        public Result inspect() { return new Result(value("item") + Api.secret); }
                    }
                    """, "behavior.ItemBehavior").get(10, TimeUnit.SECONDS);
            assertEquals("inventory", compiled.inventoryId());
            assertTrue(compiler.isCurrentInventory(compiled.inventoryId()));
            assertFalse(compiler.isCurrentInventory("different-inventory"));
            assertEquals("behavior.ItemBehavior", compiled.bytecode().primaryClass());
            assertEquals(2, compiled.bytecode().classes().size());
            assertTrue(compiled.bytecode().classes().containsKey("behavior.ItemBehavior$Result"));
            try (var target = new URLClassLoader(snapshot.sources().stream().map(source -> {
                try { return source.path().toUri().toURL(); }
                catch (Exception exception) { throw new AssertionError(exception); }
            }).toArray(URL[]::new), getClass().getClassLoader())) {
                Class<?> type = new ScriptClassLoader(target, compiled.bytecode().classes())
                        .loadClass("behavior.ItemBehavior");
                assertEquals("Result[text=item21]",
                        type.getMethod("inspect").invoke(type.getConstructor().newInstance()).toString());
            }
            assertTrue(this.sent.isEmpty());
            assertTrue(this.failures.isEmpty());
        }
    }

    @Test
    void compileOnlyReportsDiagnosticsAndRecoversWithFreshOutputs() throws Exception {
        try (ReadySnapshot snapshot = fixture(); var compiler = service()) {
            compiler.bind(snapshot);
            var invalid = compiler.compile("public class Broken { Missing field; }", "Broken");
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> invalid.get(10, TimeUnit.SECONDS));
            assertInstanceOf(InMemoryCompilationException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains("Missing"));
            var valid = compiler.compile("public class Valid {}", "Valid").get(10, TimeUnit.SECONDS);
            assertEquals(List.of("Valid"), List.copyOf(valid.bytecode().classes().keySet()));
            assertTrue(this.sent.isEmpty());
            assertTrue(this.failures.isEmpty());
        }
    }

    @Test
    void compilesUsingSharedIndexAndSendsAllClassesWithoutLoadingGameTypes() throws Exception {
        try (ReadySnapshot snapshot = fixture();
             var compiler = new ScriptCompilationService(message -> {
                 assertNotEquals("AWT-EventQueue-0", Thread.currentThread().getName());
                 return this.sent.add(message);
             }, this.requests::add)) {
            compiler.bind(snapshot);
            compiler.submit(7, SOURCE, false, ScriptExecutionEnvironment.POST_TICK, this.failures::add);
            RunScriptMessage message = this.sent.poll(10, TimeUnit.SECONDS);
            assertNotNull(message, () -> this.failures.toString());
            assertEquals("inventory", message.inventoryId());
            assertEquals("POST_TICK", message.executionEnvironment());
            assertEquals(2, message.bytecode().classes().size());
            assertTrue(message.bytecode().classes().containsKey("Probe$Result"));
            // Only the simulated target loads these classes. Compilation used class files, not class loading.
            try (var target = new URLClassLoader(snapshot.sources().stream().map(source -> {
                try { return source.path().toUri().toURL(); }
                catch (Exception exception) { throw new AssertionError(exception); }
            }).toArray(URL[]::new), getClass().getClassLoader())) {
                Class<?> type = new ScriptClassLoader(target, message.bytecode().classes()).loadClass("Probe");
                Object value = type.getMethod("run").invoke(type.getConstructor().newInstance());
                assertEquals("Result[text=ok721]", value.toString());
            }
            compiler.bind(null);
            assertNotNull(snapshot.index().findClass("fixture.Api"), "Compiler closed its borrowed index");
        }
    }

    @Test
    void compilationFailureAndRecoveryStayLocalAndDoNotReusePreviousOutputs() throws Exception {
        try (ReadySnapshot snapshot = fixture(); var compiler = service()) {
            compiler.bind(snapshot);
            compiler.submit(1, SOURCE.replace("Api.pick", "Api.missing"), false,
                    ScriptExecutionEnvironment.THREAD, this.failures::add);
            ExecutionResult failure = this.failures.poll(10, TimeUnit.SECONDS);
            assertNotNull(failure);
            assertEquals(ExecutionStatus.COMPILATION_FAILED, failure.status());
            assertTrue(failure.error().text().contains("missing"));
            assertTrue(this.sent.isEmpty());
            installServer(compiler, snapshot, "server-session");
            compiler.submit(1, "import fixture.ScriptProgram; public class Good extends ScriptProgram { public Object run() { return 42; } }",
                    true, ScriptExecutionEnvironment.THREAD, this.failures::add);
            RunScriptMessage message = this.sent.poll(10, TimeUnit.SECONDS);
            assertNotNull(message);
            assertTrue(message.serverSide());
            assertEquals(List.of("Good"), List.copyOf(message.bytecode().classes().keySet()));
        }
    }

    @Test
    void rejectsChangedInventoryBeforeReadingClassFiles() throws Exception {
        try (ReadySnapshot snapshot = fixture(); var compiler = service()) {
            compiler.bind(snapshot);
            Files.writeString(this.directory.resolve("inventory.json"), "{\"id\":\"changed\"}");
            compiler.submit(1, SOURCE, false, ScriptExecutionEnvironment.THREAD, this.failures::add);
            ExecutionResult failure = this.failures.poll(10, TimeUnit.SECONDS);
            assertNotNull(failure);
            assertTrue(failure.error().text().contains("Runtime cache has changed"));
            ExecutionException compileOnlyFailure = assertThrows(ExecutionException.class,
                    () -> compiler.compile(SOURCE, "Probe").get(10, TimeUnit.SECONDS));
            assertTrue(compileOnlyFailure.getCause().getMessage().contains("Runtime cache has changed"));
            assertTrue(this.sent.isEmpty());
        }
    }

    @Test
    void queuedCompileOnlyRejectsReplacedSnapshotWithoutUsingClosedIndex() throws Exception {
        try (ReadySnapshot snapshot = fixture(); var compiler = service()) {
            compiler.bind(snapshot);
            var release = new CountDownLatch(1);
            CompletableFuture<Void> writer = holdCacheLock(release);
            var compilation = compiler.compile(SOURCE, "Probe");
            try {
                CompletableFuture.runAsync(() -> compiler.bind(null)).get(2, TimeUnit.SECONDS);
                assertFalse(compiler.isCurrentInventory("inventory"));
                snapshot.close();
            } finally {
                release.countDown();
            }
            writer.get(5, TimeUnit.SECONDS);
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> compilation.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains("runtime changed"));
            assertTrue(this.sent.isEmpty());
        }
    }

    @Test
    void closingCompilerCompletesQueuedCompileOnlyFutures() throws Exception {
        try (ReadySnapshot snapshot = fixture(); var compiler = service()) {
            compiler.bind(snapshot);
            var release = new CountDownLatch(1);
            CompletableFuture<Void> writer = holdCacheLock(release);
            var first = compiler.compile(SOURCE, "Probe");
            var queued = compiler.compile("public class Queued {}", "Queued");
            try {
                CompletableFuture.runAsync(compiler::close).get(2, TimeUnit.SECONDS);
                snapshot.close();
            } finally {
                release.countDown();
            }
            writer.get(5, TimeUnit.SECONDS);
            for (var compilation : List.of(first, queued,
                    compiler.compile("public class AfterClose {}", "AfterClose"))) {
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> compilation.get(5, TimeUnit.SECONDS));
                assertInstanceOf(IllegalStateException.class, failure.getCause());
            }
            assertFalse(compiler.isCurrentInventory("inventory"));
            assertTrue(this.sent.isEmpty());
        }
    }

    @Test
    void cancellationAndIndexReplacementDoNotWaitForACacheWriterOrSendOldCode() throws Exception {
        ReadySnapshot snapshot = fixture();
        try (var compiler = service()) {
            compiler.bind(snapshot);
            var locked = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            CompletableFuture<Void> writer = CompletableFuture.runAsync(() -> {
                try {
                    CacheFiles.locked(this.directory, () -> {
                        locked.countDown();
                        assertTrue(release.await(10, TimeUnit.SECONDS));
                        return null;
                    });
                } catch (Exception exception) { throw new AssertionError(exception); }
            });
            assertTrue(locked.await(5, TimeUnit.SECONDS));
            try {
                compiler.submit(1, SOURCE, false, ScriptExecutionEnvironment.THREAD, this.failures::add);
                assertTrue(compiler.cancel(1));
                CompletableFuture.runAsync(() -> compiler.bind(null)).get(2, TimeUnit.SECONDS);
                snapshot.close();
            } finally {
                release.countDown();
            }
            writer.get(5, TimeUnit.SECONDS);
            assertEquals(ExecutionStatus.RUN_EXCEPTION, this.failures.poll(5, TimeUnit.SECONDS).status());
            assertFalse(compiler.cancel(1));
        } finally {
            snapshot.close();
        }
        assertTrue(this.sent.isEmpty());
        assertTrue(this.failures.isEmpty());
    }

    @Test
    void indexedCompilerDoesNotLeakCompanionsClasspathAndMatchesStandardCompiler() throws Exception {
        try (ReadySnapshot snapshot = fixture();
             var indexed = new InMemoryJavaCompiler(standard ->
                     new IndexedJavaFileManager(standard, snapshot.index(), snapshot.sources(), () -> null));
             var standard = new InMemoryJavaCompiler()) {
            String classpath = String.join(System.getProperty("path.separator"),
                    snapshot.sources().stream().map(source -> source.path().toString()).toList());
            Map<String, byte[]> expected = standard.compile(SOURCE, "Probe", classpath);
            Map<String, byte[]> actual = indexed.compile(SOURCE, "Probe", "");
            assertEquals(expected.keySet(), actual.keySet());
            for (String name : expected.keySet()) assertArrayEquals(expected.get(name), actual.get(name), name);
            assertThrows(InMemoryCompilationException.class, () -> indexed.compile(
                    "public class Leak { Object x = com.github.minecraft_ta.totalDebugCompanion.CompanionApp.class; }",
                    "Leak", ""));
        }
    }

    @Test
    void serverCompileChecksTheOverloadDependencyAndClientStillWorks() throws Exception {
        try (ReadySnapshot snapshot = fixture(); var compiler = service()) {
            compiler.bind(snapshot);
            installServer(compiler, new ServerManifest.Catalog(List.of(snapshot.sources().get(0).path(),
                    snapshot.sources().get(2).path())), "server-session");
            compiler.submit(1, SOURCE, true, ScriptExecutionEnvironment.THREAD, this.failures::add);
            var failure = this.failures.poll(10, TimeUnit.SECONDS);
            assertNotNull(failure);
            assertTrue(failure.error().text().contains("fixture.QuadView"), failure.error().text());
            assertTrue(this.sent.isEmpty());
            compiler.submit(2, SOURCE, false, ScriptExecutionEnvironment.THREAD, this.failures::add);
            assertNotNull(this.sent.poll(10, TimeUnit.SECONDS), () -> this.failures.toString());
        }
    }

    @Test
    void serverUsesItsOwnMethodBodyAfterCompanionCompilesAgainstLocalDeclarations() throws Exception {
        try (ReadySnapshot snapshot = fixture(); var compiler = service(); var javac = new InMemoryJavaCompiler()) {
            Path serverApi = jar("server-api.jar", javac.compile("""
                    package fixture;
                    class Base<T> { public T value(T value) { return value; } }
                    public class Api extends Base<String> {
                        private static int secret = 21;
                        public static int pick(int[] first, int[] second) { return 99; }
                        public static int pick(int[] first, QuadView second) { return 8; }
                    }
                    """, "fixture.Api", snapshot.sources().get(1).path().toString()));
            var paths = List.of(serverApi, snapshot.sources().get(1).path(), snapshot.sources().get(2).path());
            compiler.bind(snapshot);
            installServer(compiler, new ServerManifest.Catalog(paths), "server-session");
            compiler.submit(1, SOURCE, true, ScriptExecutionEnvironment.THREAD, this.failures::add);
            RunScriptMessage compiled = this.sent.poll(10, TimeUnit.SECONDS);
            assertNotNull(compiled, () -> this.failures.toString());
            assertEquals("server-session", compiled.serverSessionId());
            try (var loader = new URLClassLoader(paths.stream().map(path -> {
                try { return path.toUri().toURL(); }
                catch (Exception exception) { throw new AssertionError(exception); }
            }).toArray(URL[]::new), getClass().getClassLoader())) {
                Class<?> type = new ScriptClassLoader(loader, compiled.bytecode().classes()).loadClass("Probe");
                assertEquals("Result[text=ok9921]", type.getMethod("run").invoke(type.getConstructor().newInstance()).toString());
            }
        }
    }

    @Test
    void serverDeclarationMismatchNamesTheClassAndDoesNotSendBytecode() throws Exception {
        try (ReadySnapshot snapshot = fixture(); var compiler = service(); var javac = new InMemoryJavaCompiler()) {
            Path changed = jar("changed.jar", javac.compile("package fixture; public class Api {}", "fixture.Api", ""));
            compiler.bind(snapshot);
            installServer(compiler, new ServerManifest.Catalog(List.of(changed, snapshot.sources().get(1).path(),
                    snapshot.sources().get(2).path())), "server-session");
            compiler.submit(1, SOURCE, true, ScriptExecutionEnvironment.THREAD, this.failures::add);
            var failure = this.failures.poll(10, TimeUnit.SECONDS);
            assertNotNull(failure);
            assertTrue(failure.error().text().contains("class fixture.Api is absent or its declarations differ"), failure.error().text());
            assertTrue(this.sent.isEmpty());
        }
    }

    @Test
    void disconnectInvalidatesAQueuedServerCompilation() throws Exception {
        try (ReadySnapshot snapshot = fixture(); var compiler = service()) {
            compiler.bind(snapshot);
            installServer(compiler, snapshot, "old-session");
            var release = new CountDownLatch(1);
            var held = holdCacheLock(release);
            try {
                compiler.submit(1, SOURCE, true, ScriptExecutionEnvironment.THREAD, this.failures::add);
                compiler.acceptServerManifest(ServerManifestMessage.unavailable("Disconnected"));
            } finally { release.countDown(); }
            held.get(10, TimeUnit.SECONDS);
            assertNotNull(this.failures.poll(10, TimeUnit.SECONDS));
            assertTrue(this.sent.isEmpty());
            installServer(compiler, snapshot, "new-session");
            compiler.submit(2, SOURCE, true, ScriptExecutionEnvironment.THREAD, this.failures::add);
            assertEquals("new-session", this.sent.poll(10, TimeUnit.SECONDS).serverSessionId());
        }
    }

    @Test
    void serverCompilationRequiresACompletedHandshake() throws Exception {
        try (ReadySnapshot snapshot = fixture(); var compiler = service()) {
            compiler.bind(snapshot);
            compiler.submit(1, SOURCE, true, ScriptExecutionEnvironment.THREAD, this.failures::add);
            assertTrue(this.failures.poll(10, TimeUnit.SECONDS).error().text().contains("handshake"));
            assertTrue(this.sent.isEmpty());
        }
    }

    @Test
    void baselineArrivingBeforeIndexBindingStillCompletes() throws Exception {
        try (ReadySnapshot snapshot = fixture(); var compiler = service()) {
            var catalog = new ServerManifest.Catalog(snapshot.sources().stream().map(source -> source.path()).toList());
            byte[] bytes = catalog.baseline();
            int middle = bytes.length / 2;
            compiler.acceptServerManifest(new ServerManifestMessage("session", "", 0, bytes.length,
                    Arrays.copyOfRange(bytes, 0, middle)));
            compiler.bind(snapshot);
            compiler.acceptServerManifest(new ServerManifestMessage("session", "", middle, bytes.length,
                    Arrays.copyOfRange(bytes, middle, bytes.length)));
            finishHandshake(compiler, catalog);
            compiler.submit(1, SOURCE, true, ScriptExecutionEnvironment.THREAD, this.failures::add);
            assertNotNull(this.sent.poll(10, TimeUnit.SECONDS), () -> this.failures.toString());
            assertTrue(this.requests.isEmpty());
        }
    }

    @Test
    void pendingDetailsBlockServerOnlyAndOldRepliesCannotCompleteAReopenedComparison() throws Exception {
        try (ReadySnapshot snapshot = fixture(); var compiler = service(); var javac = new InMemoryJavaCompiler()) {
            Path changed = jar("changed.jar", javac.compile("package fixture; public class Api {}", "fixture.Api", ""));
            var catalog = new ServerManifest.Catalog(List.of(changed, snapshot.sources().get(1).path(), snapshot.sources().get(2).path()));
            compiler.bind(snapshot);
            for (var message : ServerManifestMessage.split("session", catalog.baseline())) compiler.acceptServerManifest(message);
            compiler.compile("public class Barrier {}", "Barrier").get(10, TimeUnit.SECONDS);
            var old = this.requests.remove();
            compiler.submit(1, SOURCE, true, ScriptExecutionEnvironment.THREAD, this.failures::add);
            assertTrue(this.failures.poll(10, TimeUnit.SECONDS).error().text().contains("Comparing server source"));
            compiler.submit(2, SOURCE, false, ScriptExecutionEnvironment.THREAD, this.failures::add);
            assertNotNull(this.sent.poll(10, TimeUnit.SECONDS));

            // The same server session is replayed when Companion reconnects; request identity must still change.
            compiler.runtimeDisconnected();
            for (var message : ServerManifestMessage.split("session", catalog.baseline())) compiler.acceptServerManifest(message);
            compiler.compile("public class Barrier {}", "Barrier").get(10, TimeUnit.SECONDS);
            var current = this.requests.remove();
            assertNotEquals(old.requestId(), current.requestId());
            for (var message : ServerManifestMessage.split(old.sessionId(), old.requestId(), old.source(), catalog.details(old.source()))) {
                compiler.acceptServerManifest(message);
            }
            compiler.compile("public class Barrier {}", "Barrier").get(10, TimeUnit.SECONDS);
            compiler.submit(3, SOURCE, true, ScriptExecutionEnvironment.THREAD, this.failures::add);
            assertTrue(this.failures.poll(10, TimeUnit.SECONDS).error().text().contains("Comparing server source"));
            this.requests.add(current);
            finishHandshake(compiler, catalog);
            compiler.submit(4, SOURCE, true, ScriptExecutionEnvironment.THREAD, this.failures::add);
            assertTrue(this.failures.poll(10, TimeUnit.SECONDS).error().text().contains("class fixture.Api"));
            assertTrue(this.sent.isEmpty());
        }
    }

    @Test
    void rebindDiscardsCompletedResultAndRecomparesTheRetainedBaseline() throws Exception {
        try (ReadySnapshot snapshot = fixture(); var compiler = service()) {
            compiler.bind(snapshot);
            installServer(compiler, snapshot, "session");
            var release = new CountDownLatch(1);
            var held = holdCacheLock(release);
            try {
                compiler.bind(snapshot);
                compiler.submit(1, SOURCE, true, ScriptExecutionEnvironment.THREAD, this.failures::add);
                assertTrue(this.failures.poll(5, TimeUnit.SECONDS).error().text().contains("handshake"));
            } finally { release.countDown(); }
            held.get(10, TimeUnit.SECONDS);
            compiler.compile("public class Barrier {}", "Barrier").get(10, TimeUnit.SECONDS);
            compiler.submit(2, SOURCE, true, ScriptExecutionEnvironment.THREAD, this.failures::add);
            assertNotNull(this.sent.poll(10, TimeUnit.SECONDS), () -> this.failures.toString());
        }
    }

    private void installServer(ScriptCompilationService compiler, ReadySnapshot snapshot, String session) throws Exception {
        installServer(compiler, new ServerManifest.Catalog(snapshot.sources().stream().map(source -> source.path()).toList()), session);
    }

    private void installServer(ScriptCompilationService compiler, ServerManifest.Catalog manifest, String session) throws Exception {
        for (var message : ServerManifestMessage.split(session, manifest.baseline())) compiler.acceptServerManifest(message);
        finishHandshake(compiler, manifest);
    }

    private void finishHandshake(ScriptCompilationService compiler, ServerManifest.Catalog manifest) throws Exception {
        for (;;) {
            // Drain queued comparison work without sleeping or reaching into service internals.
            compiler.compile("public class Barrier {}", "Barrier").get(10, TimeUnit.SECONDS);
            var request = this.requests.poll();
            if (request == null) return;
            for (var message : ServerManifestMessage.split(request.sessionId(), request.requestId(), request.source(),
                    manifest.details(request.source()))) compiler.acceptServerManifest(message);
        }
    }

    private final BlockingQueue<ServerSourceRequestMessage> requests = new LinkedBlockingQueue<>();
    private final BlockingQueue<RunScriptMessage> sent = new LinkedBlockingQueue<>();
    private final BlockingQueue<ExecutionResult> failures = new LinkedBlockingQueue<>();

    private ScriptCompilationService service() {
        return new ScriptCompilationService(this.sent::add, this.requests::add);
    }

    private CompletableFuture<Void> holdCacheLock(CountDownLatch release) throws Exception {
        var locked = new CountDownLatch(1);
        CompletableFuture<Void> writer = CompletableFuture.runAsync(() -> {
            try {
                CacheFiles.locked(this.directory, () -> {
                    locked.countDown();
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                    return null;
                });
            } catch (Exception exception) { throw new AssertionError(exception); }
        });
        assertTrue(locked.await(5, TimeUnit.SECONDS));
        return writer;
    }

    private ReadySnapshot fixture() throws Exception {
        Path dependency;
        Path api;
        Path program;
        try (var compiler = new InMemoryJavaCompiler()) {
            dependency = jar("dependency.jar", compiler.compile(
                    "package fixture; public interface QuadView {}", "fixture.QuadView", ""));
            api = jar("api.jar", compiler.compile("""
                    package fixture;
                    class Base<T> { public T value(T value) { return value; } }
                    public class Api extends Base<String> {
                        private static int secret = 21;
                        public static int pick(int[] first, int[] second) { return 7; }
                        public static int pick(int[] first, QuadView second) { return 8; }
                    }
                    """, "fixture.Api", dependency.toString()));
            program = jar("program.jar", compiler.compile(
                    "package fixture; public abstract class ScriptProgram { public abstract Object run(); }",
                    "fixture.ScriptProgram", ""));
        }
        Files.writeString(this.directory.resolve("inventory.json"), "{\"id\":\"inventory\"}");
        ClassIndex index = ClassIndex.fromSources(List.of(IndexSource.archive(0, api.toString()),
                IndexSource.archive(1, dependency.toString()), IndexSource.archive(2, program.toString())));
        return new ReadySnapshot("inventory", "signature", this.directory.resolve("index.jindex"),
                List.of(librarySource(0, api), librarySource(1, dependency), librarySource(2, program)), index);
    }

    private Path jar(String name, Map<String, byte[]> classes) throws IOException {
        Path path = this.directory.resolve(name);
        try (var output = new JarOutputStream(Files.newOutputStream(path))) {
            for (var entry : classes.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey().replace('.', '/') + ".class"));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return path;
    }
}
