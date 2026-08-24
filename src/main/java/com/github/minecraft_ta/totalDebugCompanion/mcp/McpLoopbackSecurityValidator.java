package com.github.minecraft_ta.totalDebugCompanion.mcp;

import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityValidator;

import java.util.List;
import java.util.Map;

final class McpLoopbackSecurityValidator implements ServerTransportSecurityValidator {
    private static final String LOOPBACK_HOST = "127.0.0.1";

    @Override
    public void validateHeaders(Map<String, List<String>> headers) throws ServerTransportSecurityException {
        String host = firstHeader(headers, "host");
        if (host == null || !isLoopbackHost(host)) {
            throw new ServerTransportSecurityException(403, "MCP requests must use the IPv4 loopback host");
        }

        String origin = firstHeader(headers, "origin");
        if (origin != null && !isLoopbackOrigin(origin)) {
            throw new ServerTransportSecurityException(403, "MCP request origin is not loopback");
        }
    }

    private static boolean isLoopbackHost(String host) {
        if (host.equals(LOOPBACK_HOST)) {
            return true;
        }
        if (!host.startsWith(LOOPBACK_HOST + ":")) {
            return false;
        }
        try {
            int port = Integer.parseInt(host.substring(LOOPBACK_HOST.length() + 1));
            return port >= 1 && port <= 65_535;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean isLoopbackOrigin(String origin) {
        String prefix = "http://" + LOOPBACK_HOST;
        return origin.equals(prefix) || origin.startsWith(prefix + ":");
    }

    private static String firstHeader(Map<String, List<String>> headers, String name) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (!entry.getKey().equalsIgnoreCase(name) || entry.getValue().isEmpty()) {
                continue;
            }
            return entry.getValue().getFirst();
        }
        return null;
    }
}
