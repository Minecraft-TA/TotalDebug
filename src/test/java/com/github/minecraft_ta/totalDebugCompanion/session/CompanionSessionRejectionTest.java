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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionSessionRejectionTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void readyCannotBeSentBeforeAuthentication() {
        try (CompanionSession session = new CompanionSession("correct-token-value-1234567890abcdef")) {
            assertThrows(IllegalStateException.class, session::markUiReady);
        }
    }

    @Test
    void wrongTokenGetsAnExactRejectionThenTheServerClosesTheConnection() throws Exception {
        Path data = Files.createDirectory(this.temporaryDirectory.resolve("data"));
        Path index = Files.writeString(this.temporaryDirectory.resolve("index"), "index");
        Path workspace = Files.createDirectory(this.temporaryDirectory.resolve("workspace"));
        Path sessionDirectory = Files.createDirectory(this.temporaryDirectory.resolve("session"));
        Path descriptorFile = sessionDirectory.resolve(CompanionLaunchContract.SESSION_DESCRIPTOR_FILE_NAME);
        CompanionLaunchConfiguration configuration = CompanionLaunchConfiguration.parse(
                new String[]{
                        CompanionLaunchContract.DATA_DIRECTORY_ARGUMENT, data.toString(),
                        CompanionLaunchContract.INDEX_FILE_ARGUMENT, index.toString(),
                        CompanionLaunchContract.WORKSPACE_DIRECTORY_ARGUMENT, workspace.toString(),
                        CompanionLaunchContract.SESSION_DESCRIPTOR_ARGUMENT, descriptorFile.toString()
                },
                Map.of(CompanionLaunchContract.TOKEN_ENVIRONMENT_VARIABLE, "correct-token-value-1234567890abcdef")
        );

        try (CompanionSession session = new CompanionSession("correct-token-value-1234567890abcdef");
             Client client = new Client()) {
            session.bindAndPublish(configuration);
            CompanionSessionDescriptor descriptor = CompanionSessionDescriptor.read(descriptorFile);
            configureClient(client);

            CompletableFuture<TestServerHello> response = new CompletableFuture<>();
            CountDownLatch disconnected = new CountDownLatch(1);
            client.getMessageBus().listenAlways(TestServerHello.class, response::complete);
            client.addConnectionListener(new IConnectionListener() {
                @Override
                public void onConnected() {
                    client.getMessageProcessor().enqueueMessage(new TestClientHello("wrong-token-value-1234567890abcdef"));
                }

                @Override
                public void onDisconnected() {
                    disconnected.countDown();
                }
            });

            assertTrue(client.connect(CompanionSession.sessionAddress(descriptor.port())));
            TestServerHello rejection = response.get(2, TimeUnit.SECONDS);
            assertFalse(rejection.accepted);
            assertEquals("Authentication token rejected", rejection.reason);
            assertTrue(disconnected.await(2, TimeUnit.SECONDS));
            assertThrows(IOException.class, () -> session.awaitAuthentication(Duration.ofSeconds(1)));
        }
    }

    @Test
    void authenticatedDisconnectEndsTheOwnedSession() throws Exception {
        Path data = Files.createDirectory(this.temporaryDirectory.resolve("authenticated-data"));
        Path index = Files.writeString(this.temporaryDirectory.resolve("authenticated-index"), "index");
        Path workspace = Files.createDirectory(this.temporaryDirectory.resolve("authenticated-workspace"));
        Path sessionDirectory = Files.createDirectory(this.temporaryDirectory.resolve("authenticated-session"));
        Path descriptorFile = sessionDirectory.resolve(CompanionLaunchContract.SESSION_DESCRIPTOR_FILE_NAME);
        String token = "correct-token-value-1234567890abcdef";
        CompanionLaunchConfiguration configuration = CompanionLaunchConfiguration.parse(
                new String[]{
                        CompanionLaunchContract.DATA_DIRECTORY_ARGUMENT, data.toString(),
                        CompanionLaunchContract.INDEX_FILE_ARGUMENT, index.toString(),
                        CompanionLaunchContract.WORKSPACE_DIRECTORY_ARGUMENT, workspace.toString(),
                        CompanionLaunchContract.SESSION_DESCRIPTOR_ARGUMENT, descriptorFile.toString()
                },
                Map.of(CompanionLaunchContract.TOKEN_ENVIRONMENT_VARIABLE, token)
        );

        try (CompanionSession session = new CompanionSession(token);
             Client client = new Client()) {
            session.bindAndPublish(configuration);
            CompanionSessionDescriptor descriptor = CompanionSessionDescriptor.read(descriptorFile);
            configureClient(client);

            CompletableFuture<TestServerHello> response = new CompletableFuture<>();
            client.getMessageBus().listenAlways(TestServerHello.class, response::complete);
            client.addConnectionListener(new IConnectionListener() {
                @Override
                public void onConnected() {
                    client.getMessageProcessor().enqueueMessage(new TestClientHello(token));
                }

                @Override
                public void onDisconnected() {
                }
            });

            assertTrue(client.connect(CompanionSession.sessionAddress(descriptor.port())));
            TestServerHello accepted = response.get(2, TimeUnit.SECONDS);
            assertTrue(accepted.accepted);
            assertTrue(session.markUiReady());

            client.close();

            session.awaitAuthenticatedDisconnect(Duration.ofSeconds(2));
            assertFalse(session.markUiReady());
        }
    }

    private static void configureClient(Client client) {
        client.getMessageProcessor().setMaxFrameSize(DefaultMessageProcessor.RECOMMENDED_MAX_FRAME_SIZE);
        client.getMessageProcessor().setMaxStringLength(DefaultMessageProcessor.RECOMMENDED_MAX_STRING_LENGTH);
        client.getMessageProcessor().registerMessage(CompanionProtocol.CLIENT_HELLO, TestClientHello.class);
        client.getMessageProcessor().registerMessage(
                CompanionProtocol.SERVER_HELLO,
                TestServerHello.class,
                TestServerHello::new
        );
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
        }
    }

    public static final class TestServerHello extends AbstractMessageIncoming {
        private boolean accepted;
        private String reason;

        public TestServerHello() {
        }

        @Override
        public void read(ByteBufferInputStream messageStream) {
            assertEquals(CompanionProtocol.VERSION, messageStream.readInt());
            this.accepted = messageStream.readBoolean();
            messageStream.readLong();
            this.reason = messageStream.readString();
        }
    }
}
