package com.github.minecraft_ta.totalDebugCompanion.session;

import com.github.minecraft_ta.totaldebug.protocol.CompanionProtocol;
import com.github.minecraft_ta.totaldebug.protocol.ProjectSelectionRequest;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ClientHelloMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ServerHelloMessage;
import com.github.minecraft_ta.totaldebug.storage.CompanionSessionDescriptor;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class CompanionProjectAttachmentTest {
    @TempDir Path directory;

    @Test void explicitSwitchReplacesAnAttachedGameButAnOrdinaryHandshakeCannot() throws Exception {
        var selected = new AtomicReference<>("a");
        try (var session = new CompanionSession("secret", hello -> {
            if (!hello.profileId().equals(selected.get())) throw new IOException("Select the project first");
        }, new CompanionSession.Listener() {})) {
            session.setProjectSelectionHandler(hello -> {
                if (!hello.profileId().equals(selected.get())) {
                    session.disconnect();
                    selected.set(hello.profileId());
                }
            });
            var config = new CompanionLaunchConfiguration(directory);
            session.bindAndPublish(config);
            var descriptor = CompanionSessionDescriptor.read(config.descriptorFile());
            try (var a = connect(descriptor.port())) {
                assertTrue(handshake(a, "a"));
                ProjectSelectionRequest.send(descriptor.projectPort(), hello("b"));
                assertEquals(-1, a.getInputStream().read());
                assertEquals("b", selected.get());
                try (var rejected = connect(descriptor.port())) {
                    assertFalse(handshake(rejected, "a"));
                    assertEquals(-1, rejected.getInputStream().read());
                }
                try (var b = connect(descriptor.port())) {
                    assertTrue(handshake(b, "b"));
                    assertTrue(session.isConnected());
                    assertEquals(descriptor, CompanionSessionDescriptor.read(config.descriptorFile()));
                }
            }
        }
    }

    private static Socket connect(int port) throws IOException {
        var socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(5000);
        return socket;
    }

    private static boolean handshake(Socket socket, String project) throws IOException {
        byte[] bytes = ProjectSelectionRequest.encode(hello(project));
        var output = new DataOutputStream(socket.getOutputStream());
        output.writeShort(CompanionProtocol.CLIENT_HELLO);
        output.writeInt(bytes.length);
        output.write(bytes);
        output.flush();
        var input = new DataInputStream(socket.getInputStream());
        assertEquals(CompanionProtocol.SERVER_HELLO, input.readShort());
        var response = new ServerHelloMessage();
        response.read(new ByteBufferInputStream(ByteBuffer.wrap(input.readNBytes(input.readInt()))));
        if (response.accepted()) {
            assertEquals(CompanionProtocol.READY, input.readShort());
            input.readNBytes(input.readInt());
        }
        return response.accepted();
    }

    private static ClientHelloMessage hello(String project) {
        return new ClientHelloMessage(CompanionProtocol.VERSION, "secret", project, "data", "game");
    }
}
