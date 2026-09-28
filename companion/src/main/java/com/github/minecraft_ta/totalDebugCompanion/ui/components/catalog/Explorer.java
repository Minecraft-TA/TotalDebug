package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Shows files in the system's file manager. */
final class Explorer {
    private Explorer() {
    }

    /**
     * Selects {@code path} in the file manager, or opens its folder where the file manager cannot select; a path removed
     * since it was listed opens the nearest folder that still exists. Returns why nothing could be shown, or empty.
     */
    static Optional<String> show(Path path) {
        try {
            if (Files.exists(path)) {
                Desktop.getDesktop().browseFileDirectory(path.toFile());
                return Optional.empty();
            }
        } catch (UnsupportedOperationException | IllegalArgumentException unsupported) {
            // Opening the folder below is what the platform offers.
        }
        Path folder = path.getParent();
        while (folder != null && !Files.isDirectory(folder)) folder = folder.getParent();
        if (folder == null) return Optional.of(path + " and the folders above it are gone");
        try {
            Desktop.getDesktop().open(folder.toFile());
            return Optional.empty();
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException unsupported) {
            return Optional.of("No file manager could be opened for " + folder);
        }
    }
}
