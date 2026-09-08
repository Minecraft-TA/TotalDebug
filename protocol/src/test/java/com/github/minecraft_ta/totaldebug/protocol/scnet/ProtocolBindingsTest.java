package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.minecraft_ta.totaldebug.protocol.CompanionProtocol;
import com.github.minecraft_ta.totaldebug.protocol.GoldenMessages;
import com.github.tth05.scnet.Client;
import com.github.tth05.scnet.IConnectionListener;
import com.github.tth05.scnet.Server;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import com.github.tth05.scnet.message.IMessageProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class ProtocolBindingsTest {
    @Test
    void companionReceivesAChunkedManifestThroughItsRegisteredTransport() throws Exception {
        try (Server server = endpoint(ProtocolBindings::registerCompanion)) {
            var received = new CompletableFuture<byte[]>();
            var assembler = new ServerManifestMessage.Assembler();
            server.getMessageBus().listenAlways(ServerManifestMessage.class, message -> {
                byte[] result = assembler.accept(message);
                if (result != null) received.complete(result);
            });
            byte[] bytes = new byte[ServerManifestMessage.CHUNK_BYTES + 19];
            try (SocketChannel socket = SocketChannel.open(server.getLocalAddress())) {
                for (var message : ServerManifestMessage.split("session", bytes)) {
                    var output = new ByteBufferOutputStream();
                    message.write(output);
                    var buffer = output.getBuffer().duplicate();
                    buffer.flip();
                    byte[] encoded = new byte[buffer.remaining()];
                    buffer.get(encoded);
                    write(socket, CompanionProtocol.SERVER_MANIFEST, encoded);
                }
                assertArrayEquals(bytes, received.get(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void modIgnoresOutgoingOnlyIdsWithoutDecodingTheirPayload() throws Exception {
        try (Server server = endpoint(ProtocolBindings::registerMod)) {
            var received = new CompletableFuture<Integer>();
            server.getMessageBus().listenAlways(
                    StopScriptMessage.class,
                    message -> received.complete(message.scriptId()));
            try (SocketChannel socket = SocketChannel.open(server.getLocalAddress())) {
                write(socket, CompanionProtocol.CLIENT_HELLO, new byte[]{-1});
                write(socket, CompanionProtocol.STOP_SCRIPT, HexFormat.of().parseHex(GoldenMessages.STOP_SCRIPT));
                assertEquals(7, received.get(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void companionIgnoresOutgoingOnlyIdsAndStillDispatchesHello() throws Exception {
        try (Server server = endpoint(ProtocolBindings::registerCompanion)) {
            var received = new CompletableFuture<String>();
            server.getMessageBus().listenAlways(
                    ClientHelloMessage.class,
                    message -> received.complete(message.token()));
            try (SocketChannel socket = SocketChannel.open(server.getLocalAddress())) {
                write(socket, CompanionProtocol.RUN_SCRIPT, new byte[]{-1});
                write(socket, CompanionProtocol.CLIENT_HELLO, HexFormat.of().parseHex(GoldenMessages.CLIENT_HELLO));
                assertEquals("abc", received.get(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void incomingForwardableMessageCannotBeSentThroughTheModTransport() throws Exception {
        try (ServerSocketChannel listener = ServerSocketChannel.open(); Client client = new Client()) {
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            ProtocolBindings.registerMod(client.getMessageProcessor());
            var failure = new CompletableFuture<Throwable>();
            client.addConnectionListener(new IConnectionListener() {
                @Override public void onConnected() { }
                @Override public void onDisconnected() { }
                @Override public void onConnectionError(Throwable cause) { failure.complete(cause); }
            });
            assertTrue(client.connect(listener.getLocalAddress()));
            try (SocketChannel connection = listener.accept()) {
                client.getMessageProcessor().enqueueMessage(
                        new StopScriptMessage(7));
                assertInstanceOf(IllegalArgumentException.class, failure.get(5, TimeUnit.SECONDS));
            }
        }
    }

    private static Server endpoint(Consumer<IMessageProcessor> register) throws Exception {
        Server server = new Server();
        register.accept(server.getMessageProcessor());
        server.bind(new InetSocketAddress("127.0.0.1", 0));
        return server;
    }

    private static void write(SocketChannel socket, short id, byte[] payload) throws Exception {
        ByteBuffer frame = ByteBuffer.allocate(Short.BYTES + Integer.BYTES + payload.length);
        frame.putShort(id).putInt(payload.length).put(payload).flip();
        while (frame.hasRemaining()) socket.write(frame);
    }
}
