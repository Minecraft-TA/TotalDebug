package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Shows files in the system's file manager. */
final class Explorer {
    private Explorer() {
    }

    /**
     * Selects {@code path} in the file manager, or opens its folder where the file manager cannot select; a path removed
     * since it was listed opens the nearest folder that still exists.
     */
    static void show(Path path) {
        try {
            if (Files.exists(path)) {
                Desktop.getDesktop().browseFileDirectory(path.toFile());
                return;
            }
        } catch (UnsupportedOperationException | IllegalArgumentException unsupported) {
            // Opening the folder below is what the platform offers.
        }
        Path folder = path.getParent();
        while (folder != null && !Files.isDirectory(folder)) folder = folder.getParent();
        if (folder == null) return;
        try {
            Desktop.getDesktop().open(folder.toFile());
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException ignored) {
            // The path stays available through Copy Path.
        }
    }
}
