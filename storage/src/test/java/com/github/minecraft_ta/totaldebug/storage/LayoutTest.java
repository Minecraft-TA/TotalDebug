package com.github.minecraft_ta.totaldebug.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LayoutTest {
    @TempDir Path home;

    @Test
    void developmentDataStaysInsideTheGameDirectory() throws Exception {
        Path game = Files.createDirectories(this.home.resolve("project/run"));
        var instance = InstancePaths.forGame(game);
        assertEquals(game.resolve("total-debug"), instance.home());
        assertEquals(instance.home().resolve("cache/runtime/index.jindex"), instance.index());
        assertEquals(instance.home().resolve("scripts"), instance.scripts());
        assertEquals(instance.home().resolve("cache/runtime/catalog.json"), instance.catalog());
        assertEquals(instance.home().resolve("cache/inspection-previews"), instance.previews());
        assertEquals(instance.home().resolve("companion-app"), InstancePaths.installationDirectory(game));
    }

    @Test
    void secretIsWrittenWithUserOnlyPermissions() throws Exception {
        Path secret = this.home.resolve("instance.key");
        AtomicFiles.writeSecret(secret, "test-secret");
        assertEquals("test-secret", Files.readString(secret));
        var acl = Files.getFileAttributeView(secret, AclFileAttributeView.class);
        if (acl != null) {
            assertEquals(1, acl.getAcl().size());
            assertTrue(acl.getAcl().getFirst().principal().getName().endsWith(System.getProperty("user.name")));
        } else {
            assertEquals(Set.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(secret));
        }
    }
}
