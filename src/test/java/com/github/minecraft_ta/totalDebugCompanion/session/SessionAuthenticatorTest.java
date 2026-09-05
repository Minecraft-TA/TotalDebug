package com.github.minecraft_ta.totalDebugCompanion.session;

import com.github.minecraft_ta.totalDebugCompanion.messages.session.ClientHelloMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.session.ServerHelloMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionAuthenticatorTest {
    @Test
    void acceptsTheExactVersionAndToken() {
        SessionAuthenticator authenticator = new SessionAuthenticator("correct-token-value");

        ServerHelloMessage response = authenticator.authenticate(hello(
                CompanionProtocol.VERSION,
                "correct-token-value"
        ));

        assertTrue(response.accepted());
        assertEquals("", response.rejectionReason());
    }

    @Test
    void rejectsAWrongTokenWithAnExactReason() {
        SessionAuthenticator authenticator = new SessionAuthenticator("correct-token-value");

        ServerHelloMessage response = authenticator.authenticate(hello(
                CompanionProtocol.VERSION,
                "wrong-token-value"
        ));

        assertFalse(response.accepted());
        assertEquals("Authentication token rejected", response.rejectionReason());
    }

    @Test
    void rejectsAVersionMismatchWithBothVersions() {
        SessionAuthenticator authenticator = new SessionAuthenticator("correct-token-value");

        ServerHelloMessage response = authenticator.authenticate(hello(1, "correct-token-value"));

        assertFalse(response.accepted());
        assertEquals("Unsupported protocol version: expected " + CompanionProtocol.VERSION + ", got 1", response.rejectionReason());
    }

    private static ClientHelloMessage hello(int version, String token) {
        byte[] tokenBytes = token.getBytes(StandardCharsets.UTF_8);
        byte[] value = "x".getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(
                Integer.BYTES + Integer.BYTES + tokenBytes.length
                        + 3 * (Integer.BYTES + value.length)
        );
        buffer.putInt(version);
        buffer.putInt(tokenBytes.length);
        buffer.put(tokenBytes);
        for (int index = 0; index < 3; index++) {
            buffer.putInt(value.length);
            buffer.put(value);
        }
        buffer.flip();
        ClientHelloMessage message = new ClientHelloMessage();
        message.read(new ByteBufferInputStream(buffer));
        return message;
    }
}
