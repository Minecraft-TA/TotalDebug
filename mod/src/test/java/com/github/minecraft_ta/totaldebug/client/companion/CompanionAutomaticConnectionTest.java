package com.github.minecraft_ta.totaldebug.client.companion;

import com.github.minecraft_ta.totaldebug.protocol.CompanionProtocol;
import com.github.minecraft_ta.totaldebug.protocol.ProjectSelectionRequest;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ClientHelloMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.FocusWindowMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ProtocolBindings;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ReadyMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ServerHelloMessage;
import com.github.minecraft_ta.totaldebug.storage.AppPaths;
import com.github.minecraft_ta.totaldebug.storage.AtomicFiles;
import com.github.minecraft_ta.totaldebug.storage.CompanionSessionDescriptor;
import com.github.minecraft_ta.totaldebug.storage.InstancePaths;
import com.github.tth05.scnet.Client;
import com.github.tth05.scnet.Server;
import com.sun.net.httpserver.HttpServer;
import com.sun.nio.file.ExtendedOpenOption;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class CompanionAutomaticConnectionTest {
    @TempDir Path root;
    private AppPaths paths;
    private Path game;
    private String previousHome;
    private final AtomicInteger focus = new AtomicInteger();

    @BeforeEach void setup() throws Exception {
        paths = new AppPaths(root.resolve("app"));
        game = Files.createDirectories(root.resolve("game"));
        previousHome = System.getProperty(AppPaths.HOME_PROPERTY);
        System.setProperty(AppPaths.HOME_PROPERTY, paths.home().toString());
    }

    @AfterEach void restore() {
        if (previousHome == null) System.clearProperty(AppPaths.HOME_PROPERTY);
        else System.setProperty(AppPaths.HOME_PROPERTY, previousHome);
    }

    private CompanionAppClient client() {
        var timeouts = new CompanionTimeouts(Duration.ofSeconds(3), Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofMillis(20));
        return new CompanionAppClient(game.resolve("total-debug"), timeouts, new CompanionForegroundHandoff(pid -> focus.incrementAndGet()));
    }

    @Test void connectsWhenCompanionStartsFirstWithoutSelectingOrFocusing() throws Exception {
        try (var endpoint = new Endpoint(); var client = client()) {
            endpoint.publish(profile());
            client.startDiscovery(() -> true);
            await(client::isConnected);
            assertEquals(1, endpoint.hellos.get());
            assertEquals(0, endpoint.selections.get());
            assertEquals(0, focus.get());
            assertFalse(Files.exists(InstancePaths.installationDirectory(game)));
            endpoint.publish(profile());
            Thread.sleep(300);
            assertEquals(1, endpoint.hellos.get(), "A fresh advertisement must reuse a working connection");
        }
    }

    @Test void connectsWhenTheGameStartsFirstAndRecoversAfterCompanionRestarts() throws Exception {
        try (var client = client()) {
            client.startDiscovery(() -> true);
            await(() -> Files.isDirectory(paths.run()));
            try (var endpoint = new Endpoint()) {
                endpoint.publish(profile());
                await(client::isConnected);
            }
            await(() -> !client.isConnected());
            try (var restarted = new Endpoint()) {
                restarted.publish(profile());
                await(client::isConnected);
                assertEquals(1, restarted.hellos.get());
            }
        }
    }

    @Test void waitsForMatchingSelectionAndRetriesARejectedPublicationOnlyAfterReplacement() throws Exception {
        try (var endpoint = new Endpoint(); var client = client()) {
            endpoint.publish("another-project");
            client.startDiscovery(() -> true);
            Thread.sleep(300);
            assertEquals(0, endpoint.hellos.get());
            endpoint.reject = true;
            endpoint.publish(profile());
            await(() -> endpoint.hellos.get() == 1);
            Thread.sleep(1400);
            assertEquals(1, endpoint.hellos.get(), "Authentication rejection must not spin");
            endpoint.reject = false;
            endpoint.publish(profile());
            await(client::isConnected);
            assertEquals(2, endpoint.hellos.get());
        }
    }

    @Test void transportFailureRetriesWithoutAnotherFileEvent() throws Exception {
        try (var endpoint = new Endpoint(); var client = client()) {
            endpoint.drop = true;
            endpoint.publish(profile());
            client.startDiscovery(() -> true);
            await(() -> endpoint.hellos.get() >= 1);
            endpoint.drop = false;
            await(client::isConnected);
            assertTrue(endpoint.hellos.get() >= 2);
        }
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void temporarilyUnreadableDescriptorRecoversWithoutAnotherPublication() throws Exception {
        try (var endpoint = new Endpoint(); var client = client()) {
            endpoint.publish(profile());
            try (var reader = FileChannel.open(paths.instanceDescriptor(), StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_READ)) {
                var attempt = CompanionAppClient.class.getDeclaredMethod("tryAutomaticConnection");
                attempt.setAccessible(true);
                assertEquals(CompanionDiscovery.Result.RETRY, attempt.invoke(client),
                        "A sharing violation is temporary, not an invalid publication");
                client.startDiscovery(() -> true);
                Thread.sleep(300);
                assertFalse(client.isConnected());
            }
            await(client::isConnected);
            assertEquals(1, endpoint.hellos.get());
        }
    }

    @Test void foregroundRequestSharesTheAutomaticHandshakeWithoutBlockingItsCallbacks() throws Exception {
        try (var endpoint = new Endpoint(); var client = client()) {
            endpoint.hold = true;
            endpoint.publish(profile());
            client.startDiscovery(() -> true);
            await(() -> endpoint.hellos.get() == 1);
            var explicit = CompletableFuture.runAsync(() -> {
                try { client.focus(() -> { }); }
                catch (IOException failure) { throw new AssertionError(failure); }
            });
            endpoint.accept();
            explicit.get(5, TimeUnit.SECONDS);
            await(() -> endpoint.focused.get() == 1);
            assertEquals(1, endpoint.hellos.get());
            assertEquals(1, endpoint.selections.get());
            assertEquals(1, focus.get());
        }
    }

    @Test void closeInterruptsAPendingHandshakeAndNeverReconnects() throws Exception {
        try (var endpoint = new Endpoint(); var client = client()) {
            endpoint.hold = true;
            endpoint.publish(profile());
            client.startDiscovery(() -> true);
            await(() -> endpoint.hellos.get() == 1);
            assertTimeoutPreemptively(Duration.ofSeconds(1), client::close);
            endpoint.hold = false;
            endpoint.publish(profile());
            Thread.sleep(1200);
            assertFalse(client.isConnected());
            assertEquals(1, endpoint.hellos.get());
        }
    }

    @Test void oldTransportMessagesCannotAuthenticateTheReplacement() throws Exception {
        try (var endpoint = new Endpoint(); var client = client()) {
            endpoint.publish(profile());
            client.startDiscovery(() -> true);
            await(client::isConnected);
            var connectionField = CompanionAppClient.class.getDeclaredField("connection");
            connectionField.setAccessible(true);
            Object old = connectionField.get(client);
            var transportField = old.getClass().getDeclaredField("client");
            transportField.setAccessible(true);
            Client oldTransport = (Client) transportField.get(old);
            endpoint.hold = true;
            endpoint.server.closeClient();
            await(() -> endpoint.hellos.get() == 2);
            oldTransport.getMessageBus().post(ServerHelloMessage.accept());
            oldTransport.getMessageBus().post(new ReadyMessage());
            assertFalse(client.isConnected());
            endpoint.accept();
            await(client::isConnected);
        }
    }

    @Test void disabledDiscoveryDoesNotConnect() throws Exception {
        try (var endpoint = new Endpoint(); var client = client()) {
            endpoint.publish(profile());
            client.startDiscovery(() -> false);
            Thread.sleep(300);
            assertEquals(0, endpoint.hellos.get());
        }
    }

    @Test void replacementWaitsForThePreviousSessionCleanup() throws Exception {
        var cleaning = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var cleanupCalls = new AtomicInteger();
        try (var endpoint = new Endpoint(); var client = client()) {
            client.setSessionClosedHandler(() -> {
                if (cleanupCalls.incrementAndGet() != 1) return;
                cleaning.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            });
            try {
                endpoint.publish(profile());
                client.startDiscovery(() -> true);
                await(client::isConnected);
                endpoint.server.closeClient();
                assertTrue(cleaning.await(3, TimeUnit.SECONDS));
                Thread.sleep(1500);
                assertEquals(1, endpoint.hellos.get(), "Old-session cleanup must finish before a replacement authenticates");
                release.countDown();
                await(client::isConnected);
                assertEquals(2, endpoint.hellos.get());
            } finally { release.countDown(); }
        }
    }

    private String profile() { return InstancePaths.profileId(game); }

    private static void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Connection did not reach expected state");
    }

    private final class Endpoint implements AutoCloseable {
        final Server server = new Server();
        final FileChannel lockChannel;
        final FileLock lock;
        final HttpServer selection;
        final AtomicInteger hellos = new AtomicInteger();
        final AtomicInteger selections = new AtomicInteger();
        final AtomicInteger focused = new AtomicInteger();
        volatile boolean hold;
        volatile boolean reject;
        volatile boolean drop;

        Endpoint() throws Exception {
            Files.createDirectories(paths.run());
            lockChannel = FileChannel.open(paths.instanceLock(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = lockChannel.lock();
            AtomicFiles.writeString(paths.instanceKey(), "test-token-" + System.nanoTime() + "-abcdefghijklmnopqrstuvwxyz");
            ProtocolBindings.registerCompanion(server.getMessageProcessor());
            server.getMessageBus().listenAlways(ClientHelloMessage.class, hello -> {
                hellos.incrementAndGet();
                assertEquals(CompanionProtocol.VERSION, hello.protocolVersion());
                assertEquals(profile(), hello.profileId());
                if (drop) server.closeClient();
                else if (reject) {
                    server.getMessageProcessor().enqueueMessage(ServerHelloMessage.rejected("fixture rejection"));
                    server.closeClientAfterPendingWrites();
                } else if (!hold) accept();
            });
            server.getMessageBus().listenAlways(FocusWindowMessage.class, ignored -> focused.incrementAndGet());
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            selection = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            selection.createContext(ProjectSelectionRequest.PATH, exchange -> {
                try (exchange) {
                    ProjectSelectionRequest.decode(exchange.getRequestBody().readAllBytes());
                    selections.incrementAndGet();
                    exchange.getResponseHeaders().set(ProjectSelectionRequest.CONNECTED_HEADER, "true");
                    exchange.sendResponseHeaders(204, -1);
                }
            });
            selection.start();
        }

        void publish(String profileId) throws IOException {
            new CompanionSessionDescriptor(CompanionProtocol.VERSION, ((InetSocketAddress) server.getLocalAddress()).getPort(),
                    ProcessHandle.current().pid(), selection.getAddress().getPort(), profileId).writeAtomically(paths.instanceDescriptor());
        }

        void accept() {
            server.getMessageProcessor().enqueueMessage(ServerHelloMessage.accept());
            server.getMessageProcessor().enqueueMessage(new ReadyMessage());
        }

        @Override public void close() throws Exception {
            selection.stop(0);
            server.close();
            lock.release();
            lockChannel.close();
        }
    }
}
