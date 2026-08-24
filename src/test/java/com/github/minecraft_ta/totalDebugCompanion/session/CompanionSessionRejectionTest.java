package com.github.minecraft_ta.totalDebugCompanion.session;

import com.github.tth05.scnet.Client;
import com.github.tth05.scnet.IConnectionListener;
import com.github.tth05.scnet.message.AbstractMessageIncoming;
import com.github.tth05.scnet.message.AbstractMessageOutgoing;
import com.github.tth05.scnet.message.impl.DefaultMessageProcessor;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionSessionRejectionTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void wrongTokenIsRejectedWithoutStoppingTheServer() throws Exception {
        String token = "correct-token-value-1234567890abcdef";
        CompanionLaunchConfiguration configuration = new CompanionLaunchConfiguration(this.temporaryDirectory);

        try (CompanionSession session = new CompanionSession(token);
             Client rejectedClient = configuredClient("wrong-token-value-1234567890abcdef");
             Client acceptedClient = configuredClient(token)) {
            session.bindAndPublish(configuration);
            CompanionSessionDescriptor descriptor = CompanionSessionDescriptor.read(configuration.descriptorFile());

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

        try (CompanionSession session = new CompanionSession(token, (hello, capabilities) -> { }, listener);
             Client first = configuredClient(token);
             Client second = configuredClient(token)) {
            session.bindAndPublish(configuration);
            CompanionSessionDescriptor descriptor = CompanionSessionDescriptor.read(configuration.descriptorFile());

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
            CompanionSessionDescriptor descriptor = CompanionSessionDescriptor.read(configuration.descriptorFile());
            CompletableFuture<TestReady> ready = new CompletableFuture<>();
            client.getMessageBus().listenAlways(TestReady.class, ready::complete);

            assertTrue(connect(client, descriptor).get(2, TimeUnit.SECONDS).accepted);
            ready.get(2, TimeUnit.SECONDS);
            assertTrue(session.isConnected());
        }
    }

    private static Client configuredClient(String token) {
        Client client = new Client();
        client.getMessageProcessor().setMaxFrameSize(DefaultMessageProcessor.RECOMMENDED_MAX_FRAME_SIZE);
        client.getMessageProcessor().setMaxStringLength(DefaultMessageProcessor.RECOMMENDED_MAX_STRING_LENGTH);
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
                client.getMessageProcessor().enqueueMessage(new TestClientHello(token));
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

    public static final class TestClientHello extends AbstractMessageOutgoing {
        private final String token;

        public TestClientHello(String token) {
            this.token = token;
        }

        @Override
        public void write(ByteBufferOutputStream messageStream) {
            messageStream.writeInt(CompanionProtocol.VERSION);
            messageStream.writeString(this.token);
            messageStream.writeLong(CompanionProtocol.CORE_CAPABILITIES);
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
            messageStream.readLong();
            this.reason = messageStream.readString();
        }
    }

    public static final class TestReady extends AbstractMessageIncoming {
        @Override
        public void read(ByteBufferInputStream messageStream) {
        }
    }
}
