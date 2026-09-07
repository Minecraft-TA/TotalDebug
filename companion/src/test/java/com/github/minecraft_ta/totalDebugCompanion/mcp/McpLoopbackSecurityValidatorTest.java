package com.github.minecraft_ta.totalDebugCompanion.mcp;

import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class McpLoopbackSecurityValidatorTest {
    private final McpLoopbackSecurityValidator validator = new McpLoopbackSecurityValidator();

    @Test
    void acceptsIpv4LoopbackWithoutAuthentication() {
        assertDoesNotThrow(() -> this.validator.validateHeaders(Map.of(
                "Host", List.of("127.0.0.1:32123")
        )));
    }

    @Test
    void rejectsNonLoopbackHostAndOrigin() {
        ServerTransportSecurityException hostFailure = assertThrows(
                ServerTransportSecurityException.class,
                () -> this.validator.validateHeaders(Map.of("host", List.of("example.com")))
        );
        assertEquals(403, hostFailure.getStatusCode());

        ServerTransportSecurityException originFailure = assertThrows(
                ServerTransportSecurityException.class,
                () -> this.validator.validateHeaders(Map.of(
                        "host", List.of("127.0.0.1:32123"),
                        "origin", List.of("https://example.com")
                ))
        );
        assertEquals(403, originFailure.getStatusCode());
    }
}
