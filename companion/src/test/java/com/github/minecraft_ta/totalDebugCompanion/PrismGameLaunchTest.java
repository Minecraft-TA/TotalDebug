package com.github.minecraft_ta.totalDebugCompanion;

import com.github.minecraft_ta.totalDebugCompanion.testui.UiTest;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionLaunchConfiguration;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.MainWindow;
import com.github.minecraft_ta.totaldebug.protocol.CompanionProtocol;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ClientHelloMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ProtocolBindings;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ReadyMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ServerHelloMessage;
import com.github.minecraft_ta.totaldebug.storage.CompanionSessionDescriptor;
import com.github.tth05.scnet.Client;
import com.github.tth05.scnet.IConnectionListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JButton;
import javax.swing.SwingUtilities;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class PrismGameLaunchTest {
    @TempDir Path root;
    private static final String TOKEN = "prism-launch-test-token-abcdefghijklmnopqrstuvwxyz";

    @Test void launcherHandoffIsNotSuccessAndDuplicateClicksWaitForTheSelectedGame() throws Exception {
        try (var fixture = new Fixture()) {
            var app = fixture.app;
            var requested = app.launchGame(app.requireProject());
            assertSame(requested, app.launchGame(app.requireProject()));
            await(() -> fixture.starts.get() == 1);
            fixture.process.exit(0);
            assertFalse(requested.isDone(), "Prism can exit after handing off to its existing process");
            var other = CompanionProfile.forGame(Files.createDirectories(root.resolve("other")));
            try (var wrong = fixture.connect(other, false)) {
                assertFalse(requested.isDone(), "Another instance cannot satisfy the launch");
            }
            await(() -> !app.session().hasClient());
            try (var game = fixture.connect(fixture.profile, true)) {
                requested.get(5, TimeUnit.SECONDS);
                assertTrue(app.isConnected());
                assertThrows(ExecutionException.class, () -> app.launchGame(app.requireProject()).get(5, TimeUnit.SECONDS));
                assertEquals(1, fixture.starts.get());
            }
        }
    }

    @Test void failedCommandReportsOneErrorAndAllowsRetry() throws Exception {
        try (var fixture = new Fixture()) {
            var failed = fixture.app.launchGame(fixture.app.requireProject());
            await(() -> fixture.starts.get() == 1);
            fixture.process.exit(7);
            assertTrue(assertThrows(ExecutionException.class, () -> failed.get(5, TimeUnit.SECONDS)).getCause().getMessage().contains("7"));
            assertEquals(1, fixture.app.notifications().snapshot().entries().stream()
                    .filter(entry -> entry.message().equals("Unable to launch Minecraft")).count());
            fixture.process = new LauncherProcess();
            var retry = fixture.app.launchGame(fixture.app.requireProject());
            await(() -> fixture.starts.get() == 2);
            try (var game = fixture.connect(fixture.profile, true)) { retry.get(5, TimeUnit.SECONDS); }
        }
    }

    @Test void missingExecutableFailsBeforeDispatch() throws Exception {
        try (var fixture = new Fixture()) {
            Files.delete(fixture.executable);
            var failed = fixture.app.launchGame(fixture.app.requireProject());
            assertTrue(assertThrows(ExecutionException.class, () -> failed.get(5, TimeUnit.SECONDS)).getCause().getMessage().contains("Open Prism"));
            assertEquals(0, fixture.starts.get());
        }
    }

    @Test void switchingProjectsCancelsTheIntentWithoutTerminatingPrism() throws Exception {
        try (var fixture = new Fixture()) {
            var oldScope = fixture.app.requireProject();
            var pending = fixture.app.launchGame(oldScope);
            await(() -> fixture.starts.get() == 1);
            fixture.app.openProject(CompanionProfile.forGame(Files.createDirectories(root.resolve("other")))).get(5, TimeUnit.SECONDS);
            assertTrue(pending.isCompletedExceptionally());
            fixture.process.exit(3);
            assertFalse(fixture.process.destroyed);
            assertTrue(fixture.app.notifications().snapshot().entries().stream()
                    .noneMatch(entry -> entry.message().equals("Unable to launch Minecraft")), "Old launch failures must not affect another project");
            assertThrows(ExecutionException.class, () -> fixture.app.launchGame(oldScope).get(5, TimeUnit.SECONDS));
            assertEquals(1, fixture.starts.get());
        }
    }

    @Test void shutdownDuringDiscoveryPreventsProcessDispatch() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var fixture = new Fixture(() -> {
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
        })) {
            var pending = fixture.app.launchGame(fixture.app.requireProject());
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var closing = CompletableFuture.runAsync(fixture.app::close);
            try {
                await(pending::isCompletedExceptionally);
            } finally { release.countDown(); }
            closing.get(5, TimeUnit.SECONDS);
            assertEquals(0, fixture.starts.get());
        } finally { release.countDown(); }
    }

    @Test void aClientArrivingDuringDiscoveryDefersLaunchUntilThatConnectionDrops() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var fixture = new Fixture(() -> {
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
        })) {
            var pending = fixture.app.launchGame(fixture.app.requireProject());
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var descriptor = CompanionSessionDescriptor.read(fixture.config.descriptorFile(), CompanionProtocol.VERSION);
                try (var connecting = new Socket("127.0.0.1", descriptor.port())) {
                    await(() -> fixture.app.session().hasClient());
                    release.countDown();
                    fixture.app.renameProject(fixture.profile.id(), null).get(5, TimeUnit.SECONDS);
                    assertEquals(0, fixture.starts.get(), "A connecting game must win over another launch command");
                    assertFalse(pending.isDone());
                }
                await(() -> fixture.starts.get() == 1);
                try (var game = fixture.connect(fixture.profile, true)) { pending.get(5, TimeUnit.SECONDS); }
            } finally { release.countDown(); }
        }
    }

    @Test void launchRequiresPublicationAndRepairsAWithdrawnTarget() throws Exception {
        try (var fixture = new Fixture()) {
            Files.delete(fixture.config.descriptorFile());
            Files.createDirectory(fixture.config.descriptorFile());
            Path blocker = Files.writeString(fixture.config.descriptorFile().resolve("blocker"), "blocked");
            var failed = fixture.app.launchGame(fixture.app.requireProject());
            fixture.app.renameProject(fixture.profile.id(), null).get(5, TimeUnit.SECONDS);
            assertEquals(0, fixture.starts.get(), "Do not start Minecraft without publishing its connection target");
            assertTrue(failed.isCompletedExceptionally());
            Files.delete(blocker);
            Files.delete(fixture.config.descriptorFile());
            fixture.app.session().publishProfile(null);
            var pending = fixture.app.launchGame(fixture.app.requireProject());
            await(() -> fixture.starts.get() == 1);
            assertEquals(fixture.profile.id(), CompanionSessionDescriptor.read(fixture.config.descriptorFile(), CompanionProtocol.VERSION).selectedProfileId());
            try (var game = fixture.connect(fixture.profile, true)) { pending.get(5, TimeUnit.SECONDS); }
        }
    }

    @UiTest
    @Test void realPlayButtonReflectsUnsupportedStartingConnectedAndOfflineStates() throws Exception {
        try (var fixture = new Fixture()) {
            GlobalConfig.getInstance().loadFrom(fixture.config.appHome());
            SwingUtilities.invokeAndWait(CompanionApp::configureLookAndFeel);
            var create = new FutureTask<>(fixture.app::createWindow);
            SwingUtilities.invokeAndWait(create);
            MainWindow window = create.get();
            var field = MainWindow.class.getDeclaredField("gameLaunch");
            field.setAccessible(true);
            JButton button = (JButton) field.get(window);
            awaitButton(button, true);
            SwingUtilities.invokeAndWait(() -> {
                assertEquals("Launch Minecraft in Prism", button.getToolTipText());
                assertTrue(button.getText() == null || button.getText().isEmpty());
                button.doClick(0);
                assertFalse(button.isEnabled());
            });
            await(() -> fixture.starts.get() == 1);
            try (var game = fixture.connect(fixture.profile, true)) {
                await(() -> fixture.app.isConnected());
                SwingUtilities.invokeAndWait(() -> assertFalse(button.isEnabled()));
            }
            awaitButton(button, true);
            fixture.app.openProject(CompanionProfile.forGame(Files.createDirectories(root.resolve("not-prism")))).get(5, TimeUnit.SECONDS);
            awaitButton(button, false);
        }
    }

    private final class Fixture implements AutoCloseable {
        final CompanionLaunchConfiguration config = new CompanionLaunchConfiguration(root.resolve("app"));
        final CompanionProfile profile;
        final Path executable;
        final CompanionApplication app;
        final AtomicInteger starts = new AtomicInteger();
        volatile LauncherProcess process = new LauncherProcess();

        Fixture() throws Exception { this(() -> {}); }
        Fixture(Runnable discovering) throws Exception {
            Path home = Files.createDirectories(root.resolve("Prism"));
            Files.writeString(home.resolve("prismlauncher.cfg"), "InstanceDir=instances\n");
            profile = PrismGameLauncherTest.instance(home.resolve("instances/Launch ü test"), "minecraft");
            executable = PrismGameLauncherTest.executable(root.resolve("bin/prismlauncher.exe"));
            var launcher = new PrismGameLauncher(home, () -> { discovering.run(); return List.of(executable); }, command -> {
                assertFalse(SwingUtilities.isEventDispatchThread());
                assertEquals(List.of(executable.toString(), "--dir", home.toString(), "--launch", "Launch ü test"), command);
                starts.incrementAndGet();
                return process;
            });
            app = new CompanionApplication(config, TOKEN, null, launcher);
            app.session().bindAndPublish(config);
            app.openProject(profile).get(5, TimeUnit.SECONDS);
        }

        Client connect(CompanionProfile selected, boolean accepted) throws Exception {
            Client client = new Client();
            var response = new CompletableFuture<ServerHelloMessage>();
            var ready = new CompletableFuture<Void>();
            ProtocolBindings.registerMod(client.getMessageProcessor());
            client.getMessageBus().listenAlways(ServerHelloMessage.class, response::complete);
            client.getMessageBus().listenAlways(ReadyMessage.class, ignored -> ready.complete(null));
            client.addConnectionListener(new IConnectionListener() {
                @Override public void onConnected() {
                    client.getMessageProcessor().enqueueMessage(new ClientHelloMessage(CompanionProtocol.VERSION, TOKEN,
                            selected.id(), selected.dataDirectory().toString(), selected.workspaceDirectory().toString()));
                }
                @Override public void onDisconnected() { }
            });
            try {
                var descriptor = CompanionSessionDescriptor.read(config.descriptorFile(), CompanionProtocol.VERSION);
                assertTrue(client.connect(new InetSocketAddress("127.0.0.1", descriptor.port())));
                assertEquals(accepted, response.get(5, TimeUnit.SECONDS).accepted());
                if (accepted) ready.get(5, TimeUnit.SECONDS);
                return client;
            } catch (Exception | AssertionError failure) { client.close(); throw failure; }
        }

        @Override public void close() { app.close(); assertFalse(process.destroyed); }
    }

    private static final class LauncherProcess extends Process {
        final CompletableFuture<Integer> exit = new CompletableFuture<>();
        boolean destroyed;
        void exit(int code) { exit.complete(code); }
        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { return exit.join(); }
        @Override public int exitValue() {
            if (!exit.isDone()) throw new IllegalThreadStateException();
            return exit.join();
        }
        @Override public void destroy() { destroyed = true; exit(1); }
        @Override public CompletableFuture<Process> onExit() { return exit.thenApply(ignored -> this); }
    }

    private static void awaitButton(JButton button, boolean enabled) throws Exception {
        await(() -> {
            var read = new FutureTask<>(button::isEnabled);
            try { SwingUtilities.invokeAndWait(read); return read.get() == enabled; }
            catch (Exception failure) { throw new AssertionError(failure); }
        });
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(10);
        assertTrue(condition.getAsBoolean());
    }
}
