package com.github.minecraft_ta.totaldebug.client.catalog;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.protocol.message.PreparedFilePayload;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Publishes the pack catalog belonging to the current runtime inventory. A catalog already written for the same
 * inventory and language is reused; otherwise the game captures it once and writes it next to the inventory.
 *
 * <p>After a resource reload the catalog is captured again, quietly: Companion keeps showing the catalog it was told,
 * and hears of the new one only when it differs, as it does when a pack changes names or tints. Most reloads change
 * neither, so Companion reads and indexes nothing.
 */
public final class PackCatalogPublisher implements AutoCloseable {
    /** Starts a capture on the client thread and completes with its result. */
    @FunctionalInterface
    public interface CaptureStarter {
        CompletableFuture<PackCatalog> start(String inventoryId, String language, Map<String, String> moduleByModId);
    }

    private final Path file;
    private final Supplier<String> language;
    private final CaptureStarter captures;
    private final Consumer<PreparedFilePayload> send;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> Thread.ofPlatform()
            .daemon()
            .name("TotalDebug pack catalog")
            .unstarted(task));
    private String inventoryId;
    private String requestedLanguage;
    private PreparedFilePayload state;
    private long generation;
    private boolean stale;
    /** The catalog Companion was told of last, or null before any. */
    private PackCatalog told;

    public PackCatalogPublisher(Path file, Supplier<String> language, CaptureStarter captures,
                                Consumer<PreparedFilePayload> send) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        this.language = Objects.requireNonNull(language, "language");
        this.captures = Objects.requireNonNull(captures, "captures");
        this.send = Objects.requireNonNull(send, "send");
    }

    /**
     * Captures again on the next request, also for the same inventory and language: the game's resources changed, and
     * the catalog's names come from them. Nothing happens before the first request, so a saved catalog is still reused
     * when the game starts.
     */
    public synchronized void recapture() {
        if (this.inventoryId == null) return;
        this.inventoryId = null;
        this.stale = true;
    }

    /**
     * Called whenever Companion has the given inventory. The catalog for the same inventory and language is already
     * told, or being captured; a failed one is captured again. Names are translated, so a language change captures again.
     */
    public synchronized void request(String inventoryId, Map<String, String> moduleByModId) {
        Objects.requireNonNull(inventoryId, "inventoryId");
        String language = this.language.get();
        if (inventoryId.equals(this.inventoryId) && language.equals(this.requestedLanguage)
                && (this.state == null || this.state.state() != PreparedFilePayload.State.FAILED)) {
            return;
        }
        this.inventoryId = inventoryId;
        this.requestedLanguage = language;
        this.state = null;
        long current = ++this.generation;
        Map<String, String> modules = Map.copyOf(moduleByModId);
        boolean reuse = !this.stale;
        // A catalog captured again for the inventory Companion has one of changes it only where the new one differs.
        boolean quietly = this.told != null && this.told.inventoryId().equals(inventoryId);
        this.stale = false;
        this.worker.execute(() -> prepare(current, inventoryId, language, modules, reuse, quietly));
    }

    private void prepare(long generation, String inventoryId, String language, Map<String, String> modules,
                         boolean reuse, boolean quietly) {
        if (reuse && Files.isRegularFile(this.file)) {
            try {
                // Read in full: a file whose header matches but whose body is damaged is captured again.
                PackCatalog saved = PackCatalog.read(this.file);
                if (saved.inventoryId().equals(inventoryId) && saved.language().equals(language)) {
                    publish(generation, ready(inventoryId), saved);
                    return;
                }
            } catch (IOException | RuntimeException exception) {
                TotalDebug.LOGGER.info("Recapturing the pack catalog: {}", exception.getMessage());
            }
        }
        if (!quietly) publish(generation, PreparedFilePayload.preparing(PreparedFilePayload.Kind.PACK_CATALOG, inventoryId, ""), null);
        long started = System.nanoTime();
        this.captures.start(inventoryId, language, modules).whenCompleteAsync((catalog, failure) -> {
            // A newer request owns the file and the announced state; a superseded capture leaves both alone.
            if (!isCurrent(generation)) {
                return;
            }
            if (failure == null) {
                if (quietly && catalog.equals(told())) {
                    TotalDebug.LOGGER.info("Pack catalog unchanged after {} ms; Companion keeps the one it has",
                            (System.nanoTime() - started) / 1_000_000);
                    return;
                }
                try {
                    catalog.write(this.file);
                    publish(generation, ready(inventoryId), catalog);
                    TotalDebug.LOGGER.info("Pack catalog {} after {} ms", quietly ? "changed" : "published",
                            (System.nanoTime() - started) / 1_000_000);
                    return;
                } catch (IOException | RuntimeException exception) {
                    failure = exception;
                }
            }
            if (quietly) {
                // Companion keeps the catalog it has, which only lacks what the reload changed.
                TotalDebug.LOGGER.error("Unable to capture the pack catalog again; Companion keeps the one it has", failure);
                return;
            }
            TotalDebug.LOGGER.error("Unable to publish the pack catalog", failure);
            String detail = failure.getMessage();
            publish(generation, PreparedFilePayload.failed(PreparedFilePayload.Kind.PACK_CATALOG, inventoryId,
                    detail == null || detail.isBlank() ? "Pack catalog capture failed" : detail), null);
        }, this.worker);
    }

    private PreparedFilePayload ready(String inventoryId) {
        return PreparedFilePayload.ready(PreparedFilePayload.Kind.PACK_CATALOG, inventoryId, this.file.toString());
    }

    private synchronized boolean isCurrent(long generation) {
        return generation == this.generation;
    }

    /** Tells {@code message}, and with a ready one the {@code catalog} it names. */
    private synchronized void publish(long generation, PreparedFilePayload message, PackCatalog catalog) {
        if (generation != this.generation) {
            return;
        }
        this.state = message;
        if (catalog != null) this.told = catalog;
        this.send.accept(message);
    }

    private synchronized PackCatalog told() {
        return this.told;
    }

    @Override
    public void close() {
        this.worker.shutdownNow();
    }
}
