package com.github.minecraft_ta.totalDebugCompanion.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.Locale;

/** The worlds in a game directory's {@code saves}, and which one the running game has open. Blocking. */
public final class Worlds {
    private Worlds() {
    }

    /** The world the game has open, found by its held session lock, or null. */
    public static Path open(Path workspace) {
        if (workspace == null) return null;
        try (DirectoryStream<Path> saves = Files.newDirectoryStream(workspace.resolve("saves"), Files::isDirectory)) {
            for (Path world : saves) {
                if (isOpen(world)) return world;
            }
        } catch (IOException noSaves) {
            // Without saves no world is open.
        }
        return null;
    }

    /** The world played last, by the time its {@code level.dat} was written, or null without worlds. */
    public static Path lastPlayed(Path workspace) {
        if (workspace == null) return null;
        Path latest = null;
        FileTime latestTime = null;
        try (DirectoryStream<Path> saves = Files.newDirectoryStream(workspace.resolve("saves"), Files::isDirectory)) {
            for (Path world : saves) {
                Path level = world.resolve("level.dat");
                if (!Files.isRegularFile(level)) continue;
                FileTime time = Files.getLastModifiedTime(level);
                if (latestTime == null || time.compareTo(latestTime) > 0) {
                    latest = world;
                    latestTime = time;
                }
            }
        } catch (IOException noSaves) {
            // Without saves there is no world.
        }
        return latest;
    }

    /**
     * Whether a game holds the world's {@code session.lock}. Windows refuses to read a locked file, so looking never
     * takes the lock there; elsewhere the lock is taken and released at once.
     */
    public static boolean isOpen(Path world) {
        if (world == null) return false;
        Path lock = world.resolve("session.lock");
        if (!Files.isRegularFile(lock)) return false;
        // Reading one byte is enough: Windows refuses any read of a locked file, however large it is.
        try (InputStream input = Files.newInputStream(lock)) {
            input.read();
        } catch (IOException locked) {
            return true;
        }
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) return false;
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.WRITE)) {
            FileLock held = channel.tryLock();
            if (held == null) return true;
            held.release();
            return false;
        } catch (OverlappingFileLockException ownLock) {
            return true;
        } catch (IOException unreadable) {
            return false;
        }
    }
}
