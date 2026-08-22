package com.github.minecraft_ta.totaldebug.client.companion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public record CompanionSessionDescriptor(int protocolVersion, int port, long processId) {
    public static CompanionSessionDescriptor read(Path descriptorFile) throws IOException {
        Map<String, String> values = new HashMap<>();
        for (String line : Files.readAllLines(descriptorFile, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator < 1 || separator == line.length() - 1) {
                throw new IOException("Malformed companion session descriptor line: " + line);
            }
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1);
            if (!isKnownKey(key)) {
                throw new IOException("Unknown companion session descriptor field: " + key);
            }
            if (values.putIfAbsent(key, value) != null) {
                throw new IOException("Duplicate companion session descriptor field: " + key);
            }
        }

        if (values.size() != 3) {
            throw new IOException("Companion session descriptor must contain protocol, port, and pid");
        }

        try {
            int protocol = Integer.parseInt(values.get(CompanionLaunchContract.DESCRIPTOR_PROTOCOL_KEY));
            int port = Integer.parseInt(values.get(CompanionLaunchContract.DESCRIPTOR_PORT_KEY));
            long pid = Long.parseLong(values.get(CompanionLaunchContract.DESCRIPTOR_PROCESS_ID_KEY));
            if (protocol < 1) {
                throw new IOException("Invalid companion protocol version in session descriptor: " + protocol);
            }
            if (port < 1 || port > 65_535) {
                throw new IOException("Invalid companion port in session descriptor: " + port);
            }
            if (pid < 1) {
                throw new IOException("Invalid companion process id in session descriptor: " + pid);
            }
            return new CompanionSessionDescriptor(protocol, port, pid);
        } catch (NumberFormatException exception) {
            throw new IOException("Companion session descriptor contains a non-numeric value", exception);
        }
    }

    private static boolean isKnownKey(String key) {
        return key.equals(CompanionLaunchContract.DESCRIPTOR_PROTOCOL_KEY)
                || key.equals(CompanionLaunchContract.DESCRIPTOR_PORT_KEY)
                || key.equals(CompanionLaunchContract.DESCRIPTOR_PROCESS_ID_KEY);
    }
}
