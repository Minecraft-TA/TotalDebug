package com.github.minecraft_ta.totalDebugCompanion.ui.views;

import com.github.minecraft_ta.totalDebugCompanion.UiDevHarness;
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

        ProcessResult result = runProcess(Duration.ofSeconds(5), Probe.class.getName());
        assertTrue(result.finished(), "Popup initialization did not finish within five seconds\n" + result.output());
        assertEquals(0, result.exitCode(), result.output());
    }

    @Test
    void typingKeepsResultsVisibleAndTheHeaderDragsTheWindow() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());

        ProcessResult result = runProcess(
                Duration.ofSeconds(15),
                UiDevHarness.class.getName(),
                "islands-dark",
                "--verify-search-everywhere-interactions"
        );
        assertTrue(result.finished(), "Search interaction verification timed out\n" + result.output());
        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("Search Everywhere interaction verification passed"), result.output());
    }

    private static ProcessResult runProcess(Duration timeout, String mainClass, String... arguments) throws Exception {

        String javaExecutable = Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java"
        ).toString();
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add(javaExecutable);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(mainClass);
        command.addAll(java.util.List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();

        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly().waitFor();
        }
        String output = new String(process.getInputStream().readAllBytes());
        return new ProcessResult(finished, process.exitValue(), output);
    }

    private record ProcessResult(boolean finished, int exitCode, String output) {
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
