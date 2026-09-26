package com.github.minecraft_ta.totaldebug.client.catalog;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.protocol.scnet.PackCatalogMessage;
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
    private final Consumer<PackCatalogMessage> send;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> Thread.ofPlatform()
            .daemon()
            .name("TotalDebug pack catalog")
            .unstarted(task));
    private String inventoryId;
    private String requestedLanguage;
    private PackCatalogMessage state;
    private long generation;
    private boolean stale;

    public PackCatalogPublisher(Path file, Supplier<String> language, CaptureStarter captures,
                                Consumer<PackCatalogMessage> send) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        this.language = Objects.requireNonNull(language, "language");
        this.captures = Objects.requireNonNull(captures, "captures");
        this.send = Objects.requireNonNull(send, "send");
    }

    /**
     * Called whenever Companion has the given inventory; repeats the current state for the same inventory and
     * language. Names are translated, so a language change captures again.
     */
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

    public synchronized void request(String inventoryId, Map<String, String> moduleByModId) {
        Objects.requireNonNull(inventoryId, "inventoryId");
        String language = this.language.get();
        if (inventoryId.equals(this.inventoryId) && language.equals(this.requestedLanguage)) {
            if (this.state == null) {
                return;
            }
            if (this.state.state() != PackCatalogMessage.FAILED) {
                this.send.accept(this.state);
                return;
            }
        }
        this.inventoryId = inventoryId;
        this.requestedLanguage = language;
        this.state = null;
        long current = ++this.generation;
        Map<String, String> modules = Map.copyOf(moduleByModId);
        boolean reuse = !this.stale;
        this.stale = false;
        this.worker.execute(() -> prepare(current, inventoryId, language, modules, reuse));
    }

    private void prepare(long generation, String inventoryId, String language, Map<String, String> modules,
                         boolean reuse) {
        if (reuse && Files.isRegularFile(this.file)) {
            try {
                // Read in full: a file whose header matches but whose body is damaged is captured again.
                PackCatalog saved = PackCatalog.read(this.file);
                if (saved.inventoryId().equals(inventoryId) && saved.language().equals(language)) {
                    publish(generation, PackCatalogMessage.available(inventoryId, this.file.toString()));
                    return;
                }
            } catch (IOException | RuntimeException exception) {
                TotalDebug.LOGGER.info("Recapturing the pack catalog: {}", exception.getMessage());
            }
        }
        publish(generation, PackCatalogMessage.capturing(inventoryId));
        this.captures.start(inventoryId, language, modules).whenCompleteAsync((catalog, failure) -> {
            // A newer request owns the file and the announced state; a superseded capture leaves both alone.
            if (!isCurrent(generation)) {
                return;
            }
            if (failure == null) {
                try {
                    catalog.write(this.file);
                    publish(generation, PackCatalogMessage.available(inventoryId, this.file.toString()));
                    return;
                } catch (IOException | RuntimeException exception) {
                    failure = exception;
                }
            }
            TotalDebug.LOGGER.error("Unable to publish the pack catalog", failure);
            String detail = failure.getMessage();
            publish(generation, PackCatalogMessage.failed(inventoryId,
                    detail == null || detail.isBlank() ? "Pack catalog capture failed" : detail));
        }, this.worker);
    }

    private synchronized boolean isCurrent(long generation) {
        return generation == this.generation;
    }

    private synchronized void publish(long generation, PackCatalogMessage message) {
        if (generation != this.generation) {
            return;
        }
        this.state = message;
        this.send.accept(message);
    }

    @Override
    public void close() {
        this.worker.shutdownNow();
    }
}
