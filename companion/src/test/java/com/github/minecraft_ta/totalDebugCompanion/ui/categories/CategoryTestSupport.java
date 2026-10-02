package com.github.minecraft_ta.totalDebugCompanion.ui.categories;

import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogFixtures;
import com.github.minecraft_ta.totalDebugCompanion.catalog.PackCatalogService;
import com.github.minecraft_ta.totaldebug.storage.InstancePaths;

import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** What the category pages' tests share: a captured catalog, the Swing thread and the labels a page shows. */
public final class CategoryTestSupport {
    private CategoryTestSupport() {
    }

    /** The test mod's catalog, captured under {@code directory}. */
    public static PackCatalogService readyCatalog(Path directory) throws Exception {
        InstancePaths paths = new InstancePaths(directory.resolve("total-debug"));
        CatalogFixtures.catalog(CatalogFixtures.modJar(directory)).write(paths.catalog());
        PackCatalogService catalog = new PackCatalogService(paths);
        catalog.accept(CatalogFixtures.INVENTORY, paths.catalog(), Runnable::run);
        return catalog;
    }

    /** Waits for a page's resource listing, which keeps the test's mod file open while it runs. */
    public static void settle(CompletableFuture<?> loading) {
        try {
            loading.get(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // A failed listing has already closed the file.
        }
    }

    /** Runs {@code test} on the Swing thread, rethrowing what it threw. */
    public static void onEdt(Runnable test) throws Exception {
        Throwable[] failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                test.run();
            } catch (Throwable throwable) {
                failure[0] = throwable;
            }
        });
        if (failure[0] instanceof Error error) throw error;
        if (failure[0] instanceof Exception exception) throw exception;
    }

    /** The visible labels' texts in {@code container}, depth first. */
    public static List<String> labels(Container container) {
        List<String> labels = new ArrayList<>();
        for (Component child : container.getComponents()) {
            if (child instanceof JLabel label && label.isVisible() && label.getText() != null) {
                labels.add(label.getText());
            }
            if (child instanceof Container nested) labels.addAll(labels(nested));
        }
        return labels;
    }
}
