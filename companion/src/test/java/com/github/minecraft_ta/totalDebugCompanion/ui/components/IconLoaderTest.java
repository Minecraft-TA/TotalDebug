package com.github.minecraft_ta.totalDebugCompanion.ui.components;

import org.junit.jupiter.api.Test;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class IconLoaderTest {
    private static final Icon ICON = new ImageIcon(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB));

    private final Map<String, CompletableFuture<Optional<Icon>>> loads = new LinkedHashMap<>();
    private final JLabel painting = new JLabel();
    private final IconLoader<String> loader =
            new IconLoader<>(10, 2, key -> this.loads.computeIfAbsent(key, ignored -> new CompletableFuture<>()));

    @Test
    void loadsAFewAtATimeAndKeepsWhatLoaded() throws Exception {
        onEdt(() -> {
            assertNull(this.loader.icon("a", this.painting));
            assertNull(this.loader.icon("b", this.painting));
            assertNull(this.loader.icon("c", this.painting));
            assertEquals(2, this.loads.size(), "Only two loads run at a time");
            this.loads.get("a").complete(Optional.of(ICON));
        });
        onEdt(() -> {
            assertSame(ICON, this.loader.icon("a", this.painting));
            assertNull(this.loader.icon("c", this.painting));
            assertEquals(3, this.loads.size(), "A finished load makes room for the next");
        });
    }

    @Test
    void aClearIgnoresLoadsThatWereStillRunning() throws Exception {
        onEdt(() -> {
            assertNull(this.loader.icon("old", this.painting));
            this.loader.clear();
            this.loads.get("old").complete(Optional.of(ICON));
        });
        onEdt(() -> {
            this.loads.remove("old");
            assertNull(this.loader.icon("old", this.painting), "A result from before the clear is not kept");
            assertEquals(1, this.loads.size(), "It is loaded again");
        });
    }

    /** Runs on the Swing thread after the completions queued so far. */
    private static void onEdt(Runnable action) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
        });
        SwingUtilities.invokeAndWait(action);
    }
}
