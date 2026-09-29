package com.github.minecraft_ta.totalDebugCompanion.ui.components;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.util.function.Function;

/**
 * Updates a page when a source it follows changes: at once while the page is shown, otherwise once it is shown again, so
 * a hidden page does no work for a change it cannot show. For pages that update themselves rather than through a
 * {@link PageLoader}.
 */
public final class ShownUpdates {
    private ShownUpdates() {
    }

    /**
     * Runs {@code update} on the Swing thread whenever the source {@code subscribe} adds a listener to changes, as
     * {@code catalog::addListener} does, and {@code page} is shown, or when it is shown after a change. Returns what stops
     * following; an update queued before then does not run.
     */
    public static Runnable follow(JComponent page, Function<Runnable, Runnable> subscribe, Runnable update) {
        Following following = new Following(page, update);
        page.addHierarchyListener(following);
        Runnable unsubscribe = subscribe.apply(() -> SwingUtilities.invokeLater(following::changed));
        return () -> {
            following.stopped = true;
            unsubscribe.run();
            page.removeHierarchyListener(following);
        };
    }

    private static final class Following implements HierarchyListener {
        private final JComponent page;
        private final Runnable update;
        /** Whether the source changed while the page was hidden. */
        private boolean stale;
        private boolean stopped;

        Following(JComponent page, Runnable update) {
            this.page = page;
            this.update = update;
        }

        void changed() {
            if (this.stopped) return;
            if (this.page.isShowing()) this.update.run();
            else this.stale = true;
        }

        @Override
        public void hierarchyChanged(HierarchyEvent event) {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0 || !this.page.isShowing() || !this.stale) return;
            this.stale = false;
            if (!this.stopped) this.update.run();
        }
    }
}
