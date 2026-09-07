package com.github.minecraft_ta.totalDebugCompanion.script;

import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeIndexService.ReadySnapshot;
import com.github.minecraft_ta.totaldebug.evaluation.InMemoryCompilationException;
import com.github.minecraft_ta.totaldebug.evaluation.InMemoryJavaCompiler;
import com.github.minecraft_ta.totaldebug.evaluation.ScriptClassLoader;
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
             })) {
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
                     new IndexedJavaFileManager(standard, snapshot.index(), snapshot.sources()));
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

    private final BlockingQueue<RunScriptMessage> sent = new LinkedBlockingQueue<>();
    private final BlockingQueue<ExecutionResult> failures = new LinkedBlockingQueue<>();

    private ScriptCompilationService service() {
        return new ScriptCompilationService(this.sent::add);
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
