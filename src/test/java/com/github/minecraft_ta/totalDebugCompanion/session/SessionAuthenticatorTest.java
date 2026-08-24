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
    void acceptsTheExactTokenAndIntersectsCapabilities() {
        SessionAuthenticator authenticator = new SessionAuthenticator("correct-token-value", 0b0111);

        ServerHelloMessage response = authenticator.authenticate(hello(
                CompanionProtocol.VERSION,
                "correct-token-value",
                0b1111
        ));

        assertTrue(response.accepted());
        assertEquals(0b0111, response.capabilities());
        assertEquals("", response.rejectionReason());
    }

    @Test
    void rejectsAWrongTokenWithAnExactReason() {
        SessionAuthenticator authenticator = new SessionAuthenticator("correct-token-value", 0b0111);

        ServerHelloMessage response = authenticator.authenticate(hello(
                CompanionProtocol.VERSION,
                "wrong-token-value",
                0b0111
        ));

        assertFalse(response.accepted());
        assertEquals("Authentication token rejected", response.rejectionReason());
    }

    @Test
    void rejectsAVersionMismatchWithBothVersions() {
        SessionAuthenticator authenticator = new SessionAuthenticator("correct-token-value", 0b0111);

        ServerHelloMessage response = authenticator.authenticate(hello(1, "correct-token-value", 0b0111));

        assertFalse(response.accepted());
        assertEquals("Unsupported protocol version: expected 5, got 1", response.rejectionReason());
    }

    private static ClientHelloMessage hello(int version, String token, long capabilities) {
        byte[] tokenBytes = token.getBytes(StandardCharsets.UTF_8);
        byte[] value = "x".getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(
                Integer.BYTES + Integer.BYTES + tokenBytes.length + Long.BYTES
                        + 3 * (Integer.BYTES + value.length)
        );
        buffer.putInt(version);
        buffer.putInt(tokenBytes.length);
        buffer.put(tokenBytes);
        buffer.putLong(capabilities);
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
