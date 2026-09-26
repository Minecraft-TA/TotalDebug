package com.github.minecraft_ta.totalDebugCompanion.ui.views;

import com.github.minecraft_ta.totalDebugCompanion.inspection.ItemIconService;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeIndexService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class SearchEverywherePopupProcessTest {
    @TempDir Path directory;

    @Test
    void firstConstructionDoesNotDeadlockAwtClassInitialization() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        Path output = directory.resolve("popup-startup.log");
        Process process = new ProcessBuilder(java, "-Djavax.swing.adjustPopupLocationToFit=false",
                "-cp", System.getProperty("java.class.path"), Probe.class.getName())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS),
                    () -> "Popup initialization did not finish within five seconds: " + output);
            assertEquals(0, process.exitValue(), Files.readString(output));
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }

    public static final class Probe {
        public static void main(String[] arguments) throws Exception {
            try (var service = new RuntimeIndexService(new Object(), snapshot -> snapshot.close())) {
                SwingUtilities.invokeAndWait(() -> new SearchEverywherePopup(null, service, () -> null, () -> null, () -> null, new ItemIconService(), ignored -> {}).dispose());
            }
            System.exit(0);
        }
    }
}
