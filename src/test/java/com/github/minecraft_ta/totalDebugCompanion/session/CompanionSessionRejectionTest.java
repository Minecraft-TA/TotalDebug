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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
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
        Path descriptorFile = sessionDirectory.resolve("session.properties");
        CompanionLaunchConfiguration configuration = CompanionLaunchConfiguration.parse(
                new String[]{
                        "--data-directory", data.toString(),
                        "--index-file", index.toString(),
                        "--workspace-directory", workspace.toString(),
                        "--session-descriptor", descriptorFile.toString()
                },
                Map.of(CompanionLaunchConfiguration.TOKEN_ENVIRONMENT_VARIABLE, "correct-token-value-1234567890abcdef")
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

            assertTrue(client.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), descriptor.port())));
            TestServerHello rejection = response.get(2, TimeUnit.SECONDS);
            assertFalse(rejection.accepted);
            assertEquals("Authentication token rejected", rejection.reason);
            assertTrue(disconnected.await(2, TimeUnit.SECONDS));
            assertThrows(IOException.class, () -> session.awaitAuthentication(1));
        }
    }

    private static void configureClient(Client client) {
        client.getMessageProcessor().setMaxFrameSize(DefaultMessageProcessor.RECOMMENDED_MAX_FRAME_SIZE);
        client.getMessageProcessor().setMaxStringLength(DefaultMessageProcessor.RECOMMENDED_MAX_STRING_LENGTH);
        client.getMessageProcessor().registerMessage(CompanionProtocol.CLIENT_HELLO, TestClientHello.class);
        client.getMessageProcessor().registerMessage(CompanionProtocol.SERVER_HELLO, TestServerHello.class);
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
