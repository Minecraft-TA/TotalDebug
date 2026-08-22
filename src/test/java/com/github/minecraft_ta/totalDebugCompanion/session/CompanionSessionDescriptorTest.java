package com.github.minecraft_ta.totalDebugCompanion.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CompanionSessionDescriptorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void atomicallyPublishesOnlyProtocolPortAndPid() throws Exception {
        Path descriptorFile = this.temporaryDirectory.resolve(CompanionLaunchContract.SESSION_DESCRIPTOR_FILE_NAME);
        CompanionSessionDescriptor expected = new CompanionSessionDescriptor(2, 41731, 9912);

        expected.writeAtomically(descriptorFile);

        assertEquals(expected, CompanionSessionDescriptor.read(descriptorFile));
        String contents = Files.readString(descriptorFile);
        assertEquals("protocol=2\nport=41731\npid=9912\n", contents.replace("\r\n", "\n"));
        assertFalse(contents.contains("token"));
        try (var files = Files.list(this.temporaryDirectory)) {
            assertEquals(1, files.count());
        }
    }
}
