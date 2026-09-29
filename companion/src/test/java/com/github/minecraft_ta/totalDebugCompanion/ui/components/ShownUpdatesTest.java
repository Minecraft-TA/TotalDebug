package com.github.minecraft_ta.totalDebugCompanion.ui.components;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShownUpdatesTest {
    @Test
    void aHiddenPageUpdatesOnceWhenShownAndNotAfterItStopped() throws Exception {
        List<Runnable> source = new CopyOnWriteArrayList<>();
        AtomicInteger updates = new AtomicInteger();
        ShowablePage page = new ShowablePage();
        Runnable[] stop = new Runnable[1];
        SwingUtilities.invokeAndWait(() -> stop[0] = ShownUpdates.follow(page, listener -> {
            source.add(listener);
            return () -> source.remove(listener);
        }, updates::incrementAndGet));

        source.forEach(Runnable::run);
        source.forEach(Runnable::run);
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(0, updates.get(), "a hidden page does not update");
        SwingUtilities.invokeAndWait(() -> page.setShown(true));
        assertEquals(1, updates.get(), "shown, it updates once for the changes it missed");
        SwingUtilities.invokeAndWait(() -> {
            page.setShown(false);
            page.setShown(true);
        });
        assertEquals(1, updates.get(), "shown again without a change, it does not update");

        source.forEach(Runnable::run);
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(2, updates.get(), "a shown page updates at once");

        // A change queued before it stopped does not update a page that is gone.
        SwingUtilities.invokeAndWait(() -> {
            source.forEach(Runnable::run);
            stop[0].run();
        });
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(2, updates.get());
        assertEquals(0, source.size(), "it stopped following the source");
    }
}
