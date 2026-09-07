package com.github.minecraft_ta.totalDebugCompanion.session;

import com.github.minecraft_ta.totalDebugCompanion.messages.session.ClientHelloMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.session.ServerHelloMessage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;

public final class SessionAuthenticator {
    private final byte[] expectedToken;

    public SessionAuthenticator(String expectedToken) {
        this.expectedToken = Objects.requireNonNull(expectedToken, "expectedToken").getBytes(StandardCharsets.UTF_8);
    }

    public synchronized ServerHelloMessage authenticate(ClientHelloMessage hello) {
        Objects.requireNonNull(hello, "hello");
        if (hello.protocolVersion() != CompanionProtocol.VERSION) {
            return ServerHelloMessage.rejected(
                    "Unsupported protocol version: expected " + CompanionProtocol.VERSION
                            + ", got " + hello.protocolVersion()
            );
        }
        byte[] receivedToken = hello.token().getBytes(StandardCharsets.UTF_8);
        boolean tokenMatches = MessageDigest.isEqual(this.expectedToken, receivedToken);
        Arrays.fill(receivedToken, (byte) 0);
        if (!tokenMatches) {
            return ServerHelloMessage.rejected("Authentication token rejected");
        }

        return ServerHelloMessage.accept();
    }
}
