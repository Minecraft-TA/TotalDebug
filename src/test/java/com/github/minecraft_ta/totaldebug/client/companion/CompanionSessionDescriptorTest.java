package com.github.minecraft_ta.totaldebug.client.companion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompanionSessionDescriptorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void readsTheExactThreeFieldDescriptor() throws Exception {
        Path descriptorFile = Files.writeString(
                this.temporaryDirectory.resolve("session.properties"),
                "protocol=2\nport=41731\npid=9912\n"
        );

        CompanionSessionDescriptor descriptor = CompanionSessionDescriptor.read(descriptorFile);

        assertEquals(2, descriptor.protocolVersion());
        assertEquals(41731, descriptor.port());
        assertEquals(9912, descriptor.processId());
        assertFalse(Files.readString(descriptorFile).contains("token"));
    }

    @Test
    void rejectsUnknownFieldsInsteadOfGuessing() throws Exception {
        Path descriptorFile = Files.writeString(
                this.temporaryDirectory.resolve("session.properties"),
                "protocol=2\nport=41731\npid=9912\ntoken=secret\n"
        );

        IOException exception = assertThrows(
                IOException.class,
                () -> CompanionSessionDescriptor.read(descriptorFile)
        );
        assertEquals("Unknown companion session descriptor field: token", exception.getMessage());
    }
}
