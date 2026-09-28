package com.github.minecraft_ta.totaldebug.storage;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The lock a running game holds on its instance for as long as it runs. Companion tells a running game from a closed
 * one by it, without a connection, as the game tells a running Companion by its instance lock.
 */
public final class GameLock implements AutoCloseable {
    private static final int HOLD_ATTEMPTS = 20;
    private static final long HOLD_RETRY_MILLIS = 50;

    private final FileChannel channel;
    private final FileLock lock;

    private GameLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * Takes the lock for this game process. Companion checks the lock by taking it for a moment, so a lock held only that
     * long is tried again for a second before another game is taken to run.
     */
    public static GameLock hold(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            for (int attempt = 0; lock == null && attempt < HOLD_ATTEMPTS; attempt++) {
                try {
                    Thread.sleep(HOLD_RETRY_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
                lock = channel.tryLock();
            }
            if (lock == null) throw new IOException("Another game already runs in this instance");
            return new GameLock(channel, lock);
        } catch (IOException | RuntimeException failure) {
            channel.close();
            throw failure;
        }
    }

    /**
     * Whether a game holds the lock. A lock that cannot be checked counts as held, since writing the game's files
     * while it runs would be undone.
     */
    public static boolean held(Path file) {
        if (!Files.isRegularFile(file)) return false;
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            FileLock lock = channel.tryLock();
            if (lock == null) return true;
            lock.release();
            return false;
        } catch (OverlappingFileLockException sameProcess) {
            return true;
        } catch (IOException unknown) {
            return true;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            this.lock.release();
        } finally {
            this.channel.close();
        }
    }
}
