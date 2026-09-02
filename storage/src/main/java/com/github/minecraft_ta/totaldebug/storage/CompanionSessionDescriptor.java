package com.github.minecraft_ta.totaldebug.storage;

import com.github.minecraft_ta.totaldebug.storage.CompanionLaunchContract;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public record CompanionSessionDescriptor(int protocolVersion, int port, long processId) {
    public CompanionSessionDescriptor {
        if (protocolVersion < 1) {
            throw new IllegalArgumentException("protocolVersion must be positive");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        if (processId < 1) {
            throw new IllegalArgumentException("processId must be positive");
        }
    }

    public void writeAtomically(Path descriptorFile) throws IOException {
        String contents = CompanionLaunchContract.DESCRIPTOR_PROTOCOL_KEY + "=" + this.protocolVersion + "\n"
                + CompanionLaunchContract.DESCRIPTOR_PORT_KEY + "=" + this.port + "\n"
                + CompanionLaunchContract.DESCRIPTOR_PROCESS_ID_KEY + "=" + this.processId + "\n";
        AtomicFiles.writeString(descriptorFile, contents);
    }

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
            if (!key.equals(CompanionLaunchContract.DESCRIPTOR_PROTOCOL_KEY)
                    && !key.equals(CompanionLaunchContract.DESCRIPTOR_PORT_KEY)
                    && !key.equals(CompanionLaunchContract.DESCRIPTOR_PROCESS_ID_KEY)) {
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
            return new CompanionSessionDescriptor(
                    Integer.parseInt(values.get(CompanionLaunchContract.DESCRIPTOR_PROTOCOL_KEY)),
                    Integer.parseInt(values.get(CompanionLaunchContract.DESCRIPTOR_PORT_KEY)),
                    Long.parseLong(values.get(CompanionLaunchContract.DESCRIPTOR_PROCESS_ID_KEY))
            );
        } catch (IllegalArgumentException exception) {
            throw new IOException("Companion session descriptor contains an invalid value", exception);
        }
    }
}
