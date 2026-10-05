package com.github.minecraft_ta.totalDebugCompanion.ui.categories;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExplorerTest {
    @Test
    void aPathIsCheckedOffTheSwingThreadAndItsRefusalShownOnIt() throws Exception {
        // Neither the path nor a folder above it exists, so no file manager opens.
        Path gone = Path.of("explorer-test-" + System.nanoTime(), "gone.txt");
        CompletableFuture<String> shown = new CompletableFuture<>();
        boolean[] onSwing = new boolean[1];
        boolean[] answeredAtOnce = new boolean[1];
        SwingUtilities.invokeAndWait(() -> {
            Explorer.show(gone, refusal -> {
                onSwing[0] = SwingUtilities.isEventDispatchThread();
                shown.complete(refusal);
            });
            answeredAtOnce[0] = shown.isDone();
        });

        String refusal = shown.get(5, TimeUnit.SECONDS);
        assertFalse(answeredAtOnce[0], "the Swing step that asked does not wait for the check");
        assertTrue(onSwing[0], "the refusal is shown on the Swing thread");
        assertTrue(refusal.endsWith("and the folders above it are gone"), refusal);
    }
}
