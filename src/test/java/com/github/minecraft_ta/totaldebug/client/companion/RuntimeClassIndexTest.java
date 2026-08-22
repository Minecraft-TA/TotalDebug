package com.github.minecraft_ta.totaldebug.client.companion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RuntimeClassIndexTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void classDirectoryFingerprintUsesContentRatherThanTimestamps() throws Exception {
        Path classFile = this.temporaryDirectory.resolve("example/Fixture.class");
        Files.createDirectories(classFile.getParent());
        Files.writeString(classFile, "first");
        String initialFingerprint = fingerprint(this.temporaryDirectory);

        Files.setLastModifiedTime(classFile, FileTime.from(Instant.now().plusSeconds(30)));
        assertEquals(initialFingerprint, fingerprint(this.temporaryDirectory));

        Files.writeString(classFile, "second");
        assertNotEquals(initialFingerprint, fingerprint(this.temporaryDirectory));
    }

    private static String fingerprint(Path directory) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        RuntimeClassIndex.updateClassDirectoryDigest(digest, directory);
        return HexFormat.of().formatHex(digest.digest());
    }
}
