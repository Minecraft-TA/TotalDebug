package com.github.minecraft_ta.totaldebug.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameLockTest {
    @Test
    void aGameHoldsTheLockUntilItCloses(@TempDir Path directory) throws Exception {
        Path file = InstancePaths.forGame(directory).gameLock();
        assertFalse(GameLock.held(file), "no game has run in this instance");

        try (GameLock ignored = GameLock.hold(file)) {
            assertTrue(GameLock.held(file));
        }

        assertFalse(GameLock.held(file), "a closed game leaves the file unlocked");
    }
}
