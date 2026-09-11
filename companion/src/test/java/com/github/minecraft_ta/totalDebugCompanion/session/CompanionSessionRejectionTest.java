package com.github.minecraft_ta.totalDebugCompanion.session;

import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionStatus;
import com.github.minecraft_ta.totaldebug.protocol.CompanionProtocol;
import com.github.minecraft_ta.totaldebug.storage.CompanionSessionDescriptor;
import com.github.tth05.scnet.Client;
import com.github.tth05.scnet.IConnectionListener;
import com.github.tth05.scnet.message.AbstractMessageIncoming;
import com.github.tth05.scnet.message.AbstractMessageOutgoing;
import com.github.tth05.scnet.message.impl.DefaultMessageProcessor;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ExecutionResultMessage;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResult;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionText;
import org.junit.jupiter.api.Test;
import com.github.minecraft_ta.totalDebugCompanion.project.ProjectScope;
import com.github.minecraft_ta.totalDebugCompanion.storage.InstanceState;
import com.github.minecraft_ta.totalDebugCompanion.script.ScriptCompilationService;
import com.github.minecraft_ta.totalDebugCompanion.script.ScriptExecutionService;
import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptExecutionEnvironment;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionSessionRejectionTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void unauthenticatedResultsNeverReachApplicationListeners() throws Exception {
        String token = "correct-token-value-1234567890abcdef";
        CompanionLaunchConfiguration configuration = new CompanionLaunchConfiguration(this.temporaryDirectory);
        AtomicInteger delivered = new AtomicInteger();
        try (CompanionSession session = new CompanionSession(token);
             Client client = configuredClient(null)) {
            session.addExecutionResultListener(
                    message -> delivered.incrementAndGet());
            client.getMessageProcessor().registerMessage(CompanionProtocol.EXECUTION_RESULT, TestExecutionResult.class);
            session.bindAndPublish(configuration);
            CompletableFuture<TestServerHello> rejection = connect(client,
                    CompanionSessionDescriptor.read(configuration.descriptorFile(), com.github.minecraft_ta.totaldebug.protocol.CompanionProtocol.VERSION));

            client.getMessageProcessor().enqueueMessage(new TestExecutionResult());

            assertFalse(rejection.get(2, TimeUnit.SECONDS).accepted);
            awaitDisconnected(client);
            assertEquals(0, delivered.get());
        }
    }

    @Test
    void rejectedSendReturnsFalseWhileTheTransportEndsTheSession() throws Exception {
        String token = "correct-token-value-1234567890abcdef";
        CountDownLatch authenticated = new CountDownLatch(1);
        CountDownLatch releaseTransport = new CountDownLatch(1);
        CompanionSession.Listener listener = new CompanionSession.Listener() {
            @Override public void connected() {
                authenticated.countDown();
                try {
                    assertTrue(releaseTransport.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }
        };
        CompanionLaunchConfiguration configuration = new CompanionLaunchConfiguration(this.temporaryDirectory);
        try (CompanionSession session = new CompanionSession(token, hello -> { }, listener);
             Client client = configuredClient(token)) {
            try {
                session.bindAndPublish(configuration);
                connect(client, CompanionSessionDescriptor.read(configuration.descriptorFile(), com.github.minecraft_ta.totaldebug.protocol.CompanionProtocol.VERSION));
                assertTrue(authenticated.await(2, TimeUnit.SECONDS));
                session.server().getMessageProcessor().beginOutboundDrain();
                assertFalse(session.send(new com.github.tth05.scnet.message.impl.EmptyMessage()));
            } finally {
                releaseTransport.countDown();
            }
        }
    }

    @Test
    void wrongTokenIsRejectedWithoutStoppingTheServer() throws Exception {
        String token = "correct-token-value-1234567890abcdef";
        CompanionLaunchConfiguration configuration = new CompanionLaunchConfiguration(this.temporaryDirectory);

        try (CompanionSession session = new CompanionSession(token);
             Client rejectedClient = configuredClient("wrong-token-value-1234567890abcdef");
             Client acceptedClient = configuredClient(token)) {
            session.bindAndPublish(configuration);
            CompanionSessionDescriptor descriptor = CompanionSessionDescriptor.read(configuration.descriptorFile(), com.github.minecraft_ta.totaldebug.protocol.CompanionProtocol.VERSION);

            TestServerHello rejected = connect(rejectedClient, descriptor).get(2, TimeUnit.SECONDS);
            assertFalse(rejected.accepted);
            assertEquals("Authentication token rejected", rejected.reason);
            awaitDisconnected(rejectedClient);

            TestServerHello accepted = connect(acceptedClient, descriptor).get(2, TimeUnit.SECONDS);
            assertTrue(accepted.accepted);
            assertTrue(session.isConnected());
        }
    }

    @Test
    void authenticatedDisconnectReturnsToOfflineAndAcceptsAReconnect() throws Exception {
        String token = "correct-token-value-1234567890abcdef";
        CompanionLaunchConfiguration configuration = new CompanionLaunchConfiguration(this.temporaryDirectory);
        CountDownLatch disconnected = new CountDownLatch(1);
        CompanionSession.Listener listener = new CompanionSession.Listener() {
            @Override
            public void disconnected() {
                disconnected.countDown();
            }
        };

        try (CompanionSession session = new CompanionSession(token, hello -> { }, listener);
             Client first = configuredClient(token);
             Client second = configuredClient(token)) {
            session.bindAndPublish(configuration);
            CompanionSessionDescriptor descriptor = CompanionSessionDescriptor.read(configuration.descriptorFile(), com.github.minecraft_ta.totaldebug.protocol.CompanionProtocol.VERSION);

            assertTrue(connect(first, descriptor).get(2, TimeUnit.SECONDS).accepted);
            first.close();
            assertTrue(disconnected.await(2, TimeUnit.SECONDS));
            assertFalse(session.isConnected());

            assertTrue(connect(second, descriptor).get(2, TimeUnit.SECONDS).accepted);
            assertTrue(session.isConnected());
        }
    }

    @Test
    void authenticatedHandshakePublishesReadyAfterServerHello() throws Exception {
        String token = "correct-token-value-1234567890abcdef";
        CompanionLaunchConfiguration configuration = new CompanionLaunchConfiguration(this.temporaryDirectory);

        try (CompanionSession session = new CompanionSession(token);
             Client client = configuredClient(token)) {
            session.bindAndPublish(configuration);
            CompanionSessionDescriptor descriptor = CompanionSessionDescriptor.read(configuration.descriptorFile(), com.github.minecraft_ta.totaldebug.protocol.CompanionProtocol.VERSION);
            CompletableFuture<TestReady> ready = new CompletableFuture<>();
            client.getMessageBus().listenAlways(TestReady.class, ready::complete);

            assertTrue(connect(client, descriptor).get(2, TimeUnit.SECONDS).accepted);
            ready.get(2, TimeUnit.SECONDS);
            assertTrue(session.isConnected());
        }
    }

    @Test
    void scriptAdmissionRejectsUnauthenticatedSocketsAndASwitchRace() throws Exception {
        String token = "correct-token-value-1234567890abcdef";
        var configuration = new CompanionLaunchConfiguration(temporaryDirectory);
        Object lifecycle = new Object();
        var scope = new ProjectScope(lifecycle, new CompanionProfile("test", temporaryDirectory, temporaryDirectory), InstanceState.inMemory());
        try (var session = new CompanionSession(token);
             var compiler = new ScriptCompilationService(message -> true, message -> true);
             Client client = configuredClient(null)) {
            var scripts = new ScriptExecutionService(session, compiler);
            session.bindAndPublish(configuration);
            var response = connect(client, CompanionSessionDescriptor.read(configuration.descriptorFile(), CompanionProtocol.VERSION));
            assertFalse(scripts.run(scope, 1, "source", false, ScriptExecutionEnvironment.THREAD, failure -> {}));
            client.getMessageProcessor().enqueueMessage(new TestClientHello(token));
            assertTrue(response.get(2, TimeUnit.SECONDS).accepted);
            assertTrue(session.isConnected());
            var result = new CompletableFuture<Boolean>();
            Thread submitter = Thread.ofPlatform().unstarted(() -> {
                try { result.complete(scripts.run(scope, 2, "source", false, ScriptExecutionEnvironment.THREAD, failure -> {})); }
                catch (Throwable failure) { result.completeExceptionally(failure); }
            });
            synchronized (lifecycle) {
                submitter.start();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (submitter.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(1);
                assertEquals(Thread.State.BLOCKED, submitter.getState(), "Submission must be waiting at the scope gate");
                scope.beginSwitch();
            }
            assertFalse(result.get(2, TimeUnit.SECONDS), "A gate rejection must preserve the boolean caller contract");
            scope.cancelSwitch();
            assertTrue(scope.isActive());
        } finally { scope.retire(); scope.close(); }
    }

    private static Client configuredClient(String token) {
        Client client = new Client();
        client.getMessageProcessor().setMaxFrameSize(DefaultMessageProcessor.DEFAULT_MAX_FRAME_SIZE);
        client.getMessageProcessor().setMaxStringLength(DefaultMessageProcessor.DEFAULT_MAX_STRING_LENGTH);
        client.getMessageProcessor().registerMessage(CompanionProtocol.CLIENT_HELLO, TestClientHello.class);
        client.getMessageProcessor().registerMessage(
                CompanionProtocol.SERVER_HELLO,
                TestServerHello.class,
                TestServerHello::new
        );
        client.getMessageProcessor().registerMessage(
                CompanionProtocol.READY,
                TestReady.class,
                TestReady::new
        );
        client.addConnectionListener(new IConnectionListener() {
            @Override
            public void onConnected() {
                if (token != null) {
                    client.getMessageProcessor().enqueueMessage(new TestClientHello(token));
                }
            }

            @Override
            public void onDisconnected() {
            }
        });
        return client;
    }

    private static CompletableFuture<TestServerHello> connect(Client client, CompanionSessionDescriptor descriptor) {
        CompletableFuture<TestServerHello> response = new CompletableFuture<>();
        client.getMessageBus().listenAlways(TestServerHello.class, response::complete);
        assertTrue(client.connect(CompanionSession.sessionAddress(descriptor.port())));
        return response;
    }

    private static void awaitDisconnected(Client client) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (client.isConnected() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertFalse(client.isConnected());
    }

    public static final class TestExecutionResult extends AbstractMessageOutgoing {
        @Override
        public void write(ByteBufferOutputStream stream) {
            new ExecutionResultMessage(1, new ExecutionResult(ExecutionStatus.RUN_COMPLETED,
                    ExecutionText.empty(), null, ExecutionText.empty())).write(stream);
        }
    }

    public static final class TestClientHello extends AbstractMessageOutgoing {
        private final String token;

        public TestClientHello(String token) {
            this.token = token;
        }

        @Override
        public void write(ByteBufferOutputStream messageStream) {
            messageStream.writeInt(CompanionProtocol.VERSION);
            messageStream.writeString(this.token);
            messageStream.writeString("profile");
            messageStream.writeString("data");
            messageStream.writeString("workspace");
        }
    }

    public static final class TestServerHello extends AbstractMessageIncoming {
        private boolean accepted;
        private String reason;

        @Override
        public void read(ByteBufferInputStream messageStream) {
            assertEquals(CompanionProtocol.VERSION, messageStream.readInt());
            this.accepted = messageStream.readBoolean();
            this.reason = messageStream.readString();
        }
    }

    public static final class TestReady extends AbstractMessageIncoming {
        @Override
        public void read(ByteBufferInputStream messageStream) {
        }
    }
}
