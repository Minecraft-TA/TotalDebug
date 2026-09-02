package com.github.minecraft_ta.totaldebug.storage;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/** A process-shared read lock with reference counting for multiple users in the same JVM. */
public final class FileLease implements AutoCloseable {
    private static final Map<Path, Pin> PINS = new HashMap<>();
    private final Path path;
    private boolean closed;

    private FileLease(Path path) {
        this.path = path;
    }

    public static synchronized FileLease acquire(Path file) throws IOException {
        Path path = file.toRealPath();
        Pin pin = PINS.get(path);
        if (pin == null) {
            FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
            try {
                pin = new Pin(channel, channel.lock(0, Long.MAX_VALUE, true));
                PINS.put(path, pin);
            } catch (IOException | RuntimeException exception) {
                channel.close();
                throw exception;
            }
        }
        pin.users++;
        return new FileLease(path);
    }

    public Path path() {
        return this.path;
    }

    @Override
    public void close() throws IOException {
        synchronized (FileLease.class) {
            if (this.closed) {
                return;
            }
            this.closed = true;
            Pin pin = PINS.get(this.path);
            if (--pin.users == 0) {
                PINS.remove(this.path);
                try {
                    pin.lock.release();
                } finally {
                    pin.channel.close();
                }
            }
        }
    }

    private static final class Pin {
        private final FileChannel channel;
        private final FileLock lock;
        private int users;

        private Pin(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }
    }
}
