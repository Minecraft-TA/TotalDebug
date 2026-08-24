package com.github.minecraft_ta.totalDebugCompanion.util;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;

public class FileUtils {

    public static Runnable startNewDirectoryWatcher(Path directory, Runnable onChange) {
        try {
            var watchService = FileSystems.getDefault().newWatchService();
            directory.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE);

            Thread watcherThread = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        var key = watchService.take();

                        if (key.pollEvents().stream()
                                .anyMatch(e -> e.kind() != StandardWatchEventKinds.OVERFLOW &&
                                               e.kind() != StandardWatchEventKinds.ENTRY_MODIFY)) {
                            onChange.run();
                        }

                        if (!key.reset())
                            break;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (ClosedWatchServiceException ignored) {
                        break;
                    }
                }
            }, "directory-watcher-" + directory.getFileName());
            watcherThread.setDaemon(true);
            watcherThread.start();
            return () -> {
                try {
                    watchService.close();
                } catch (IOException ignored) {
                }
                watcherThread.interrupt();
            };
        } catch (IOException e) {
            throw new RuntimeException("Unable to watch directory " + directory, e);
        }
    }

    public static boolean isSubPathOf(Path base, Path other) {
        var it1 = other.normalize().iterator();

        for (Path part : base.normalize()) {
            if (!it1.hasNext() || !part.equals(it1.next()))
                return false;
        }

        return it1.hasNext();
    }

    public static URI toURI(String s) {
        try {
            return new URI(s);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static void createIfNotExists(Path path, boolean dir) {
        if (Files.exists(path))
            return;

        try {
            if (dir)
                Files.createDirectories(path);
            else
                Files.createFile(path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
