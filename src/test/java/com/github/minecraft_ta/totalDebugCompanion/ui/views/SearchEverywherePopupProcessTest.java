package com.github.minecraft_ta.totalDebugCompanion.ui.views;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class SearchEverywherePopupProcessTest {
    @Test
    void firstConstructionDoesNotDeadlockAwtClassInitialization() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());

        String javaExecutable = Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java"
        ).toString();
        Process process = new ProcessBuilder(
                javaExecutable,
                "-cp",
                System.getProperty("java.class.path"),
                Probe.class.getName()
        ).redirectErrorStream(true).start();

        boolean finished = process.waitFor(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly().waitFor();
        }
        String output = new String(process.getInputStream().readAllBytes());

        assertTrue(finished, "Popup initialization did not finish within five seconds\n" + output);
        assertEquals(0, process.exitValue(), output);
    }

    public static final class Probe {
        private Probe() {
        }

        public static void main(String[] arguments) throws Exception {
            SwingUtilities.invokeAndWait(() -> new SearchEverywherePopup().dispose());
            System.exit(0);
        }
    }
}
