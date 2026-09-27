package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;

/** Shows files in the system's file manager. */
final class Explorer {
    private Explorer() {
    }

    /** Selects {@code path} in the file manager, or opens its folder where the file manager cannot select. */
    static void show(Path path) {
        try {
            Desktop.getDesktop().browseFileDirectory(path.toFile());
        } catch (UnsupportedOperationException exception) {
            try {
                Desktop.getDesktop().open(path.getParent().toFile());
            } catch (IOException | UnsupportedOperationException ignored) {
                // The path stays available through Copy Path.
            }
        }
    }
}
