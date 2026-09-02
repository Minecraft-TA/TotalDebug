package com.github.minecraft_ta.totaldebug.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class LayoutTest {
    @TempDir Path home;

    @Test
    void devRootIsExplicitAndNeverGuessedFromFolderNames() throws Exception {
        Path game = Files.createDirectories(this.home.resolve("project/run"));
        String previous = System.getProperty(InstancePaths.WORKSPACE_PROPERTY);
        try {
            System.clearProperty(InstancePaths.WORKSPACE_PROPERTY);
            assertEquals(game.resolve("total-debug"), InstancePaths.forGame(game).home());
            System.setProperty(InstancePaths.WORKSPACE_PROPERTY, game.getParent().toString());
            var dev = InstancePaths.forGame(game);
            assertEquals(game.getParent().resolve("total-debug"), dev.home());
            assertEquals(dev.home().resolve("cache/runtime/index.jindex"), dev.index());
            assertEquals(dev.home().resolve("scripts"), dev.scripts());
            assertEquals(game.resolve("total-debug/companion-app"), InstancePaths.installationDirectory(game));
        } finally {
            if (previous == null) System.clearProperty(InstancePaths.WORKSPACE_PROPERTY);
            else System.setProperty(InstancePaths.WORKSPACE_PROPERTY, previous);
        }
    }

    @Test
    void secretIsWrittenWithUserOnlyPermissions() throws Exception {
        Path secret = this.home.resolve("instance.key");
        AtomicFiles.writeSecret(secret, "test-secret");
        assertEquals("test-secret", Files.readString(secret));
        var acl = Files.getFileAttributeView(secret, java.nio.file.attribute.AclFileAttributeView.class);
        if (acl != null) {
            assertEquals(1, acl.getAcl().size());
            assertTrue(acl.getAcl().getFirst().principal().getName().endsWith(System.getProperty("user.name")));
        } else {
            assertEquals(java.util.Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(secret));
        }
    }
}
