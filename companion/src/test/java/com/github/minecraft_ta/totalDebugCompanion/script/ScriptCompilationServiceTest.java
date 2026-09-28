package com.github.minecraft_ta.totalDebugCompanion.script;

import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeIndexService.ReadySnapshot;
import com.github.minecraft_ta.totaldebug.evaluation.InMemoryCompilationException;
import com.github.minecraft_ta.totaldebug.evaluation.InMemoryJavaCompiler;
import com.github.minecraft_ta.totaldebug.evaluation.ScriptClassLoader;
import com.github.minecraft_ta.totaldebug.evaluation.ScriptReferences;
import com.github.minecraft_ta.totaldebug.protocol.Side;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResult;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionStatus;
import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptExecutionEnvironment;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;
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
             })) {
            compiler.bind(snapshot);
            compiler.submit(7, SOURCE, Side.CLIENT, ScriptExecutionEnvironment.POST_TICK, outcome -> this.failures.add(outcome.result()));
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
    void readinessSaysWhyARunCannotStartAndSignalsTheNextChange() throws Exception {
        try (ReadySnapshot snapshot = fixture();
             var compiler = new ScriptCompilationService(this.sent::add)) {
            ScriptCompilationService.Readiness unbound = compiler.readiness(Side.CLIENT);
            assertFalse(unbound.ready());
            assertEquals("The runtime class index is not ready for compilation", unbound.detail());
            assertFalse(unbound.changed().isDone());

            compiler.bind(snapshot);

            assertTrue(unbound.changed().isDone(), "binding the index is a change");
            assertTrue(compiler.readiness(Side.CLIENT).ready(), "client runs need only the index");
            ScriptCompilationService.Readiness server = compiler.readiness(Side.SERVER);
            assertFalse(server.ready());
            assertEquals(ScriptCompilationService.NO_SERVER, server.detail());

            compiler.serverAccess("");

            assertTrue(server.changed().isDone(), "the server's answer is a change");
            assertTrue(compiler.readiness(Side.SERVER).ready());
            server = compiler.readiness(Side.SERVER);

            compiler.runtimeDisconnected();

            assertTrue(server.changed().isDone());
            assertEquals("Minecraft disconnected", compiler.readiness(Side.SERVER).detail());
        }
    }

    @Test
    void sendsTheSubjectARunIsBoundTo() throws Exception {
        try (ReadySnapshot snapshot = fixture();
             var compiler = new ScriptCompilationService(this.sent::add)) {
            compiler.bind(snapshot);
            compiler.submit(8, SOURCE, Side.CLIENT, ScriptExecutionEnvironment.POST_TICK,
                    new ScriptSubject(SubjectRef.parseOccurrence("entity 0f8fad5b-d9cb-469f-a165-70867728950e"), "game-session",
                            "minecraft:pig"),
                    outcome -> this.failures.add(outcome.result()));
            RunScriptMessage message = this.sent.poll(10, TimeUnit.SECONDS);

            assertNotNull(message, () -> this.failures.toString());
            assertEquals("entity 0f8fad5b-d9cb-469f-a165-70867728950e", message.subject());
            assertEquals("game-session", message.subjectSessionId());
            assertEquals("minecraft:pig", message.subjectExpectedId());
        }
    }

    @Test
    void reusesBytecodeForIdenticalSourceUntilTheRuntimeChanges() throws Exception {
        try (ReadySnapshot snapshot = fixture();
             var compiler = new ScriptCompilationService(this.sent::add)) {
            compiler.bind(snapshot);
            compiler.submit(9, SOURCE, Side.CLIENT, ScriptExecutionEnvironment.POST_TICK, outcome -> this.failures.add(outcome.result()));
            RunScriptMessage first = this.sent.poll(10, TimeUnit.SECONDS);
            compiler.submit(10, SOURCE, Side.CLIENT, ScriptExecutionEnvironment.POST_TICK, outcome -> this.failures.add(outcome.result()));
            RunScriptMessage second = this.sent.poll(10, TimeUnit.SECONDS);

            assertNotNull(first, () -> this.failures.toString());
            assertNotNull(second, () -> this.failures.toString());
            assertSame(first.bytecode(), second.bytecode());

            compiler.bind(snapshot);
            compiler.submit(11, SOURCE, Side.CLIENT, ScriptExecutionEnvironment.POST_TICK, outcome -> this.failures.add(outcome.result()));
            RunScriptMessage rebound = this.sent.poll(10, TimeUnit.SECONDS);
            assertNotNull(rebound, () -> this.failures.toString());
            assertNotSame(first.bytecode(), rebound.bytecode());
        }
    }

    @Test
    void compilationFailureAndRecoveryStayLocalAndDoNotReusePreviousOutputs() throws Exception {
        try (ReadySnapshot snapshot = fixture(); var compiler = service()) {
            compiler.bind(snapshot);
            var outcomes = new LinkedBlockingQueue<ScriptCompilationService.Failure>();
            compiler.submit(1, SOURCE.replace("Api.pick", "Api.missing"), Side.CLIENT,
                    ScriptExecutionEnvironment.THREAD, outcomes::add);
            var outcome = outcomes.poll(10, TimeUnit.SECONDS);
            assertNotNull(outcome);
            assertFalse(outcome.diagnostics().isEmpty());
            assertTrue(outcome.diagnostics().stream().anyMatch(problem -> problem.start() >= 0 && problem.message().contains("missing")));
            ExecutionResult failure = outcome.result();
            assertNotNull(failure);
            assertEquals(ExecutionStatus.COMPILATION_FAILED, failure.status());
            assertTrue(failure.error().text().contains("missing"));
            assertTrue(this.sent.isEmpty());
            compiler.serverAccess("");
            compiler.submit(1, "import fixture.ScriptProgram; public class Good extends ScriptProgram { public Object run() { return 42; } }",
                    Side.SERVER, ScriptExecutionEnvironment.THREAD, recovered -> this.failures.add(recovered.result()));
            RunScriptMessage message = this.sent.poll(10, TimeUnit.SECONDS);
            assertNotNull(message);
            assertEquals(Side.SERVER, message.side());
            assertEquals(List.of("Good"), List.copyOf(message.bytecode().classes().keySet()));
        }
    }

    @Test
    void rejectsChangedInventoryBeforeReadingClassFiles() throws Exception {
        try (ReadySnapshot snapshot = fixture(); var compiler = service()) {
            compiler.bind(snapshot);
            Files.writeString(this.directory.resolve("inventory.json"), "{\"id\":\"changed\"}");
            compiler.submit(1, SOURCE, Side.CLIENT, ScriptExecutionEnvironment.THREAD, outcome -> this.failures.add(outcome.result()));
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
                compiler.submit(1, SOURCE, Side.CLIENT, ScriptExecutionEnvironment.THREAD, outcome -> this.failures.add(outcome.result()));
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

    @Test
    void theServerRunsItsOwnMethodBodiesAndNamesTheDeclarationsItLacks() throws Exception {
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
            Path lacking = jar("lacking-api.jar", javac.compile("""
                    package fixture;
                    class Base<T> { public T value(T value) { return value; } }
                    public class Api extends Base<String> {
                        private static int secret = 21;
                        public static int pick(int[] first, QuadView second) { return 8; }
                    }
                    """, "fixture.Api", snapshot.sources().get(1).path().toString()));
            var paths = List.of(serverApi, snapshot.sources().get(1).path(), snapshot.sources().get(2).path());
            compiler.bind(snapshot);
            compiler.serverAccess("");
            compiler.submit(1, SOURCE, Side.SERVER, ScriptExecutionEnvironment.THREAD, outcome -> this.failures.add(outcome.result()));
            RunScriptMessage compiled = this.sent.poll(10, TimeUnit.SECONDS);
            assertNotNull(compiled, () -> this.failures.toString());
            ScriptReferences references = ScriptReferences.read(compiled.bytecode().classes());
            try (var loader = loader(paths)) {
                assertEquals(List.of(), references.unresolved(loader), "the same declarations link");
                Class<?> type = new ScriptClassLoader(loader, compiled.bytecode().classes()).loadClass("Probe");
                assertEquals("Result[text=ok9921]", type.getMethod("run").invoke(type.getConstructor().newInstance()).toString());
            }
            try (var loader = loader(List.of(lacking, snapshot.sources().get(1).path(), snapshot.sources().get(2).path()))) {
                assertEquals(List.of("fixture.Api.pick(int[], int[])"), references.unresolved(loader),
                        "the overload the script calls, although the server has another");
            }
        }
    }

    @Test
    void aServerCompilationQueuedBeforeTheServerChangedIsNotSent() throws Exception {
        try (ReadySnapshot snapshot = fixture(); var compiler = service()) {
            compiler.bind(snapshot);
            compiler.serverAccess("");
            var release = new CountDownLatch(1);
            var held = holdCacheLock(release);
            try {
                compiler.submit(1, SOURCE, Side.SERVER, ScriptExecutionEnvironment.THREAD, outcome -> this.failures.add(outcome.result()));
                compiler.serverAccess("Waiting for the server");
            } finally { release.countDown(); }
            held.get(10, TimeUnit.SECONDS);
            assertNotNull(this.failures.poll(10, TimeUnit.SECONDS));
            assertTrue(this.sent.isEmpty());
            compiler.serverAccess("");
            compiler.submit(2, SOURCE, Side.SERVER, ScriptExecutionEnvironment.THREAD, outcome -> this.failures.add(outcome.result()));
            assertEquals(2, this.sent.poll(10, TimeUnit.SECONDS).scriptId());
        }
    }

    @Test
    void aServerRunNeedsTheServersPermissionAndNamesItsRefusal() throws Exception {
        try (ReadySnapshot snapshot = fixture(); var compiler = service()) {
            compiler.bind(snapshot);
            compiler.submit(1, SOURCE, Side.SERVER, ScriptExecutionEnvironment.THREAD, outcome -> this.failures.add(outcome.result()));
            assertEquals(ScriptCompilationService.NO_SERVER, this.failures.poll(10, TimeUnit.SECONDS).error().text());
            compiler.serverAccess("Server-side scripts are disabled by the server configuration");
            compiler.submit(2, SOURCE, Side.SERVER, ScriptExecutionEnvironment.THREAD, outcome -> this.failures.add(outcome.result()));
            assertEquals("Server-side scripts are disabled by the server configuration",
                    this.failures.poll(10, TimeUnit.SECONDS).error().text());
            assertTrue(this.sent.isEmpty());
        }
    }

    private URLClassLoader loader(List<Path> paths) {
        return new URLClassLoader(paths.stream().map(path -> {
            try { return path.toUri().toURL(); }
            catch (Exception exception) { throw new AssertionError(exception); }
        }).toArray(URL[]::new), getClass().getClassLoader());
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

    private ReadySnapshot fixture() throws Exception { return fixture(directory); }

    private static Map<String, byte[]> fixtureArchives;

    static synchronized ReadySnapshot fixture(Path directory) throws Exception {
        Path dependency = directory.resolve("dependency.jar");
        Path api = directory.resolve("api.jar");
        Path program = directory.resolve("program.jar");
        if (fixtureArchives == null) {
            try (var compiler = new InMemoryJavaCompiler()) {
                dependency = jar(directory, "dependency.jar", compiler.compile(
                        "package fixture; public interface QuadView {}", "fixture.QuadView", ""));
                api = jar(directory, "api.jar", compiler.compile("""
                        package fixture;
                        class Base<T> { public T value(T value) { return value; } }
                        public class Api extends Base<String> {
                            private static int secret = 21;
                            public static int pick(int[] first, int[] second) { return 7; }
                            public static int pick(int[] first, QuadView second) { return 8; }
                        }
                        """, "fixture.Api", dependency.toString()));
                program = jar(directory, "program.jar", compiler.compile(
                        "package fixture; public abstract class ScriptProgram { public abstract Object run(); }",
                        "fixture.ScriptProgram", ""));
            }
            fixtureArchives = Map.of("dependency.jar", Files.readAllBytes(dependency),
                    "api.jar", Files.readAllBytes(api), "program.jar", Files.readAllBytes(program));
        }
        for (var archive : fixtureArchives.entrySet()) Files.write(directory.resolve(archive.getKey()), archive.getValue());
        Files.writeString(directory.resolve("inventory.json"), "{\"id\":\"inventory\"}");
        ClassIndex index = ClassIndex.fromSources(List.of(IndexSource.archive(0, api.toString()),
                IndexSource.archive(1, dependency.toString()), IndexSource.archive(2, program.toString())));
        return new ReadySnapshot("inventory", "signature", directory.resolve("index.jindex"),
                List.of(librarySource(0, api), librarySource(1, dependency), librarySource(2, program)), index);
    }

    private Path jar(String name, Map<String, byte[]> classes) throws IOException { return jar(directory, name, classes); }

    private static Path jar(Path directory, String name, Map<String, byte[]> classes) throws IOException {
        Path path = directory.resolve(name);
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
