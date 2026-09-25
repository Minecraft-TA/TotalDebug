package com.github.minecraft_ta.totaldebug.client.catalog;

import com.github.minecraft_ta.totaldebug.protocol.scnet.PackCatalogMessage;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@Timeout(10)
class PackCatalogPublisherTest {
    @TempDir Path directory;
    private final BlockingQueue<PackCatalogMessage> sent = new LinkedBlockingQueue<>();
    private final AtomicInteger captures = new AtomicInteger();
    private final AtomicReference<String> language = new AtomicReference<>("en_us");
    private volatile CompletableFuture<PackCatalog> capture = new CompletableFuture<>();
    private PackCatalogPublisher publisher;

    @AfterEach
    void close() {
        if (this.publisher != null) this.publisher.close();
    }

    @Test
    void capturesWritesAndAnnouncesTheCatalog() throws Exception {
        Path file = this.directory.resolve("catalog.json");
        PackCatalogPublisher publisher = publisher(file);

        publisher.request("inventory", Map.of("mekanism", "mekanism"));
        assertEquals(PackCatalogMessage.CAPTURING, next().state());
        this.capture.complete(catalog("inventory", "en_us"));

        PackCatalogMessage available = next();
        assertEquals(PackCatalogMessage.AVAILABLE, available.state());
        assertEquals("inventory", available.inventoryId());
        assertEquals(file.toString(), available.catalogFile());
        assertEquals(catalog("inventory", "en_us"), PackCatalog.read(file));

        publisher.request("inventory", Map.of());
        assertEquals(PackCatalogMessage.AVAILABLE, next().state());
        awaitCaptures(1);
    }

    @Test
    void reusesAMatchingCatalogAndRecapturesForAnotherInventoryOrLanguage() throws Exception {
        Path file = this.directory.resolve("catalog.json");
        catalog("inventory", "en_us").write(file);

        publisher(file).request("inventory", Map.of());
        assertEquals(PackCatalogMessage.AVAILABLE, next().state());
        assertEquals(0, this.captures.get());

        this.language.set("de_de");
        this.publisher.request("other", Map.of());
        assertEquals(PackCatalogMessage.CAPTURING, next().state());
        awaitCaptures(1);
    }

    @Test
    void reportsAFailedCaptureAndRetriesOnTheNextRequest() throws Exception {
        PackCatalogPublisher publisher = publisher(this.directory.resolve("catalog.json"));

        publisher.request("inventory", Map.of());
        assertEquals(PackCatalogMessage.CAPTURING, next().state());
        this.capture.completeExceptionally(new IllegalStateException("registry exploded"));
        PackCatalogMessage failed = next();
        assertEquals(PackCatalogMessage.FAILED, failed.state());
        assertEquals("registry exploded", failed.detail());

        this.capture = new CompletableFuture<>();
        publisher.request("inventory", Map.of());
        assertEquals(PackCatalogMessage.CAPTURING, next().state());
        awaitCaptures(2);
        assertNull(this.sent.poll(100, TimeUnit.MILLISECONDS));
    }

    @Test
    void aLanguageChangeCapturesAgainForTheSameInventory() throws Exception {
        PackCatalogPublisher publisher = publisher(this.directory.resolve("catalog.json"));
        publisher.request("inventory", Map.of());
        assertEquals(PackCatalogMessage.CAPTURING, next().state());
        this.capture.complete(catalog("inventory", "en_us"));
        assertEquals(PackCatalogMessage.AVAILABLE, next().state());

        this.language.set("de_de");
        this.capture = new CompletableFuture<>();
        publisher.request("inventory", Map.of());

        assertEquals(PackCatalogMessage.CAPTURING, next().state());
        awaitCaptures(2);
    }

    @Test
    void aSupersededCaptureNeitherWritesNorAnnounces() throws Exception {
        Path file = this.directory.resolve("catalog.json");
        PackCatalogPublisher publisher = publisher(file);
        publisher.request("old", Map.of());
        assertEquals(PackCatalogMessage.CAPTURING, next().state());
        awaitCaptures(1);
        CompletableFuture<PackCatalog> superseded = this.capture;
        this.capture = new CompletableFuture<>();
        publisher.request("new", Map.of());
        assertEquals(PackCatalogMessage.CAPTURING, next().state());
        awaitCaptures(2);

        superseded.complete(catalog("old", "en_us"));
        this.capture.complete(catalog("new", "en_us"));

        PackCatalogMessage available = next();
        assertEquals("new", available.inventoryId());
        assertNull(this.sent.poll(100, TimeUnit.MILLISECONDS));
        assertEquals("new", PackCatalog.readHeader(file).inventoryId());
    }

    private PackCatalogPublisher publisher(Path file) {
        this.publisher = new PackCatalogPublisher(file, this.language::get, (inventoryId, language, modules) -> {
            // Take the future before counting, so a test that saw the count can replace the future for the next call.
            CompletableFuture<PackCatalog> capture = this.capture;
            this.captures.incrementAndGet();
            return capture;
        }, this.sent::add);
        return this.publisher;
    }

    /** Waits for the capture count: the publisher announces a capture just before it starts one. */
    private void awaitCaptures(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (this.captures.get() < expected && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(expected, this.captures.get());
    }

    private PackCatalogMessage next() throws InterruptedException {
        return this.sent.poll(5, TimeUnit.SECONDS);
    }

    private static PackCatalog catalog(String inventoryId, String language) {
        return new PackCatalog(inventoryId, language, List.of(), List.of(),
                List.of(new PackCatalog.ItemEntry("minecraft:stone", "Stone", "net.minecraft.world.item.BlockItem",
                        "minecraft:stone", "", Map.of())), List.of(), List.of(), List.of(), Map.of());
    }
}
