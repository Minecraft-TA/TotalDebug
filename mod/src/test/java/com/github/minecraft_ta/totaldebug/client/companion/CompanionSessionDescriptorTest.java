package com.github.minecraft_ta.totaldebug.client.companion;

import com.github.minecraft_ta.totaldebug.storage.CompanionSessionDescriptor;

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
    void readsBothPortsAndTheProcessIdentity() throws Exception {
        Path descriptorFile = Files.writeString(
                this.temporaryDirectory.resolve("session.properties"),
                "protocol=3\nport=41731\npid=9912\nprojectPort=41732\n"
        );

        CompanionSessionDescriptor descriptor = CompanionSessionDescriptor.read(descriptorFile);

        assertEquals(3, descriptor.protocolVersion());
        assertEquals(41731, descriptor.port());
        assertEquals(9912, descriptor.processId());
        assertEquals(41732, descriptor.projectPort());
        assertFalse(Files.readString(descriptorFile).contains("token"));
    }

    @Test
    void rejectsUnknownFieldsInsteadOfGuessing() throws Exception {
        Path descriptorFile = Files.writeString(
                this.temporaryDirectory.resolve("session.properties"),
                "protocol=3\nport=41731\npid=9912\ntoken=secret\n"
        );

        IOException exception = assertThrows(
                IOException.class,
                () -> CompanionSessionDescriptor.read(descriptorFile)
        );
        assertEquals("Unknown companion session descriptor field: token", exception.getMessage());
    }
}
