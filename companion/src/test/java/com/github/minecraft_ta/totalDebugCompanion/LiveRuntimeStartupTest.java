package com.github.minecraft_ta.totalDebugCompanion;

import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeIndexService;
import com.github.minecraft_ta.totalDebugCompanion.script.ScriptCompilationService;
import com.github.minecraft_ta.totalDebugCompanion.script.ScriptExecutionService;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionLaunchConfiguration;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionSession;
import com.github.minecraft_ta.totaldebug.protocol.CompanionProtocol;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ClientHelloMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ProtocolBindings;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ReadyMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.RuntimeInventoryMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ServerHelloMessage;
import com.github.minecraft_ta.totaldebug.storage.CacheFiles;
import com.github.minecraft_ta.totaldebug.storage.CompanionSessionDescriptor;
import com.github.minecraft_ta.totaldebug.storage.InstancePaths;
import com.github.minecraft_ta.totaldebug.storage.RuntimeInventory;
import com.github.tth05.scnet.Client;
import com.github.tth05.scnet.IConnectionListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import javax.swing.Action;
import javax.swing.SwingUtilities;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class LiveRuntimeStartupTest {
    private static final String TOKEN = "live-runtime-startup-test-token-abcdefghijklmnopqrstuvwxyz";
    @TempDir Path root;

    @Test void restoredInventoryCannotCompileDuringLiveStartupAndTheNewInventoryBecomesUsable() throws Exception {
        var cachedA = cache("A", "oldOnly");
        var cachedB = cache("B", "newOnly");
        var profile = CompanionProfile.forGame(Files.createDirectories(root.resolve("game")));
        var paths = new InstancePaths(profile.dataDirectory());
        install(cachedA, paths);
        var config = new CompanionLaunchConfiguration(root.resolve("app"));
        GlobalConfig.getInstance().loadFrom(config.appHome());
        CompanionApp.configureLookAndFeel();
        try (var app = new CompanionApplication(config, TOKEN)) {
            app.session().bindAndPublish(config);
            app.openProject(profile).get(10, TimeUnit.SECONDS);
            await(() -> installed(app, "A"));
            var compiler = field(app, "scriptCompiler", ScriptCompilationService.class);
            var executions = field(app, "scriptExecutions", ScriptExecutionService.class);
            assertEquals("A", compiler.compile(probe("oldOnly"), "Probe").get(5, TimeUnit.SECONDS).inventoryId());
            var createAction = new FutureTask<>(() -> field(app.createWindow(), "evaluateExpressionAction", Action.class));
            SwingUtilities.invokeAndWait(createAction);
            var evaluate = createAction.get();

            try (var game = new Game(app, profile, config)) {
                assertTrue(app.isConnected());
                assertNotReady(executions, compiler, "Authentication alone must not authorize the restored inventory A");
                awaitActionEnabled(evaluate, false);
                game.inventory(RuntimeInventoryMessage.preparing("Preparing the new live inventory"));
                assertNotReady(executions, compiler, "PREPARING must not authorize cached inventory A");
                awaitActionEnabled(evaluate, false);

                try (var publication = new HeldCache(paths, () -> { copy(cachedB, paths); return null; })) {
                    game.inventory(RuntimeInventoryMessage.available("B", paths.inventory().toString()));
                    assertNotReady(executions, compiler, "AVAILABLE cannot enable execution until inventory B is installed");
                    awaitActionEnabled(evaluate, false);
                    publication.release();
                    await(() -> installed(app, "B") && executions.isReady());
                    awaitActionEnabled(evaluate, true);
                }
                assertEquals("B", compiler.compile(probe("newOnly"), "Probe").get(5, TimeUnit.SECONDS).inventoryId());
                assertThrows(ExecutionException.class, () -> compiler.compile(probe("oldOnly"), "Probe").get(5, TimeUnit.SECONDS),
                        "The replacement compiler must resolve B's sources, not A's");
            }

            await(() -> !app.isConnected() && !app.session().server().isClientConnected());
            var retained = app.requireProject().requireRuntime();
            try (var reconnected = new Game(app, profile, config)) {
                assertNotReady(executions, compiler, "A reconnect must confirm its inventory even when the cached ID will match");
                awaitActionEnabled(evaluate, false);
                reconnected.inventory(RuntimeInventoryMessage.available("B", paths.inventory().toString()));
                await(executions::isReady);
                awaitActionEnabled(evaluate, true);
                assertEquals("B", compiler.compile(probe("newOnly"), "Probe").get(5, TimeUnit.SECONDS).inventoryId());
                assertSame(retained, app.requireProject().requireRuntime(), "Matching live identity should reuse the retained binding");
            }
        }
    }

    @Test void aPendingOfflineRestoreCannotReenableCompilationAfterLivePreparationStarts() throws Exception {
        var cachedA = cache("A", "oldOnly");
        var profile = CompanionProfile.forGame(Files.createDirectories(root.resolve("game")));
        var paths = new InstancePaths(profile.dataDirectory());
        install(cachedA, paths);
        var config = new CompanionLaunchConfiguration(root.resolve("app"));
        try (var app = new CompanionApplication(config, TOKEN);
             var restoreGate = new HeldCache(paths, () -> null)) {
            app.session().bindAndPublish(config);
            app.openProject(profile).get(10, TimeUnit.SECONDS);
            try (var game = new Game(app, profile, config)) {
                game.inventory(RuntimeInventoryMessage.preparing("Live runtime is still preparing"));
                restoreGate.release();
                var loader = field(app, "runtimeIndexService", RuntimeIndexService.class);
                field(loader, "worker", ExecutorService.class).submit(() -> { }).get(10, TimeUnit.SECONDS);
                assertNotReady(field(app, "scriptExecutions", ScriptExecutionService.class),
                        field(app, "scriptCompiler", ScriptCompilationService.class),
                        "Late cached restore A must not make the preparing live game executable");
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void disconnectBeforeLiveInventoryResumesInterruptedOfflineIndexing(boolean localFallback, boolean failedInventory) throws Exception {
        var profile = CompanionProfile.forGame(Files.createDirectories(root.resolve("game")));
        var paths = new InstancePaths(profile.dataDirectory());
        if (localFallback) {
            Path archive = Files.createDirectories(profile.workspaceDirectory().resolve("mods")).resolve("sample.jar");
            try (var output = new ZipOutputStream(Files.newOutputStream(archive))) {
                output.putNextEntry(new ZipEntry("sample/LiveVersion.class"));
                output.write(classBytes("oldOnly"));
                output.closeEntry();
            }
        } else {
            install(cache("A", "oldOnly"), paths);
        }
        var config = new CompanionLaunchConfiguration(root.resolve("app"));
        try (var app = new CompanionApplication(config, TOKEN);
             var restoreGate = new HeldCache(paths, () -> null)) {
            app.session().bindAndPublish(config);
            app.openProject(profile).get(10, TimeUnit.SECONDS);
            var executions = field(app, "scriptExecutions", ScriptExecutionService.class);
            try (var game = new Game(app, profile, config)) {
                game.inventory(RuntimeInventoryMessage.preparing("Live runtime is still preparing"));
                if (failedInventory) {
                    game.inventory(RuntimeInventoryMessage.failed("Runtime export failed"));
                    assertEquals(RuntimeIndexService.Phase.FAILED, app.getRuntimeIndexStatus().phase());
                }
                assertNull(app.requireProject().runtime(), "The held offline index must not have installed yet");
                assertFalse(executions.isReady());
            }
            await(() -> !app.session().hasClient() && !app.isConnected());
            restoreGate.release();
            await(() -> app.getRuntimeIndexStatus().phase() == RuntimeIndexService.Phase.READY
                    && app.requireProject().runtime() != null);
            var snapshot = app.requireProject().requireRuntime().snapshot();
            assertEquals(!localFallback, snapshot.isRuntime());
            assertNotNull(snapshot.index().findClass("sample", "LiveVersion"), "Offline browsing must recover without manual retry");
            assertFalse(executions.isReady(), "An offline index must not enable game execution");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void offlineActionsDuringAuthenticationCannotRestoreUnconfirmedCompilation(boolean retryIndex) throws Exception {
        var cachedA = cache("A", "oldOnly");
        var profile = CompanionProfile.forGame(Files.createDirectories(root.resolve("game")));
        install(cachedA, new InstancePaths(profile.dataDirectory()));
        var config = new CompanionLaunchConfiguration(root.resolve("app"));
        try (var app = new CompanionApplication(config, TOKEN)) {
            app.session().bindAndPublish(config);
            app.openProject(profile).get(10, TimeUnit.SECONDS);
            await(() -> installed(app, "A"));
            var admitted = new CountDownLatch(1);
            var releaseHandshake = new CountDownLatch(1);
            var attachment = CompanionSession.class.getDeclaredField("attachmentHandler");
            attachment.setAccessible(true);
            var original = (CompanionSession.AttachmentHandler) attachment.get(app.session());
            attachment.set(app.session(), (CompanionSession.AttachmentHandler) hello -> {
                original.attach(hello);
                admitted.countDown();
                try {
                    if (!releaseHandshake.await(10, TimeUnit.SECONDS)) throw new IOException("Handshake gate timed out");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Handshake gate interrupted", failure);
                }
            });
            try (var game = new Game(app, profile, config, () -> {
                try {
                    assertTrue(admitted.await(5, TimeUnit.SECONDS));
                    (retryIndex ? app.retryIndex() : app.openProject(profile)).get(5, TimeUnit.SECONDS);
                    var loader = field(app, "runtimeIndexService", RuntimeIndexService.class);
                    field(loader, "worker", ExecutorService.class).submit(() -> { }).get(5, TimeUnit.SECONDS);
                } finally {
                    releaseHandshake.countDown();
                }
                return null;
            })) {
                var executions = field(app, "scriptExecutions", ScriptExecutionService.class);
                assertNotReady(executions, field(app, "scriptCompiler", ScriptCompilationService.class),
                        "Offline actions must not restore a cache after live admission has suspended it");
                game.inventory(RuntimeInventoryMessage.available("A", new InstancePaths(profile.dataDirectory()).inventory().toString()));
                await(executions::isReady);
            } finally {
                releaseHandshake.countDown();
            }
        }
    }

    private static void assertNotReady(ScriptExecutionService executions, ScriptCompilationService compiler, String message) throws Exception {
        assertFalse(executions.isReady(), message);
        var failure = assertThrows(ExecutionException.class, () -> compiler.compile(probe("oldOnly"), "Probe").get(5, TimeUnit.SECONDS));
        assertTrue(failure.getCause().getMessage().contains("not ready"), "Reject admission before reading a superseded cache: " + failure.getCause());
    }

    private static void awaitActionEnabled(Action action, boolean expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean enabled;
        do {
            var read = new FutureTask<>(action::isEnabled);
            SwingUtilities.invokeAndWait(read);
            enabled = read.get();
            if (enabled == expected) return;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        assertEquals(expected, enabled, "Evaluate action must follow live runtime readiness");
    }

    private static String probe(String method) {
        return "import sample.LiveVersion; public class Probe { public static int value() { return LiveVersion." + method + "(); } }";
    }

    private InstancePaths cache(String identity, String method) throws Exception {
        Path classes = Files.createDirectories(root.resolve("sources-" + identity).resolve("sample"));
        Files.write(classes.resolve("LiveVersion.class"), classBytes(method));
        var paths = new InstancePaths(root.resolve("cached-" + identity));
        Path source = classes.getParent();
        var module = new RuntimeInventory.RuntimeModule("sample", "Sample", RuntimeInventory.ModuleKind.MOD);
        new RuntimeInventory(identity, System.getProperty("java.runtime.version"), System.getProperty("java.home"), false,
                List.of(new RuntimeInventory.Source(RuntimeInventory.SourceKind.DIRECTORY, source, source.toUri().toString(), module)))
                .write(paths.inventory());
        var ready = new CompletableFuture<RuntimeIndexService.ReadySnapshot>();
        try (var loader = new RuntimeIndexService(new Object(), ready::complete)) {
            loader.addStatusListener(status -> {
                if (status.phase() == RuntimeIndexService.Phase.FAILED) ready.completeExceptionally(status.failure());
            });
            loader.restore(paths.home());
            try (var snapshot = ready.get(15, TimeUnit.SECONDS)) { assertEquals(identity, snapshot.inventoryId()); }
        }
        return paths;
    }

    private static byte[] classBytes(String method) {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "sample/LiveVersion", null, "java/lang/Object", null);
        var body = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, method, "()I", null, null);
        body.visitCode();
        body.visitInsn(Opcodes.ICONST_1);
        body.visitInsn(Opcodes.IRETURN);
        body.visitMaxs(1, 0);
        body.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void install(InstancePaths cached, InstancePaths target) throws Exception {
        CacheFiles.locked(target.runtime(), () -> { copy(cached, target); return null; });
    }

    private static void copy(InstancePaths cached, InstancePaths target) throws IOException {
        Files.copy(cached.inventory(), target.inventory(), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(cached.index(), target.index(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static boolean installed(CompanionApplication app, String identity) {
        var runtime = app.requireProject().runtime();
        return runtime != null && identity.equals(runtime.snapshot().inventoryId())
                && app.getRuntimeIndexStatus().phase() == RuntimeIndexService.Phase.READY;
    }

    private static <T> T field(Object owner, String name, Class<T> type) throws ReflectiveOperationException {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(owner));
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Live runtime did not reach the expected state");
    }

    private static final class HeldCache implements AutoCloseable {
        private final CountDownLatch release = new CountDownLatch(1);
        private final CompletableFuture<Void> writer;

        HeldCache(InstancePaths paths, CacheFiles.Operation<Void, Exception> publish) throws Exception {
            var entered = new CountDownLatch(1);
            writer = CompletableFuture.runAsync(() -> {
                try {
                    CacheFiles.locked(paths.runtime(), () -> {
                        publish.run();
                        entered.countDown();
                        assertTrue(release.await(15, TimeUnit.SECONDS));
                        return null;
                    });
                } catch (Exception failure) { throw new AssertionError(failure); }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS), "Cache gate was not acquired");
        }

        void release() throws Exception { release.countDown(); writer.get(5, TimeUnit.SECONDS); }
        @Override public void close() throws Exception { release(); }
    }

    private static final class Game implements AutoCloseable {
        private final CompanionApplication app;
        private final Client client = new Client();

        Game(CompanionApplication app, CompanionProfile profile, CompanionLaunchConfiguration config) throws Exception {
            this(app, profile, config, () -> null);
        }

        Game(CompanionApplication app, CompanionProfile profile, CompanionLaunchConfiguration config, Callable<Void> whileConnecting) throws Exception {
            this.app = app;
            var ready = new CompletableFuture<Void>();
            var attached = new CompletableFuture<Void>();
            app.session().server().getMessageBus().listenOnce(ClientHelloMessage.class, ignored -> attached.complete(null));
            ProtocolBindings.registerMod(client.getMessageProcessor());
            client.getMessageBus().listenAlways(ServerHelloMessage.class, hello -> {
                if (!hello.accepted()) ready.completeExceptionally(new IOException(hello.rejectionReason()));
            });
            client.getMessageBus().listenAlways(ReadyMessage.class, ignored -> ready.complete(null));
            client.addConnectionListener(new IConnectionListener() {
                @Override public void onConnected() {
                    client.getMessageProcessor().enqueueMessage(new ClientHelloMessage(CompanionProtocol.VERSION, TOKEN, profile.id(),
                            profile.dataDirectory().toString(), profile.workspaceDirectory().toString()));
                }
                @Override public void onDisconnected() { }
            });
            var descriptor = CompanionSessionDescriptor.read(config.descriptorFile(), CompanionProtocol.VERSION);
            assertTrue(client.connect(new InetSocketAddress("127.0.0.1", descriptor.port())));
            whileConnecting.call();
            ready.get(5, TimeUnit.SECONDS);
            attached.get(5, TimeUnit.SECONDS);
        }

        void inventory(RuntimeInventoryMessage message) throws Exception {
            var processed = new CompletableFuture<Void>();
            Consumer<RuntimeInventoryMessage> barrier = ignored -> processed.complete(null);
            app.session().server().getMessageBus().listenAlways(RuntimeInventoryMessage.class, barrier);
            try {
                client.getMessageProcessor().enqueueMessage(message);
                processed.get(5, TimeUnit.SECONDS);
            } finally { app.session().server().getMessageBus().unregister(RuntimeInventoryMessage.class, barrier); }
        }

        @Override public void close() { client.close(); }
    }
}
