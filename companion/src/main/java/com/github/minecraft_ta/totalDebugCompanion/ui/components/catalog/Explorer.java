package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.util.Workers;

import javax.swing.SwingUtilities;
import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Shows files in the system's file manager. */
final class Explorer {
    private Explorer() {
    }

    /**
     * Selects {@code path} in the file manager, or opens its folder where the file manager cannot select; a path removed
     * since it was listed opens the nearest folder that still exists. Runs on file work, as it checks the path, and gives
     * {@code shown} on the Swing thread why nothing could be shown, or an empty text.
     */
    static void show(Path path, Consumer<String> shown) {
        CompletableFuture.supplyAsync(() -> show(path), Workers.files())
                .thenAcceptAsync(shown, SwingUtilities::invokeLater);
    }

    private static String show(Path path) {
        try {
            if (Files.exists(path)) {
                Desktop.getDesktop().browseFileDirectory(path.toFile());
                return "";
            }
        } catch (UnsupportedOperationException | IllegalArgumentException unsupported) {
            // Opening the folder below is what the platform offers.
        }
        Path folder = path.getParent();
        while (folder != null && !Files.isDirectory(folder)) folder = folder.getParent();
        if (folder == null) return path + " and the folders above it are gone";
        try {
            Desktop.getDesktop().open(folder.toFile());
            return "";
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException unsupported) {
            return "No file manager could be opened for " + folder;
        }
    }
}
